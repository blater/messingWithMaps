package dn.strategicmap.pipeline;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Extracts review candidates for unusually large, widely tracked labels in a reference map.
 *
 * <p>This is a bounded offline authoring operation. Each target examines one crop of a roughly
 * 9,000 by 5,000 reference image, allocates arrays proportional to that crop, and performs linear
 * connected-component work followed by {@code O(g^2)} row construction over {@code g} retained
 * large components. The real POC crops retain at most a few hundred components (142 in the
 * initial ocean pass), so this deliberately simple super-linear stage is bounded and remains far
 * below image traversal cost. It is never called from loading or rendering code.</p>
 */
public final class ReferenceDisplayLabelExtractor {
  private static final Color[] CANDIDATE_COLORS = {
      new Color(205, 30, 35), new Color(20, 105, 205), new Color(20, 145, 65)
  };

  private ReferenceDisplayLabelExtractor() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Expected <reference-image> <calibration.tsv> <targets.tsv> <output-directory>");
    }
    extract(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
  }

  static void extract(Path referencePath, Path calibrationPath, Path targetsPath, Path outputDirectory)
      throws IOException {
    BufferedImage reference = ImageIO.read(referencePath.toFile());
    if (reference == null) {
      throw new IOException("Unable to read reference image: " + referencePath);
    }
    CalibrationGrid grid = CalibrationGrid.read(calibrationPath);
    List<Target> targets = Target.read(targetsPath);
    Files.createDirectories(outputDirectory);

    Map<AnalysisKey, Analysis> analyses = new HashMap<>();
    Map<Target, Analysis> analysisByTarget = new LinkedHashMap<>();
    Map<Target, List<Candidate>> candidatesByTarget = new LinkedHashMap<>();
    for (Target target : targets) {
      AnalysisKey analysisKey = new AnalysisKey(
          target.crop(), target.minimumCapHeight(), target.maximumCapHeight(),
          target.maximumInkLuminance());
      Analysis analysis = analyses.computeIfAbsent(analysisKey, key ->
          analyse(reference, key.crop(), grid, key.minimumCapHeight(), key.maximumCapHeight(),
              key.maximumInkLuminance()));
      analysisByTarget.put(target, analysis);
      candidatesByTarget.put(target, rankCandidates(analysis.glyphs(), target));
    }
    Map<Target, Candidate> selectedByTarget = selectOrderedLines(targets, candidatesByTarget);

    Path candidatesPath = outputDirectory.resolve("display-label-candidates.tsv");
    try (BufferedWriter writer = Files.newBufferedWriter(candidatesPath)) {
      writer.write("labelKey\tlineText\trank\tscore\tevidenceGlyphs\texpectedGlyphs"
          + "\tinferredGlyphs\tcapHeightPixels\ttrackingPixels\timagePath\treviewImage\n");
      for (Target target : targets) {
        Analysis analysis = analysisByTarget.get(target);
        List<Candidate> candidates = selectedFirst(
            candidatesByTarget.get(target), selectedByTarget.get(target));
        Path reviewPath = outputDirectory.resolve(target.fileStem() + ".png");
        writeReviewImage(reference, analysis, target, candidates, reviewPath);
        int limit = Math.min(3, candidates.size());
        for (int index = 0; index < limit; index++) {
          writeCandidate(writer, target, candidates.get(index), index + 1, reviewPath.getFileName());
        }
        System.out.printf(Locale.ROOT,
            "%s %s: retained=%d candidates=%d best=%.3f review=%s%n",
            target.labelKey(), target.lineText(), analysis.glyphs().size(), candidates.size(),
            candidates.isEmpty() ? 0.0 : candidates.getFirst().score(), reviewPath);
      }
    }
    System.out.printf("Prepared %s: targets=%d crops=%d%n",
        candidatesPath, targets.size(), analyses.size());
  }

  private static List<Candidate> selectedFirst(List<Candidate> candidates, Candidate selected) {
    if (selected == null || candidates.isEmpty() || candidates.getFirst() == selected) {
      return candidates;
    }
    List<Candidate> ordered = new ArrayList<>(candidates.size());
    ordered.add(selected);
    for (Candidate candidate : candidates) {
      if (candidate != selected) {
        ordered.add(candidate);
      }
    }
    return ordered;
  }

  private static Map<Target, Candidate> selectOrderedLines(
      List<Target> targets, Map<Target, List<Candidate>> candidatesByTarget) {
    Map<GroupKey, List<Target>> groups = new LinkedHashMap<>();
    for (Target target : targets) {
      groups.computeIfAbsent(new GroupKey(target.labelKey(), target.crop()), ignored ->
          new ArrayList<>()).add(target);
    }
    Map<Target, Candidate> selected = new HashMap<>();
    for (List<Target> group : groups.values()) {
      group.sort(Comparator.comparingInt(Target::lineOrder));
      OrderedLineSearch search = new OrderedLineSearch(group, candidatesByTarget);
      search.run();
      for (int index = 0; index < search.best().size(); index++) {
        selected.put(group.get(index), search.best().get(index));
      }
    }
    return selected;
  }

  private static Analysis analyse(
      BufferedImage reference,
      Crop crop,
      CalibrationGrid grid,
      int minimumHeight,
      int maximumHeight,
      int maximumInkLuminance) {
    int width = crop.width();
    int height = crop.height();
    boolean[] ink = new boolean[width * height];
    boolean[] removedRed = new boolean[ink.length];
    boolean[] removedGrid = new boolean[ink.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int rgb = reference.getRGB(crop.x() + x, crop.y() + y);
        int red = rgb >>> 16 & 0xff;
        int green = rgb >>> 8 & 0xff;
        int blue = rgb & 0xff;
        int luminance = (red * 54 + green * 183 + blue * 19) >> 8;
        if (luminance >= maximumInkLuminance) {
          continue;
        }
        int offset = y * width + x;
        if (red * 100 > green * 116 && red * 100 > blue * 116) {
          removedRed[offset] = true;
        } else if (grid.contains(crop.x() + x, crop.y() + y, 3)) {
          removedGrid[offset] = true;
        } else {
          ink[offset] = true;
        }
      }
    }
    // The normal threshold needs a small repair after calibrated line removal. Softer thresholds
    // already join faint strokes and terrain ink; bridging them would merge title glyphs into
    // large map components and destroy the very evidence the soft pass is intended to recover.
    if (maximumInkLuminance <= 165) {
      bridgeNarrowGaps(ink, width, height, 7);
    }

    Components components = components(ink, width, height);
    List<Glyph> glyphs = components.items().stream()
        .filter(component -> component.height() >= minimumHeight)
        .filter(component -> component.height() <= maximumHeight)
        .filter(component -> component.width() >= 2 && component.width() <= maximumHeight)
        .filter(component -> component.area() >= 12)
        .filter(component -> component.width() / (double) component.height() <= 3.5)
        .map(Glyph::new)
        .toList();
    boolean[] retainedLabels = new boolean[components.items().size() + 1];
    for (Glyph glyph : glyphs) {
      retainedLabels[glyph.component().label()] = true;
    }
    return new Analysis(
        crop, ink, removedRed, removedGrid, components.labelByPixel(), retainedLabels, glyphs);
  }

  private static void bridgeNarrowGaps(boolean[] ink, int width, int height, int maximumGap) {
    boolean[] bridged = ink.clone();
    for (int y = 0; y < height; y++) {
      int x = 0;
      while (x < width) {
        while (x < width && ink[y * width + x]) {
          x++;
        }
        int start = x;
        while (x < width && !ink[y * width + x]) {
          x++;
        }
        if (start > 0 && x < width && x - start <= maximumGap) {
          Arrays.fill(bridged, y * width + start, y * width + x, true);
        }
      }
    }
    for (int x = 0; x < width; x++) {
      int y = 0;
      while (y < height) {
        while (y < height && bridged[y * width + x]) {
          y++;
        }
        int start = y;
        while (y < height && !bridged[y * width + x]) {
          y++;
        }
        if (start > 0 && y < height && y - start <= maximumGap) {
          for (int fillY = start; fillY < y; fillY++) {
            ink[fillY * width + x] = true;
          }
        }
      }
    }
    for (int offset = 0; offset < ink.length; offset++) {
      ink[offset] |= bridged[offset];
    }
  }

  private static Components components(boolean[] ink, int width, int height) {
    int[] labels = new int[ink.length];
    List<Component> components = new ArrayList<>();
    int[] queue = new int[ink.length];
    int label = 0;
    for (int start = 0; start < ink.length; start++) {
      if (!ink[start] || labels[start] != 0) {
        continue;
      }
      label++;
      int head = 0;
      int tail = 0;
      queue[tail++] = start;
      labels[start] = label;
      int minimumX = start % width;
      int maximumX = minimumX;
      int minimumY = start / width;
      int maximumY = minimumY;
      int area = 0;
      while (head < tail) {
        int offset = queue[head++];
        int x = offset % width;
        int y = offset / width;
        area++;
        minimumX = Math.min(minimumX, x);
        maximumX = Math.max(maximumX, x);
        minimumY = Math.min(minimumY, y);
        maximumY = Math.max(maximumY, y);
        for (int neighbourY = Math.max(0, y - 1);
            neighbourY <= Math.min(height - 1, y + 1); neighbourY++) {
          for (int neighbourX = Math.max(0, x - 1);
              neighbourX <= Math.min(width - 1, x + 1); neighbourX++) {
            int neighbour = neighbourY * width + neighbourX;
            if (ink[neighbour] && labels[neighbour] == 0) {
              labels[neighbour] = label;
              queue[tail++] = neighbour;
            }
          }
        }
      }
      components.add(new Component(
          label, minimumX, minimumY, maximumX - minimumX + 1, maximumY - minimumY + 1, area));
    }
    return new Components(labels, components);
  }

  private static List<Candidate> rankCandidates(List<Glyph> glyphs, Target target) {
    int expected = target.expectedGlyphs();
    int minimumCount = Math.max(2, expected - target.maximumMissingGlyphs());
    int maximumCount = expected + 2;
    Map<String, Candidate> unique = new LinkedHashMap<>();
    for (Glyph seed : glyphs) {
      List<Glyph> row = glyphs.stream()
          .filter(glyph -> compatible(seed, glyph))
          .sorted(Comparator.comparingDouble(Glyph::centreX))
          .toList();
      for (List<Glyph> run : splitRuns(row, seed.height() * 16.0)) {
        for (int count = minimumCount; count <= Math.min(maximumCount, run.size()); count++) {
          for (int start = 0; start + count <= run.size(); start++) {
            List<Glyph> window = run.subList(start, start + count);
            Candidate candidate = candidate(window, expected);
            String key = candidate.boundsKey();
            Candidate previous = unique.get(key);
            if (previous == null || candidate.score() > previous.score()) {
              unique.put(key, candidate);
            }
          }
        }
      }
    }
    return unique.values().stream()
        .sorted(Comparator.comparingDouble(Candidate::score).reversed())
        .toList();
  }

  private static boolean compatible(Glyph first, Glyph second) {
    double heightRatio = second.height() / first.height();
    double baselineTolerance = Math.max(10.0, first.height() * 0.60);
    return heightRatio >= 0.48 && heightRatio <= 2.10
        && Math.abs(first.baselineY() - second.baselineY()) <= baselineTolerance;
  }

  private static List<List<Glyph>> splitRuns(List<Glyph> row, double maximumGap) {
    if (row.isEmpty()) {
      return List.of();
    }
    List<List<Glyph>> runs = new ArrayList<>();
    List<Glyph> current = new ArrayList<>();
    current.add(row.getFirst());
    for (int index = 1; index < row.size(); index++) {
      Glyph previous = row.get(index - 1);
      Glyph glyph = row.get(index);
      if (glyph.minimumX() - previous.maximumX() > maximumGap) {
        runs.add(List.copyOf(current));
        current.clear();
      }
      current.add(glyph);
    }
    runs.add(List.copyOf(current));
    return runs;
  }

  private static Candidate candidate(List<Glyph> glyphs, int expectedGlyphs) {
    Curve curve = Curve.fit(glyphs);
    double medianHeight = median(glyphs.stream().mapToDouble(Glyph::height).toArray());
    double[] gaps = new double[Math.max(0, glyphs.size() - 1)];
    for (int index = 1; index < glyphs.size(); index++) {
      gaps[index - 1] = glyphs.get(index).centreX() - glyphs.get(index - 1).centreX();
    }
    double medianGap = gaps.length == 0 ? medianHeight : median(gaps);
    double heightDeviation = standardDeviation(
        glyphs.stream().mapToDouble(Glyph::height).toArray(), medianHeight) / medianHeight;
    double gapDeviation = gaps.length == 0 ? 1.0 : regularGapDeviation(gaps, medianGap);
    double countScore = Math.max(0.0,
        1.0 - Math.abs(glyphs.size() - expectedGlyphs) / (double) expectedGlyphs);
    double heightScore = Math.exp(-heightDeviation * 4.0);
    double baselineScore = Math.exp(-(curve.rmse() / medianHeight) * 6.0);
    double gapScore = Math.exp(-gapDeviation * 3.0);
    double trackingRatio = medianGap / medianHeight;
    double displaySpacingScore = trackingRatio >= 0.75 && trackingRatio <= 4.5 ? 1.0 : 0.3;
    double score = countScore * 0.38 + heightScore * 0.18 + baselineScore * 0.24
        + gapScore * 0.15 + displaySpacingScore * 0.05;
    double tracking = glyphs.size() < expectedGlyphs && expectedGlyphs > 1
        ? (glyphs.getLast().centreX() - glyphs.getFirst().centreX()) / (expectedGlyphs - 1)
        : medianGap;
    return new Candidate(List.copyOf(glyphs), curve, score, medianHeight, tracking,
        Math.max(0, expectedGlyphs - glyphs.size()));
  }

  private static double regularGapDeviation(double[] gaps, double medianGap) {
    double total = 0.0;
    for (double gap : gaps) {
      double multiple = Math.max(1.0, Math.rint(gap / medianGap));
      total += Math.abs(gap - multiple * medianGap) / medianGap;
    }
    return total / gaps.length;
  }

  private static double median(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 0
        ? (sorted[middle - 1] + sorted[middle]) * 0.5 : sorted[middle];
  }

  private static double standardDeviation(double[] values, double centre) {
    double total = 0.0;
    for (double value : values) {
      double difference = value - centre;
      total += difference * difference;
    }
    return Math.sqrt(total / values.length);
  }

  private static void writeCandidate(
      BufferedWriter writer, Target target, Candidate candidate, int rank, Path reviewImage)
      throws IOException {
    writer.write(target.labelKey());
    writer.write('\t');
    writer.write(target.lineText());
    writer.write('\t');
    writer.write(Integer.toString(rank));
    writer.write('\t');
    writer.write(String.format(Locale.ROOT, "%.4f", candidate.score()));
    writer.write('\t');
    writer.write(Integer.toString(candidate.glyphs().size()));
    writer.write('\t');
    writer.write(Integer.toString(target.expectedGlyphs()));
    writer.write('\t');
    writer.write(Integer.toString(candidate.inferredGlyphs()));
    writer.write('\t');
    writer.write(String.format(Locale.ROOT, "%.2f", candidate.capHeight()));
    writer.write('\t');
    writer.write(String.format(Locale.ROOT, "%.2f", candidate.tracking()));
    writer.write('\t');
    writer.write(candidate.curve().pathText(target.crop()));
    writer.write('\t');
    writer.write(reviewImage.toString());
    writer.write('\n');
  }

  private static void writeReviewImage(
      BufferedImage reference, Analysis analysis, Target target, List<Candidate> candidates,
      Path output) throws IOException {
    Crop crop = target.crop();
    int heading = 42;
    BufferedImage review = new BufferedImage(
        crop.width(), crop.height() * 2 + heading, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = review.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, review.getWidth(), review.getHeight());
    graphics.drawImage(reference,
        0, heading, crop.width(), heading + crop.height(),
        crop.x(), crop.y(), crop.x() + crop.width(), crop.y() + crop.height(), null);

    int maskY = heading + crop.height();
    for (int y = 0; y < crop.height(); y++) {
      for (int x = 0; x < crop.width(); x++) {
        int offset = y * crop.width() + x;
        int componentLabel = analysis.componentLabels()[offset];
        if (componentLabel > 0 && analysis.retainedLabels()[componentLabel]) {
          review.setRGB(x, maskY + y, Color.BLACK.getRGB());
        } else if (analysis.removedRed()[offset]) {
          review.setRGB(x, maskY + y, new Color(250, 220, 220).getRGB());
        } else if (analysis.removedGrid()[offset]) {
          review.setRGB(x, maskY + y, new Color(210, 225, 250).getRGB());
        }
      }
    }

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    graphics.setColor(Color.BLACK);
    graphics.drawString(target.labelKey() + " — " + target.lineText(), 12, 28);
    graphics.setStroke(new BasicStroke(3.0f));
    for (int rank = 0; rank < Math.min(3, candidates.size()); rank++) {
      Candidate candidate = candidates.get(rank);
      graphics.setColor(CANDIDATE_COLORS[rank]);
      for (Glyph glyph : candidate.glyphs()) {
        graphics.drawRect(glyph.minimumX(), maskY + glyph.minimumY(),
            glyph.width(), glyph.height());
      }
      List<ImagePoint> path = candidate.curve().localPath();
      for (int index = 1; index < path.size(); index++) {
        ImagePoint first = path.get(index - 1);
        ImagePoint second = path.get(index);
        graphics.drawLine((int) first.x(), maskY + (int) first.y(),
            (int) second.x(), maskY + (int) second.y());
      }
      graphics.drawString(String.format(Locale.ROOT, "#%d %.3f", rank + 1, candidate.score()),
          12 + rank * 130, maskY + 28);
    }
    graphics.dispose();
    ImageIO.write(review, "png", output.toFile());
  }

  private record Crop(int x, int y, int width, int height) {}

  private record AnalysisKey(
      Crop crop, int minimumCapHeight, int maximumCapHeight, int maximumInkLuminance) {}

  private record Target(
      String labelKey,
      String lineText,
      int lineOrder,
      Crop crop,
      int minimumCapHeight,
      int maximumCapHeight,
      int maximumMissingGlyphs,
      int maximumInkLuminance) {
    static List<Target> read(Path source) throws IOException {
      List<Target> targets = new ArrayList<>();
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        targets.add(new Target(fields[0], fields[1], Integer.parseInt(fields[2]), new Crop(
            Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
            Integer.parseInt(fields[5]), Integer.parseInt(fields[6])),
            Integer.parseInt(fields[7]), Integer.parseInt(fields[8]),
            Integer.parseInt(fields[9]), fields.length > 11 ? Integer.parseInt(fields[11]) : 165));
      }
      return targets;
    }

    int expectedGlyphs() {
      return (int) lineText.codePoints().filter(Character::isLetterOrDigit).count();
    }

    String fileStem() {
      return (labelKey + "-" + lineText).toLowerCase(Locale.ROOT)
          .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
  }

  private record CalibrationGrid(double[] verticalLines, double[] horizontalLines) {
    static CalibrationGrid read(Path source) throws IOException {
      List<Double> vertical = new ArrayList<>();
      List<Double> horizontal = new ArrayList<>();
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("axis\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        (fields[0].equals("X") ? vertical : horizontal).add(Double.parseDouble(fields[1]));
      }
      return new CalibrationGrid(
          vertical.stream().mapToDouble(Double::doubleValue).toArray(),
          horizontal.stream().mapToDouble(Double::doubleValue).toArray());
    }

    boolean contains(int imageX, int imageY, int halfWidth) {
      for (double line : verticalLines) {
        if (Math.abs(imageX - line) <= halfWidth) {
          return true;
        }
      }
      for (double line : horizontalLines) {
        if (Math.abs(imageY - line) <= halfWidth) {
          return true;
        }
      }
      return false;
    }
  }

  private record Component(int label, int x, int y, int width, int height, int area) {}

  private record Components(int[] labelByPixel, List<Component> items) {}

  private record Glyph(Component component) {
    double centreX() {
      return component.x() + component.width() * 0.5;
    }

    double baselineY() {
      return component.y() + component.height();
    }

    int minimumX() {
      return component.x();
    }

    int maximumX() {
      return component.x() + component.width() - 1;
    }

    int minimumY() {
      return component.y();
    }

    int width() {
      return component.width();
    }

    int height() {
      return component.height();
    }
  }

  private record Analysis(
      Crop crop,
      boolean[] ink,
      boolean[] removedRed,
      boolean[] removedGrid,
      int[] componentLabels,
      boolean[] retainedLabels,
      List<Glyph> glyphs) {}

  private record GroupKey(String labelKey, Crop crop) {}

  private static final class OrderedLineSearch {
    private final List<Target> targets;
    private final Map<Target, List<Candidate>> candidatesByTarget;
    private List<Candidate> best = List.of();
    private double bestScore = Double.NEGATIVE_INFINITY;

    OrderedLineSearch(List<Target> targets, Map<Target, List<Candidate>> candidatesByTarget) {
      this.targets = targets;
      this.candidatesByTarget = candidatesByTarget;
    }

    void run() {
      visit(0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, new ArrayList<>());
    }

    private void visit(
        int index,
        double previousBaseline,
        double previousMaximumX,
        double score,
        List<Candidate> selection) {
      if (index == targets.size()) {
        if (score > bestScore) {
          bestScore = score;
          best = new ArrayList<>(selection);
        }
        return;
      }
      List<Candidate> candidates = candidatesByTarget.get(targets.get(index));
      if (candidates.isEmpty()) {
        selection.add(null);
        visit(index + 1, previousBaseline, previousMaximumX, score - 1.0, selection);
        selection.removeLast();
        return;
      }
      boolean samePrintedLine = index > 0
          && targets.get(index).lineOrder() == targets.get(index - 1).lineOrder();
      int limit = candidates.size();
      for (int candidateIndex = 0; candidateIndex < limit; candidateIndex++) {
        Candidate candidate = candidates.get(candidateIndex);
        double baseline = candidate.meanBaseline();
        if (samePrintedLine
            && (Math.abs(baseline - previousBaseline) > candidate.capHeight() * 0.80
                || candidate.minimumX() <= previousMaximumX)) {
          continue;
        }
        if (!samePrintedLine && baseline <= previousBaseline + candidate.capHeight() * 0.45) {
          continue;
        }
        selection.add(candidate);
        visit(index + 1, baseline, candidate.maximumX(), score + candidate.score(), selection);
        selection.removeLast();
      }
    }

    List<Candidate> best() {
      return best;
    }
  }

  private record Candidate(
      List<Glyph> glyphs,
      Curve curve,
      double score,
      double capHeight,
      double tracking,
      int inferredGlyphs) {
    String boundsKey() {
      return glyphs.getFirst().minimumX() + ":" + glyphs.getFirst().minimumY() + ":"
          + glyphs.getLast().maximumX() + ":" + glyphs.getLast().minimumY();
    }

    double meanBaseline() {
      double midpoint = (curve.minimumX() + curve.maximumX()) * 0.5;
      return curve.y(midpoint);
    }

    double minimumX() {
      return glyphs.getFirst().minimumX();
    }

    double maximumX() {
      return glyphs.getLast().maximumX();
    }
  }

  private record ImagePoint(double x, double y) {}

  private record Curve(double a, double b, double c, double minimumX, double maximumX, double rmse) {
    static Curve fit(List<Glyph> glyphs) {
      double minimumX = glyphs.getFirst().centreX();
      double maximumX = glyphs.getLast().centreX();
      if (maximumX == minimumX) {
        return new Curve(0.0, 0.0, glyphs.getFirst().baselineY(), minimumX, maximumX, 0.0);
      }
      double[][] normal = new double[3][4];
      for (Glyph glyph : glyphs) {
        double t = (glyph.centreX() - minimumX) / (maximumX - minimumX);
        double y = glyph.baselineY();
        double[] powers = {1.0, t, t * t, t * t * t, t * t * t * t};
        normal[0][0] += powers[0];
        normal[0][1] += powers[1];
        normal[0][2] += powers[2];
        normal[0][3] += y;
        normal[1][0] += powers[1];
        normal[1][1] += powers[2];
        normal[1][2] += powers[3];
        normal[1][3] += t * y;
        normal[2][0] += powers[2];
        normal[2][1] += powers[3];
        normal[2][2] += powers[4];
        normal[2][3] += t * t * y;
      }
      double[] coefficients = solve(normal);
      double totalSquaredError = 0.0;
      for (Glyph glyph : glyphs) {
        double t = (glyph.centreX() - minimumX) / (maximumX - minimumX);
        double difference = glyph.baselineY()
            - (coefficients[0] + coefficients[1] * t + coefficients[2] * t * t);
        totalSquaredError += difference * difference;
      }
      return new Curve(coefficients[2], coefficients[1], coefficients[0], minimumX, maximumX,
          Math.sqrt(totalSquaredError / glyphs.size()));
    }

    private static double[] solve(double[][] matrix) {
      for (int pivot = 0; pivot < 3; pivot++) {
        int strongest = pivot;
        for (int row = pivot + 1; row < 3; row++) {
          if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[strongest][pivot])) {
            strongest = row;
          }
        }
        double[] swap = matrix[pivot];
        matrix[pivot] = matrix[strongest];
        matrix[strongest] = swap;
        if (Math.abs(matrix[pivot][pivot]) < 1.0e-9) {
          return new double[] {matrix[0][3] / Math.max(1.0, matrix[0][0]), 0.0, 0.0};
        }
        double divisor = matrix[pivot][pivot];
        for (int column = pivot; column < 4; column++) {
          matrix[pivot][column] /= divisor;
        }
        for (int row = 0; row < 3; row++) {
          if (row == pivot) {
            continue;
          }
          double factor = matrix[row][pivot];
          for (int column = pivot; column < 4; column++) {
            matrix[row][column] -= factor * matrix[pivot][column];
          }
        }
      }
      return new double[] {matrix[0][3], matrix[1][3], matrix[2][3]};
    }

    double y(double x) {
      double t = maximumX == minimumX ? 0.0 : (x - minimumX) / (maximumX - minimumX);
      return a * t * t + b * t + c;
    }

    List<ImagePoint> localPath() {
      double midpoint = (minimumX + maximumX) * 0.5;
      double linearMidpoint = (y(minimumX) + y(maximumX)) * 0.5;
      int samples = Math.abs(y(midpoint) - linearMidpoint) >= 1.0 ? 5 : 2;
      List<ImagePoint> path = new ArrayList<>(samples);
      for (int index = 0; index < samples; index++) {
        double x = minimumX + (maximumX - minimumX) * index / (samples - 1.0);
        path.add(new ImagePoint(x, y(x)));
      }
      return path;
    }

    String pathText(Crop crop) {
      StringBuilder text = new StringBuilder();
      for (ImagePoint point : localPath()) {
        if (!text.isEmpty()) {
          text.append(';');
        }
        text.append(String.format(Locale.ROOT, "%.1f,%.1f",
            crop.x() + point.x(), crop.y() + point.y()));
      }
      return text.toString();
    }
  }
}
