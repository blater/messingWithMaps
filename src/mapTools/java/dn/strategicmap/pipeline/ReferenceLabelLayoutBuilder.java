package dn.strategicmap.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * Converts reviewed paths on a reference-map image into fixed canonical map-space label paths.
 *
 * <p>This is deliberately a direct compiler: it neither searches for a placement nor changes an
 * observed baseline. A polyline with two points represents a straight or angled label; additional
 * points preserve an observed subtle curve.</p>
 */
public final class ReferenceLabelLayoutBuilder {
  private ReferenceLabelLayoutBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected <reference-calibration.tsv> <reference-observations.tsv> <output.tsv>");
    }
    Calibration calibration = Calibration.read(Path.of(args[0]));
    List<Observation> observations = Observation.read(Path.of(args[1]));
    Path output = Path.of(args[2]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write("labelKey\ttypographyRole\tcapHeightMapUnits\ttrackingMapUnits\tpathMapCoordinates\n");
      for (Observation observation : observations) {
        List<MapPoint> path = new ArrayList<>(observation.imagePath().size());
        for (ImagePoint point : observation.imagePath()) {
          path.add(new MapPoint(calibration.mapX(point.x()), calibration.mapY(point.y())));
        }
        ImagePoint centre = observation.imagePath().get(observation.imagePath().size() / 2);
        writer.write(observation.labelKey());
        writer.write('\t');
        writer.write(observation.typographyRole());
        writer.write('\t');
        writer.write(Double.toString(observation.capHeightPixels() * calibration.mapUnitsPerPixelY(centre.y())));
        writer.write('\t');
        writer.write(Double.toString(observation.trackingPixels() * calibration.mapUnitsPerPixelX(centre.x())));
        writer.write('\t');
        writer.write(pathText(path));
        writer.write('\n');
      }
    }
    System.out.printf("Prepared %s: labels=%d%n", output, observations.size());
  }

  private static String pathText(List<MapPoint> path) {
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < path.size(); index++) {
      if (index > 0) {
        text.append(';');
      }
      MapPoint point = path.get(index);
      text.append(point.x()).append(',').append(point.y());
    }
    return text.toString();
  }

  private enum Axis { X, Y }

  private record ImagePoint(double x, double y) {}

  private record MapPoint(double x, double y) {}

  private record Calibration(EnumMap<Axis, List<ControlPoint>> pointsByAxis) {
    static Calibration read(Path source) throws IOException {
      EnumMap<Axis, List<ControlPoint>> points = new EnumMap<>(Axis.class);
      points.put(Axis.X, new ArrayList<>());
      points.put(Axis.Y, new ArrayList<>());
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("axis\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        Axis axis = Axis.valueOf(fields[0]);
        points.get(axis).add(new ControlPoint(
            Double.parseDouble(fields[1]), Double.parseDouble(fields[2])));
      }
      for (List<ControlPoint> axisPoints : points.values()) {
        axisPoints.sort(Comparator.comparingDouble(ControlPoint::imagePixel));
      }
      return new Calibration(points);
    }

    double mapX(double imageX) {
      return interpolate(Axis.X, imageX);
    }

    double mapY(double imageY) {
      return interpolate(Axis.Y, imageY);
    }

    double mapUnitsPerPixelX(double imageX) {
      return derivative(Axis.X, imageX);
    }

    double mapUnitsPerPixelY(double imageY) {
      return Math.abs(derivative(Axis.Y, imageY));
    }

    private double interpolate(Axis axis, double imagePixel) {
      ControlSegment segment = segment(axis, imagePixel);
      return segment.first().mapCoordinate()
          + (imagePixel - segment.first().imagePixel()) * segment.mapUnitsPerPixel();
    }

    private double derivative(Axis axis, double imagePixel) {
      return segment(axis, imagePixel).mapUnitsPerPixel();
    }

    private ControlSegment segment(Axis axis, double imagePixel) {
      List<ControlPoint> points = pointsByAxis.get(axis);
      if (points.size() < 2) {
        throw new IllegalStateException("Reference calibration needs at least two " + axis + " points");
      }
      for (int index = 1; index < points.size(); index++) {
        if (imagePixel <= points.get(index).imagePixel()) {
          return new ControlSegment(points.get(index - 1), points.get(index));
        }
      }
      return new ControlSegment(points.get(points.size() - 2), points.getLast());
    }
  }

  private record ControlPoint(double imagePixel, double mapCoordinate) {}

  private record ControlSegment(ControlPoint first, ControlPoint second) {
    double mapUnitsPerPixel() {
      return (second.mapCoordinate() - first.mapCoordinate())
          / (second.imagePixel() - first.imagePixel());
    }
  }

  private record Observation(
      String labelKey,
      String typographyRole,
      double capHeightPixels,
      double trackingPixels,
      List<ImagePoint> imagePath) {
    static List<Observation> read(Path source) throws IOException {
      List<Observation> observations = new ArrayList<>();
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        observations.add(new Observation(
            fields[0],
            fields[1],
            Double.parseDouble(fields[2]),
            Double.parseDouble(fields[3]),
            imagePath(fields[4])));
      }
      return observations;
    }

    private static List<ImagePoint> imagePath(String value) {
      List<ImagePoint> points = new ArrayList<>();
      for (String coordinate : value.split(";")) {
        String[] fields = coordinate.split(",", -1);
        points.add(new ImagePoint(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])));
      }
      return points;
    }
  }
}
