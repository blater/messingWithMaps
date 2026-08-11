package dn.strategicmap.pipeline;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.Point;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;

/** Converts the reviewed neutral region source into the deterministic runtime map asset. */
public final class StrategicMapAssetBuilder {
  private static final int MAGIC = 0x44524d50;
  private static final int VERSION = 2;
  private static final String SOURCE_DATASET_ID = "natural-earth-5.1.1";
  private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
  private static final FlatMapProjection PROJECTION = new FlatMapProjection();

  private StrategicMapAssetBuilder() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected <region-source.geojson> <output.map>");
    }
    build(Path.of(args[0]), Path.of(args[1]));
  }

  private static void build(Path sourcePath, Path outputPath) throws IOException {
    long startedNanos = System.nanoTime();
    byte[] sourceBytes = Files.readAllBytes(sourcePath);
    JsonValue root;
    try {
      root = new JsonReader().parse(new String(sourceBytes, StandardCharsets.UTF_8));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Failed parsing " + sourcePath, failure);
    }
    SourceData source = readSource(root.get("features"));

    List<CoverageRenderGeometryBuilder.RegionGeometry> regionGeometry =
        new ArrayList<>(source.features().size());
    for (SourceFeature feature : source.features()) {
      regionGeometry.add(new CoverageRenderGeometryBuilder.RegionGeometry(
          feature.regionIndex(), feature.geometry()));
    }
    List<CoverageRenderGeometryBuilder.PreparedLevel> renderLevels;
    try {
      renderLevels = new CoverageRenderGeometryBuilder(
          GEOMETRY_FACTORY, FlatMapProjection.WORLD_BOUNDS).build(regionGeometry);
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Failed preparing render coverage from " + sourcePath, failure);
    }

    Files.createDirectories(outputPath.toAbsolutePath().getParent());
    String sourceDigest = sha256(sourceBytes);
    writeAsset(outputPath, sourceDigest, source, renderLevels);
    report(outputPath, sourceDigest, source, renderLevels, startedNanos);
  }

  /**
   * Reads every source feature once. Grouped regions are dissolved offline; the largest group has
   * only a few dozen source provinces, so the union cost is small and never reaches runtime.
   */
  private static SourceData readSource(JsonValue featureValues) {
    Map<String, Integer> regionIndexById = new LinkedHashMap<>();
    List<RegionHeader> regions = new ArrayList<>();
    List<List<Geometry>> geometryByRegion = new ArrayList<>();
    int featureIndex = 0;
    for (JsonValue feature = featureValues.child; feature != null; feature = feature.next) {
      JsonValue properties = feature.get("properties");
      String regionId = properties.getString("regionId");
      String displayName = properties.getString("displayName");
      Integer regionIndex = regionIndexById.get(regionId);
      if (regionIndex == null) {
        regionIndex = regions.size();
        regionIndexById.put(regionId, regionIndex);
        regions.add(new RegionHeader(regionId, displayName));
        geometryByRegion.add(new ArrayList<>());
      } else if (!regions.get(regionIndex).displayName().equals(displayName)) {
        throw new IllegalStateException("Region ID " + regionId + " has multiple display names");
      }

      try {
        geometryByRegion.get(regionIndex).add(prepareGeometry(feature.get("geometry")));
      } catch (RuntimeException failure) {
        throw new IllegalStateException(
            "Failed preparing region " + regionId + " at feature " + featureIndex, failure);
      }
      featureIndex++;
    }

    List<SourceFeature> features = new ArrayList<>(regions.size());
    for (int regionIndex = 0; regionIndex < regions.size(); regionIndex++) {
      List<Geometry> contributions = geometryByRegion.get(regionIndex);
      Geometry geometry = contributions.size() == 1
          ? contributions.getFirst()
          : UnaryUnionOp.union(contributions);
      features.add(new SourceFeature(regionIndex, geometry, sourceParts(geometry)));
    }
    return new SourceData(regions, features);
  }

  private static Geometry prepareGeometry(JsonValue geometryValue) {
    JsonValue coordinates = geometryValue.get("coordinates");
    List<Polygon> polygons = new ArrayList<>();
    switch (geometryValue.getString("type")) {
      case "Polygon" -> polygons.add(preparePolygon(coordinates));
      case "MultiPolygon" -> {
        for (JsonValue polygon = coordinates.child; polygon != null; polygon = polygon.next) {
          polygons.add(preparePolygon(polygon));
        }
      }
      default -> throw new IllegalStateException(
          "Unsupported geometry type " + geometryValue.getString("type"));
    }

    Polygon[] geometryParts = polygons.toArray(Polygon[]::new);
    Geometry geometry = geometryParts.length == 1
        ? geometryParts[0]
        : GEOMETRY_FACTORY.createMultiPolygon(geometryParts);
    return geometry;
  }

  private static Polygon preparePolygon(JsonValue polygonValue) {
    List<Coordinate[]> cleanedRings = new ArrayList<>(polygonValue.size);
    int ringIndex = 0;
    for (JsonValue ringValue = polygonValue.child; ringValue != null; ringValue = ringValue.next) {
      Coordinate[] ring = cleanRing(ringValue);
      if (ringIndex == 0 || absoluteArea(ring) > 1.0e-12) {
        cleanedRings.add(ring);
      }
      ringIndex++;
    }

    LinearRing exterior = GEOMETRY_FACTORY.createLinearRing(cleanedRings.getFirst());
    LinearRing[] holes = new LinearRing[Math.max(0, cleanedRings.size() - 1)];
    for (int index = 1; index < cleanedRings.size(); index++) {
      holes[index - 1] = GEOMETRY_FACTORY.createLinearRing(cleanedRings.get(index));
    }
    return GEOMETRY_FACTORY.createPolygon(exterior, holes);
  }

  /** Visits each dissolved polygon and ring once to produce the hit-test geometry. */
  private static List<SourcePart> sourceParts(Geometry geometry) {
    List<SourcePart> parts = new ArrayList<>(geometry.getNumGeometries());
    appendSourceParts(geometry, parts);
    return parts;
  }

  private static void appendSourceParts(Geometry geometry, List<SourcePart> parts) {
    if (geometry instanceof Polygon polygon) {
      float[][] rings = new float[polygon.getNumInteriorRing() + 1][];
      Coordinate[] exterior = polygon.getExteriorRing().getCoordinates();
      rings[0] = ringValues(exterior);
      for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
        rings[index + 1] = ringValues(polygon.getInteriorRingN(index).getCoordinates());
      }
      parts.add(new SourcePart(bounds(exterior), rings));
      return;
    }
    for (int index = 0; index < geometry.getNumGeometries(); index++) {
      Geometry child = geometry.getGeometryN(index);
      if (child != geometry) {
        appendSourceParts(child, parts);
      }
    }
  }

  private static float[] ringValues(Coordinate[] coordinates) {
    float[] values = new float[coordinates.length * 2];
    for (int point = 0; point < coordinates.length; point++) {
      values[point * 2] = (float) coordinates[point].x;
      values[point * 2 + 1] = (float) coordinates[point].y;
    }
    return values;
  }

  private static Coordinate[] cleanRing(JsonValue ringValue) {
    List<Coordinate> points = new ArrayList<>(ringValue.size);
    for (JsonValue pointValue = ringValue.child; pointValue != null; pointValue = pointValue.next) {
      Point projected = PROJECTION.project(pointValue.getDouble(1), pointValue.getDouble(0));
      Coordinate point = new Coordinate(projected.x(), projected.y());
      if (!points.isEmpty() && points.getLast().equals2D(point)) {
        continue;
      }
      if (points.size() >= 2
          && isCollinearBacktrack(points.get(points.size() - 2), points.getLast(), point)) {
        points.removeLast();
        if (points.getLast().equals2D(point)) {
          continue;
        }
      }
      points.add(point);
    }
    if (points.size() > 1 && points.getLast().equals2D(points.getFirst())) {
      points.removeLast();
    }
    points.add(new Coordinate(points.getFirst()));
    return points.toArray(Coordinate[]::new);
  }

  private static boolean isCollinearBacktrack(
      Coordinate first, Coordinate overshoot, Coordinate next) {
    double abX = overshoot.x - first.x;
    double abY = overshoot.y - first.y;
    double acX = next.x - first.x;
    double acY = next.y - first.y;
    double cross = abX * acY - abY * acX;
    double scale = Math.max(1.0, abX * abX + abY * abY);
    if (Math.abs(cross) > 1.0e-12 * scale) {
      return false;
    }
    return acX * (next.x - overshoot.x) + acY * (next.y - overshoot.y) <= 0.0;
  }

  private static double absoluteArea(Coordinate[] ring) {
    double twiceArea = 0.0;
    for (int index = 1; index < ring.length; index++) {
      twiceArea += ring[index - 1].x * ring[index].y - ring[index].x * ring[index - 1].y;
    }
    return Math.abs(twiceArea) * 0.5;
  }

  private static MapBounds bounds(Coordinate[] exterior) {
    double west = Double.POSITIVE_INFINITY;
    double south = Double.POSITIVE_INFINITY;
    double east = Double.NEGATIVE_INFINITY;
    double north = Double.NEGATIVE_INFINITY;
    for (Coordinate point : exterior) {
      west = Math.min(west, point.x);
      south = Math.min(south, point.y);
      east = Math.max(east, point.x);
      north = Math.max(north, point.y);
    }
    return new MapBounds(north, south, east, west);
  }

  private static void writeAsset(
      Path outputPath,
      String sourceDigest,
      SourceData source,
      List<CoverageRenderGeometryBuilder.PreparedLevel> renderLevels) throws IOException {
    int partCount = source.features().stream().mapToInt(feature -> feature.parts().size()).sum();
    try (DataOutputStream output = new DataOutputStream(
        new BufferedOutputStream(Files.newOutputStream(outputPath)))) {
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      output.writeUTF(sourceDigest);
      output.writeUTF(SOURCE_DATASET_ID);
      writeBounds(output, FlatMapProjection.WORLD_BOUNDS);
      output.writeInt(source.regions().size());
      output.writeInt(partCount);
      for (RegionHeader region : source.regions()) {
        output.writeUTF(region.regionId());
        output.writeUTF(region.displayName());
      }

      for (SourceFeature feature : source.features()) {
        for (SourcePart part : feature.parts()) {
          output.writeInt(feature.regionIndex());
          writeBounds(output, part.bounds());
          output.writeInt(part.rings().length);
          for (float[] ring : part.rings()) {
            output.writeInt(ring.length / 2);
            writeFloats(output, ring);
          }
        }
      }

      output.writeInt(renderLevels.size());
      for (CoverageRenderGeometryBuilder.PreparedLevel level : renderLevels) {
        output.writeDouble(level.maxErrorMapUnits());
        output.writeInt(level.columns());
        output.writeInt(level.rows());
        output.writeInt(level.chunks().size());
        for (CoverageRenderGeometryBuilder.PreparedChunk chunk : level.chunks()) {
          writeBounds(output, chunk.bounds());
          output.writeInt(chunk.fills().size());
          for (CoverageRenderGeometryBuilder.PreparedFill fill : chunk.fills()) {
            output.writeInt(fill.regionIndex());
            output.writeInt(fill.triangles().length / 2);
            writeFloats(output, fill.triangles());
          }
          output.writeInt(chunk.boundaryLines().length / 2);
          writeFloats(output, chunk.boundaryLines());
        }
      }
    }
  }

  private static void writeBounds(DataOutputStream output, MapBounds bounds) throws IOException {
    output.writeDouble(bounds.west());
    output.writeDouble(bounds.south());
    output.writeDouble(bounds.east());
    output.writeDouble(bounds.north());
  }

  private static void writeFloats(DataOutputStream output, float[] values) throws IOException {
    for (float value : values) {
      output.writeFloat(value);
    }
  }

  private static void report(
      Path outputPath,
      String sourceDigest,
      SourceData source,
      List<CoverageRenderGeometryBuilder.PreparedLevel> levels,
      long startedNanos) {
    int partCount = source.features().stream().mapToInt(feature -> feature.parts().size()).sum();
    StringBuilder levelReport = new StringBuilder();
    for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
      CoverageRenderGeometryBuilder.PreparedLevel level = levels.get(levelIndex);
      int fillVertices = 0;
      int boundaryVertices = 0;
      int maximumChunkVertices = 0;
      for (CoverageRenderGeometryBuilder.PreparedChunk chunk : level.chunks()) {
        fillVertices += chunk.fillVertexCount();
        boundaryVertices += chunk.boundaryLines().length / 2;
        maximumChunkVertices = Math.max(maximumChunkVertices, chunk.fillVertexCount());
      }
      if (levelIndex > 0) {
        levelReport.append(' ');
      }
      levelReport.append(String.format(
          "level%d=%dx%d/%d-triangles/%d-boundary-vertices/max-chunk-%d",
          levelIndex,
          level.columns(),
          level.rows(),
          fillVertices / 3,
          boundaryVertices,
          maximumChunkVertices));
    }
    double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    System.out.printf(
        "Generated %s: regions=%d parts=%d %s sourceSha256=%s time=%.3fs%n",
        outputPath,
        source.regions().size(),
        partCount,
        levelReport,
        sourceDigest,
        elapsedSeconds);
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record SourceData(List<RegionHeader> regions, List<SourceFeature> features) {}

  private record RegionHeader(String regionId, String displayName) {}

  private record SourceFeature(int regionIndex, Geometry geometry, List<SourcePart> parts) {}

  private record SourcePart(MapBounds bounds, float[][] rings) {}

}
