package dn;

import dn.strategicmap.feature.MapRank;
import dn.strategicmap.render.MapPresentationPolicy;
import dn.strategicmap.render.ZoomBand;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Integration-owned build adapter for the cumulative GRAND/THEATRE reference-label catalogue.
 *
 * <p>This is the only build code in the slice that reads both physical map presentation data and
 * political presentation data. It emits generic label descriptions and extractor targets; the
 * OCR/extraction pipeline receives no political models. The work is linear in the small source
 * catalogues and runs only as an explicit asset-authoring task.</p>
 */
public final class GrandTheatreReferenceTargetBuilder {
  private GrandTheatreReferenceTargetBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 8) {
      throw new IllegalArgumentException(
          "Expected <reference-image> <calibration.tsv> <geographic-labels.tsv>"
              + " <political-placements.tsv> <actors.tsv> <target-overrides.tsv>"
              + " <catalogue-output.tsv> <targets-output.tsv>");
    }
    build(
        Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
        Path.of(args[4]), Path.of(args[5]), Path.of(args[6]), Path.of(args[7]));
  }

  static void build(
      Path referencePath,
      Path calibrationPath,
      Path geographicLabelsPath,
      Path politicalPlacementsPath,
      Path actorsPath,
      Path overridesPath,
      Path catalogueOutput,
      Path targetsOutput) throws IOException {
    BufferedImage reference = ImageIO.read(referencePath.toFile());
    if (reference == null) {
      throw new IOException("Unable to read reference image: " + referencePath);
    }
    Calibration calibration = Calibration.read(calibrationPath);
    Map<String, Actor> actors = readActors(actorsPath);
    LinkedHashMap<String, Label> labels = readGeographicLabels(geographicLabelsPath);
    readPoliticalLabels(politicalPlacementsPath, actors, labels);
    Map<String, List<Override>> overrides = readOverrides(overridesPath);

    Files.createDirectories(catalogueOutput.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(catalogueOutput)) {
      writer.write("labelKey\tdisplayText\tcategory\tminimumBand\tlatitude\tlongitude\tsource\n");
      for (Label label : labels.values()) {
        writer.write(label.labelKey() + "\t" + label.displayText() + "\t" + label.category()
            + "\t" + label.minimumBand() + "\t" + label.latitude() + "\t"
            + label.longitude() + "\t" + label.source() + "\n");
      }
    }

    Files.createDirectories(targetsOutput.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(targetsOutput)) {
      writer.write("labelKey\tlineText\tlineOrder\tcropX\tcropY\tcropWidth\tcropHeight"
          + "\tminimumCapHeight\tmaximumCapHeight\tmaximumMissingGlyphs\trenderText"
          + "\tmaximumInkLuminance\n");
      for (Label label : labels.values()) {
        List<Override> labelOverrides = overrides.get(label.labelKey());
        if (labelOverrides == null) {
          writeTarget(writer, automaticTarget(label, label.displayText(), 0, calibration,
              reference.getWidth(), reference.getHeight(), null));
          continue;
        }
        for (Override override : labelOverrides) {
          writeTarget(writer, automaticTarget(label, override.lineText(), override.lineOrder(),
              calibration, reference.getWidth(), reference.getHeight(), override));
        }
      }
    }
    System.out.printf(Locale.ROOT, "Prepared %s and %s: labels=%d targetLines=%d%n",
        catalogueOutput, targetsOutput, labels.size(),
        labels.values().stream().mapToInt(label ->
            overrides.getOrDefault(label.labelKey(), List.of()).isEmpty()
                ? 1 : overrides.get(label.labelKey()).size()).sum());
  }

  private static LinkedHashMap<String, Label> readGeographicLabels(Path source) throws IOException {
    LinkedHashMap<String, Label> labels = new LinkedHashMap<>();
    for (String line : Files.readAllLines(source)) {
      if (ignored(line, "labelId\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      MapRank rank = MapRank.valueOf(fields[5]);
      ZoomBand minimumBand = MapPresentationPolicy.minimumBand(rank);
      if (minimumBand.ordinal() > ZoomBand.THEATRE.ordinal()) {
        continue;
      }
      String category = switch (fields[2]) {
        case "OCEAN" -> "WATER";
        case "SEA" -> "SEA";
        default -> "LAND";
      };
      labels.put(fields[0], new Label(
          fields[0], fields[1], category, minimumBand,
          Double.parseDouble(fields[3]), Double.parseDouble(fields[4]), "GEOGRAPHIC"));
    }
    return labels;
  }

  private static void readPoliticalLabels(
      Path source, Map<String, Actor> actors, LinkedHashMap<String, Label> labels) throws IOException {
    for (String line : Files.readAllLines(source)) {
      if (ignored(line, "actorId\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      ZoomBand minimumBand = ZoomBand.valueOf(fields[3]);
      if (minimumBand.ordinal() > ZoomBand.THEATRE.ordinal()) {
        continue;
      }
      Actor actor = actors.get(fields[0]);
      if (actor == null) {
        throw new IllegalStateException("Political label has no actor: " + fields[0]);
      }
      String labelKey = "political." + fields[0];
      labels.put(labelKey, new Label(
          labelKey, actor.displayName(), actor.playable() ? "PRIMARY_GROUP" : "SECONDARY_GROUP",
          minimumBand, Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), "POLITICAL"));
    }
  }

  private static Map<String, Actor> readActors(Path source) throws IOException {
    Map<String, Actor> actors = new HashMap<>();
    for (String line : Files.readAllLines(source)) {
      if (ignored(line, "actorId\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      actors.put(fields[0], new Actor(fields[1], Boolean.parseBoolean(fields[4])));
    }
    return actors;
  }

  private static Map<String, List<Override>> readOverrides(Path source) throws IOException {
    Map<String, List<Override>> overrides = new HashMap<>();
    for (String line : Files.readAllLines(source)) {
      if (ignored(line, "labelKey\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      Override override = new Override(
          fields[1], Integer.parseInt(fields[2]),
          nullableInteger(fields[3]), nullableInteger(fields[4]), nullableInteger(fields[5]),
          nullableInteger(fields[6]), nullableInteger(fields[7]), nullableInteger(fields[8]),
          nullableInteger(fields[9]), fields.length > 10 && !fields[10].isBlank()
              ? fields[10] : fields[1], fields.length > 11 && !fields[11].isBlank()
              ? Integer.parseInt(fields[11]) : 165);
      overrides.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(override);
    }
    for (List<Override> values : overrides.values()) {
      values.sort(Comparator.comparingInt(Override::lineOrder));
    }
    return overrides;
  }

  private static Target automaticTarget(
      Label label,
      String lineText,
      int lineOrder,
      Calibration calibration,
      int imageWidth,
      int imageHeight,
      Override override) {
    int defaultWidth = switch (label.minimumBand()) {
      case WORLD -> 2_200;
      case GRAND -> 1_700;
      case THEATRE -> 1_100;
      default -> throw new IllegalStateException("Unexpected band " + label.minimumBand());
    };
    int defaultHeight = switch (label.minimumBand()) {
      case WORLD -> 1_300;
      case GRAND -> 1_000;
      case THEATRE -> 750;
      default -> throw new IllegalStateException("Unexpected band " + label.minimumBand());
    };
    int width = value(override == null ? null : override.cropWidth(), defaultWidth);
    int height = value(override == null ? null : override.cropHeight(), defaultHeight);
    int centreX = (int) Math.round(calibration.imageX(canonicalReferenceLongitude(label.longitude())));
    int centreY = (int) Math.round(calibration.imageY(label.latitude()));
    int cropX = value(override == null ? null : override.cropX(), clamp(centreX - width / 2, 0,
        Math.max(0, imageWidth - width)));
    int cropY = value(override == null ? null : override.cropY(), clamp(centreY - height / 2, 0,
        Math.max(0, imageHeight - height)));
    int minimumHeight = value(override == null ? null : override.minimumCapHeight(),
        label.minimumBand() == ZoomBand.THEATRE ? 8 : 14);
    int maximumHeight = value(override == null ? null : override.maximumCapHeight(),
        label.minimumBand() == ZoomBand.THEATRE ? 52 : 90);
    int missing = value(override == null ? null : override.maximumMissingGlyphs(), 2);
    return new Target(label.labelKey(), lineText.toUpperCase(Locale.ROOT), lineOrder,
        cropX, cropY, width, height, minimumHeight, maximumHeight, missing,
        override == null ? lineText.toUpperCase(Locale.ROOT) : override.renderText(),
        override == null ? 165 : override.maximumInkLuminance());
  }

  private static double canonicalReferenceLongitude(double longitude) {
    return longitude < 0.0 ? longitude + 360.0 : longitude;
  }

  private static void writeTarget(BufferedWriter writer, Target target) throws IOException {
    writer.write(target.labelKey() + "\t" + target.lineText() + "\t" + target.lineOrder()
        + "\t" + target.cropX() + "\t" + target.cropY() + "\t" + target.cropWidth()
        + "\t" + target.cropHeight() + "\t" + target.minimumCapHeight() + "\t"
        + target.maximumCapHeight() + "\t" + target.maximumMissingGlyphs() + "\t"
        + target.renderText() + "\t" + target.maximumInkLuminance() + "\n");
  }

  private static boolean ignored(String line, String headerPrefix) {
    return line.isBlank() || line.startsWith("#") || line.startsWith(headerPrefix);
  }

  private static Integer nullableInteger(String value) {
    return value.isBlank() ? null : Integer.valueOf(value);
  }

  private static int value(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private record Label(
      String labelKey,
      String displayText,
      String category,
      ZoomBand minimumBand,
      double latitude,
      double longitude,
      String source) {}

  private record Actor(String displayName, boolean playable) {}

  private record Override(
      String lineText,
      int lineOrder,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight,
      Integer minimumCapHeight,
      Integer maximumCapHeight,
      Integer maximumMissingGlyphs,
      String renderText,
      int maximumInkLuminance) {}

  private record Target(
      String labelKey,
      String lineText,
      int lineOrder,
      int cropX,
      int cropY,
      int cropWidth,
      int cropHeight,
      int minimumCapHeight,
      int maximumCapHeight,
      int maximumMissingGlyphs,
      String renderText,
      int maximumInkLuminance) {}

  private enum Axis { X, Y }

  private record Calibration(EnumMap<Axis, List<ControlPoint>> pointsByAxis) {
    static Calibration read(Path source) throws IOException {
      EnumMap<Axis, List<ControlPoint>> controls = new EnumMap<>(Axis.class);
      controls.put(Axis.X, new ArrayList<>());
      controls.put(Axis.Y, new ArrayList<>());
      for (String line : Files.readAllLines(source)) {
        if (ignored(line, "axis\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        controls.get(Axis.valueOf(fields[0])).add(new ControlPoint(
            Double.parseDouble(fields[1]), Double.parseDouble(fields[2])));
      }
      for (List<ControlPoint> points : controls.values()) {
        points.sort(Comparator.comparingDouble(ControlPoint::mapCoordinate));
      }
      return new Calibration(controls);
    }

    double imageX(double longitude) {
      return interpolate(Axis.X, longitude);
    }

    double imageY(double latitude) {
      return interpolate(Axis.Y, latitude);
    }

    private double interpolate(Axis axis, double coordinate) {
      List<ControlPoint> points = pointsByAxis.get(axis);
      if (points.size() < 2) {
        throw new IllegalStateException("Reference calibration needs two " + axis + " controls");
      }
      for (int index = 1; index < points.size(); index++) {
        if (coordinate <= points.get(index).mapCoordinate()) {
          return interpolate(points.get(index - 1), points.get(index), coordinate);
        }
      }
      return interpolate(points.get(points.size() - 2), points.getLast(), coordinate);
    }

    private static double interpolate(ControlPoint first, ControlPoint second, double coordinate) {
      double fraction = (coordinate - first.mapCoordinate())
          / (second.mapCoordinate() - first.mapCoordinate());
      return first.imagePixel() + (second.imagePixel() - first.imagePixel()) * fraction;
    }
  }

  private record ControlPoint(double imagePixel, double mapCoordinate) {}
}
