package dn.strategicmap.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiles located reference evidence plus explicit absent-title fallbacks into review candidates. */
public final class GrandTheatreCandidateLayoutBuilder {
  private GrandTheatreCandidateLayoutBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 5) {
      throw new IllegalArgumentException(
          "Expected <calibration.tsv> <catalogue.tsv> <ocr-report.tsv> <fallbacks.tsv> <output.tsv>");
    }
    build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
  }

  static void build(
      Path calibrationPath,
      Path cataloguePath,
      Path reportPath,
      Path fallbackPath,
      Path outputPath) throws IOException {
    Calibration calibration = Calibration.read(calibrationPath);
    LinkedHashMap<String, CatalogueLabel> catalogue = readCatalogue(cataloguePath);
    Map<String, List<FallbackLine>> fallbacks = readFallbacks(fallbackPath);
    List<PreparedLine> lines = new ArrayList<>();
    Set<String> covered = new HashSet<>();

    List<String> reportLines = Files.readAllLines(reportPath);
    Map<String, Integer> reportColumns = columns(reportLines.getFirst());
    for (int rowIndex = 1; rowIndex < reportLines.size(); rowIndex++) {
      String[] fields = reportLines.get(rowIndex).split("\\t", -1);
      String labelKey = value(fields, reportColumns, "labelKey");
      if (fallbacks.containsKey(labelKey)) {
        continue;
      }
      String status = value(fields, reportColumns, "status");
      String imagePath = value(fields, reportColumns, "imagePath");
      if (status.equals("REVIEW_REQUIRED") || status.equals("MISS") || imagePath.isBlank()) {
        continue;
      }
      CatalogueLabel label = catalogue.get(labelKey);
      if (label == null) {
        throw new IllegalStateException("OCR report label is absent from catalogue: " + labelKey);
      }
      List<ImagePoint> imagePoints = imagePath(imagePath);
      List<MapPoint> path = new ArrayList<>(imagePoints.size());
      double previousX = Double.NaN;
      for (ImagePoint point : imagePoints) {
        double mapX = canonicalX(calibration.mapX(point.x()), previousX);
        path.add(new MapPoint(mapX, calibration.mapY(point.y())));
        previousX = mapX;
      }
      ImagePoint centre = imagePoints.get(imagePoints.size() / 2);
      double capHeight = Double.parseDouble(value(fields, reportColumns, "capHeightPixels"))
          * calibration.mapUnitsPerPixelY(centre.y());
      double tracking = Double.parseDouble(value(fields, reportColumns, "trackingPixels"))
          * calibration.mapUnitsPerPixelX(centre.x());
      lines.add(new PreparedLine(
          labelKey, face(label), value(fields, reportColumns, "renderText"), capHeight,
          tracking, pathText(path)));
      covered.add(labelKey);
    }

    for (CatalogueLabel label : catalogue.values()) {
      if (covered.contains(label.labelKey())) {
        continue;
      }
      List<FallbackLine> labelFallbacks = fallbacks.get(label.labelKey());
      if (labelFallbacks == null) {
        throw new IllegalStateException("No located evidence or fallback for " + label.labelKey());
      }
      for (FallbackLine fallback : labelFallbacks) {
        lines.add(new PreparedLine(
            label.labelKey(), fallback.fontFace(), fallback.text(), fallback.capHeightMapUnits(),
            fallback.trackingMapUnits(), fallback.pathMapCoordinates()));
      }
      covered.add(label.labelKey());
    }
    if (covered.size() != catalogue.size()) {
      throw new IllegalStateException(
          "Candidate coverage mismatch: " + covered.size() + "/" + catalogue.size());
    }

    Files.createDirectories(outputPath.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      writer.write("labelKey\tfontFace\ttext\tcapHeightMapUnits\ttrackingMapUnits"
          + "\tpathMapCoordinates\n");
      for (PreparedLine line : lines) {
        writer.write(line.labelKey() + "\t" + line.fontFace() + "\t" + line.text() + "\t"
            + line.capHeightMapUnits() + "\t" + line.trackingMapUnits() + "\t"
            + line.pathMapCoordinates() + "\n");
      }
    }
    System.out.printf("Prepared %s: labels=%d lines=%d reference=%d fallback=%d%n",
        outputPath, covered.size(), lines.size(), catalogue.size() - fallbacks.size(), fallbacks.size());
  }

  private static String face(CatalogueLabel label) {
    return "SERIF";
  }

  private static LinkedHashMap<String, CatalogueLabel> readCatalogue(Path source)
      throws IOException {
    List<String> lines = Files.readAllLines(source);
    Map<String, Integer> columns = columns(lines.getFirst());
    LinkedHashMap<String, CatalogueLabel> labels = new LinkedHashMap<>();
    for (int index = 1; index < lines.size(); index++) {
      String[] fields = lines.get(index).split("\\t", -1);
      CatalogueLabel label = new CatalogueLabel(
          value(fields, columns, "labelKey"), value(fields, columns, "category"),
          value(fields, columns, "minimumBand"));
      labels.put(label.labelKey(), label);
    }
    return labels;
  }

  private static Map<String, List<FallbackLine>> readFallbacks(Path source) throws IOException {
    List<String> lines = Files.readAllLines(source).stream()
        .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    Map<String, Integer> columns = columns(lines.getFirst());
    Map<String, List<FallbackLine>> fallbacks = new HashMap<>();
    for (int index = 1; index < lines.size(); index++) {
      String[] fields = lines.get(index).split("\\t", -1);
      FallbackLine fallback = new FallbackLine(
          value(fields, columns, "fontFace"), value(fields, columns, "text"),
          Double.parseDouble(value(fields, columns, "capHeightMapUnits")),
          Double.parseDouble(value(fields, columns, "trackingMapUnits")),
          value(fields, columns, "pathMapCoordinates"));
      fallbacks.computeIfAbsent(value(fields, columns, "labelKey"), ignored -> new ArrayList<>())
          .add(fallback);
    }
    return fallbacks;
  }

  private static Map<String, Integer> columns(String header) {
    String[] names = header.split("\\t", -1);
    Map<String, Integer> columns = new HashMap<>();
    for (int index = 0; index < names.length; index++) {
      columns.put(names[index], index);
    }
    return columns;
  }

  private static String value(String[] fields, Map<String, Integer> columns, String name) {
    Integer index = columns.get(name);
    if (index == null) {
      throw new IllegalStateException("Missing column: " + name);
    }
    return fields[index];
  }

  private static List<ImagePoint> imagePath(String value) {
    List<ImagePoint> points = new ArrayList<>();
    for (String coordinate : value.split(";")) {
      String[] fields = coordinate.split(",", -1);
      points.add(new ImagePoint(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])));
    }
    return points;
  }

  private static double canonicalX(double x, double previousX) {
    double canonical = x > 180.0 ? x - 360.0 : x;
    if (Double.isNaN(previousX)) {
      return canonical;
    }
    while (canonical - previousX > 180.0) {
      canonical -= 360.0;
    }
    while (canonical - previousX < -180.0) {
      canonical += 360.0;
    }
    return canonical;
  }

  private static String pathText(List<MapPoint> path) {
    StringBuilder result = new StringBuilder();
    for (MapPoint point : path) {
      if (!result.isEmpty()) {
        result.append(';');
      }
      result.append(point.x()).append(',').append(point.y());
    }
    return result.toString();
  }

  private enum Axis { X, Y }

  private record Calibration(EnumMap<Axis, List<ControlPoint>> controls) {
    static Calibration read(Path source) throws IOException {
      EnumMap<Axis, List<ControlPoint>> controls = new EnumMap<>(Axis.class);
      controls.put(Axis.X, new ArrayList<>());
      controls.put(Axis.Y, new ArrayList<>());
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("axis\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        controls.get(Axis.valueOf(fields[0])).add(new ControlPoint(
            Double.parseDouble(fields[1]), Double.parseDouble(fields[2])));
      }
      for (List<ControlPoint> points : controls.values()) {
        points.sort(Comparator.comparingDouble(ControlPoint::imagePixel));
      }
      return new Calibration(controls);
    }

    double mapX(double pixel) { return segment(Axis.X, pixel).mapCoordinate(pixel); }
    double mapY(double pixel) { return segment(Axis.Y, pixel).mapCoordinate(pixel); }
    double mapUnitsPerPixelX(double pixel) { return segment(Axis.X, pixel).unitsPerPixel(); }
    double mapUnitsPerPixelY(double pixel) { return Math.abs(segment(Axis.Y, pixel).unitsPerPixel()); }

    private Segment segment(Axis axis, double pixel) {
      List<ControlPoint> points = controls.get(axis);
      for (int index = 1; index < points.size(); index++) {
        if (pixel <= points.get(index).imagePixel()) {
          return new Segment(points.get(index - 1), points.get(index));
        }
      }
      return new Segment(points.get(points.size() - 2), points.getLast());
    }
  }

  private record ControlPoint(double imagePixel, double mapCoordinate) {}

  private record Segment(ControlPoint first, ControlPoint second) {
    double unitsPerPixel() {
      return (second.mapCoordinate() - first.mapCoordinate())
          / (second.imagePixel() - first.imagePixel());
    }

    double mapCoordinate(double pixel) {
      return first.mapCoordinate() + (pixel - first.imagePixel()) * unitsPerPixel();
    }
  }

  private record CatalogueLabel(String labelKey, String category, String minimumBand) {}
  private record ImagePoint(double x, double y) {}
  private record MapPoint(double x, double y) {}
  private record FallbackLine(
      String fontFace, String text, double capHeightMapUnits, double trackingMapUnits,
      String pathMapCoordinates) {}
  private record PreparedLine(
      String labelKey, String fontFace, String text, double capHeightMapUnits,
      double trackingMapUnits, String pathMapCoordinates) {}
}
