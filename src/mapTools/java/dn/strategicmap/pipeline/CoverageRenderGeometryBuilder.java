package dn.strategicmap.pipeline;

import com.badlogic.gdx.utils.FloatArray;
import dn.strategicmap.geometry.MapBounds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.coverage.CoverageSimplifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator;

/** Builds the two render levels without exposing JTS or tiling to runtime code. */
final class CoverageRenderGeometryBuilder {
  static final double COARSE_MAX_ERROR_MAP_UNITS = 0.05;
  static final int FINE_COLUMNS = 8;
  static final int FINE_ROWS = 4;

  private final GeometryFactory geometryFactory;
  private final MapBounds worldBounds;

  CoverageRenderGeometryBuilder(GeometryFactory geometryFactory, MapBounds worldBounds) {
    this.geometryFactory = geometryFactory;
    this.worldBounds = worldBounds;
  }

  /**
   * TPVW uses indexed topology checks and is potentially super-linear in source vertices. It is
   * confined to this offline pass over the pinned roughly 836k-point coverage; the complete
   * simplification, clipping, triangulation, and write currently finishes in about six seconds.
   */
  List<PreparedLevel> build(List<RegionGeometry> regions) {
    Geometry[] coverage = new Geometry[regions.size()];
    for (int index = 0; index < regions.size(); index++) {
      coverage[index] = regions.get(index).geometry();
    }

    CoverageSimplifier simplifier = new CoverageSimplifier(coverage);
    simplifier.setRemovableRingSizeFactor(0.0);
    Geometry[] simplified = simplifier.simplify(COARSE_MAX_ERROR_MAP_UNITS);
    return List.of(
        buildGlobalLevel(regions, simplified),
        buildTiledFineLevel(regions));
  }

  /** Visits each simplified coverage element and its emitted triangles once. */
  private PreparedLevel buildGlobalLevel(
      List<RegionGeometry> regions, Geometry[] simplified) {
    MutableChunk chunk = new MutableChunk(worldBounds);
    for (int index = 0; index < regions.size(); index++) {
      RegionGeometry region = regions.get(index);
      chunk.addFill(region.regionIndex(), simplified[index]);
      chunk.addBoundary(simplified[index].getBoundary());
    }
    return new PreparedLevel(
        COARSE_MAX_ERROR_MAP_UNITS, 1, 1, List.of(chunk.finish()));
  }

  /**
   * Each region visits only the regular-grid cells overlapped by its bounds. JTS intersection may
   * be super-linear within one region/tile pair, but the fixed 8x4 grid bounds each operation and
   * the complete pinned dataset is measured by the asset task. Runtime never clips geometry.
   */
  private PreparedLevel buildTiledFineLevel(List<RegionGeometry> regions) {
    double tileWidth = worldBounds.width() / FINE_COLUMNS;
    double tileHeight = worldBounds.height() / FINE_ROWS;
    MutableChunk[] chunks = new MutableChunk[FINE_COLUMNS * FINE_ROWS];
    Polygon[] tilePolygons = new Polygon[chunks.length];
    for (int row = 0; row < FINE_ROWS; row++) {
      for (int column = 0; column < FINE_COLUMNS; column++) {
        int index = row * FINE_COLUMNS + column;
        MapBounds bounds = tileBounds(column, row, tileWidth, tileHeight);
        chunks[index] = new MutableChunk(bounds);
        tilePolygons[index] = (Polygon) geometryFactory.toGeometry(new Envelope(
            bounds.west(), bounds.east(), bounds.south(), bounds.north()));
      }
    }

    for (RegionGeometry region : regions) {
      Geometry geometry = region.geometry();
      Geometry boundary = geometry.getBoundary();
      Envelope regionBounds = geometry.getEnvelopeInternal();
      int firstColumn = columnFor(regionBounds.getMinX(), tileWidth);
      int lastColumn = columnFor(Math.nextDown(regionBounds.getMaxX()), tileWidth);
      int firstRow = rowFor(regionBounds.getMinY(), tileHeight);
      int lastRow = rowFor(Math.nextDown(regionBounds.getMaxY()), tileHeight);
      for (int row = firstRow; row <= lastRow; row++) {
        for (int column = firstColumn; column <= lastColumn; column++) {
          int index = row * FINE_COLUMNS + column;
          Polygon tile = tilePolygons[index];
          Geometry clipped;
          Geometry clippedBoundary;
          if (tile.getEnvelopeInternal().contains(regionBounds)) {
            clipped = geometry;
            clippedBoundary = boundary;
          } else {
            clipped = geometry.intersection(tile);
            clippedBoundary = boundary.intersection(tile);
          }
          chunks[index].addFill(region.regionIndex(), clipped);
          chunks[index].addBoundary(clippedBoundary);
        }
      }
    }

    List<PreparedChunk> prepared = new ArrayList<>(chunks.length);
    for (MutableChunk chunk : chunks) {
      prepared.add(chunk.finish());
    }
    return new PreparedLevel(0.0, FINE_COLUMNS, FINE_ROWS, prepared);
  }

  private MapBounds tileBounds(int column, int row, double tileWidth, double tileHeight) {
    double west = worldBounds.west() + column * tileWidth;
    double south = worldBounds.south() + row * tileHeight;
    double east = west + tileWidth;
    double north = south + tileHeight;
    return new MapBounds(north, south, east, west);
  }

  private int columnFor(double mapX, double tileWidth) {
    return clamp(
        (int) Math.floor((mapX - worldBounds.west()) / tileWidth), 0, FINE_COLUMNS - 1);
  }

  private int rowFor(double mapY, double tileHeight) {
    return clamp(
        (int) Math.floor((mapY - worldBounds.south()) / tileHeight), 0, FINE_ROWS - 1);
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  record RegionGeometry(int regionIndex, Geometry geometry) {}

  record PreparedLevel(
      double maxErrorMapUnits, int columns, int rows, List<PreparedChunk> chunks) {}

  record PreparedChunk(MapBounds bounds, List<PreparedFill> fills, float[] boundaryLines) {
    int fillVertexCount() {
      int count = 0;
      for (PreparedFill fill : fills) {
        count += fill.triangles().length / 2;
      }
      return count;
    }
  }

  record PreparedFill(int regionIndex, float[] triangles) {}

  private static final class MutableChunk {
    private final MapBounds bounds;
    private final List<PreparedFill> fills = new ArrayList<>();
    private final FloatArray boundaryLines = new FloatArray();
    private final Set<SegmentKey> boundarySegments = new HashSet<>();

    private MutableChunk(MapBounds bounds) {
      this.bounds = bounds;
    }

    private void addFill(int regionIndex, Geometry geometry) {
      FloatArray triangles = new FloatArray();
      appendTriangles(geometry, triangles);
      if (triangles.size > 0) {
        fills.add(new PreparedFill(regionIndex, triangles.toArray()));
      }
    }

    private void addBoundary(Geometry geometry) {
      appendBoundaryLines(geometry);
    }

    private void appendBoundaryLines(Geometry geometry) {
      if (geometry instanceof LineString line) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int index = 1; index < coordinates.length; index++) {
          addBoundarySegment(coordinates[index - 1], coordinates[index]);
        }
        return;
      }
      for (int index = 0; index < geometry.getNumGeometries(); index++) {
        Geometry child = geometry.getGeometryN(index);
        if (child != geometry) {
          appendBoundaryLines(child);
        }
      }
    }

    private void addBoundarySegment(Coordinate first, Coordinate second) {
      float firstX = (float) first.x;
      float firstY = (float) first.y;
      float secondX = (float) second.x;
      float secondY = (float) second.y;
      long firstKey = pointKey(firstX, firstY);
      long secondKey = pointKey(secondX, secondY);
      SegmentKey segment = Long.compareUnsigned(firstKey, secondKey) <= 0
          ? new SegmentKey(firstKey, secondKey)
          : new SegmentKey(secondKey, firstKey);
      if (!boundarySegments.add(segment)) {
        return;
      }
      boundaryLines.add(firstX);
      boundaryLines.add(firstY);
      boundaryLines.add(secondX);
      boundaryLines.add(secondY);
    }

    private PreparedChunk finish() {
      return new PreparedChunk(bounds, fills, boundaryLines.toArray());
    }

    private static void appendTriangles(Geometry geometry, FloatArray target) {
      if (geometry instanceof Polygon polygon) {
        if (polygon.getArea() <= 1.0e-12) {
          return;
        }
        Geometry triangles = PolygonTriangulator.triangulate(polygon);
        for (int index = 0; index < triangles.getNumGeometries(); index++) {
          Coordinate[] triangle = triangles.getGeometryN(index).getCoordinates();
          for (int vertex = 0; vertex < 3; vertex++) {
            target.add((float) triangle[vertex].x);
            target.add((float) triangle[vertex].y);
          }
        }
        return;
      }
      for (int index = 0; index < geometry.getNumGeometries(); index++) {
        Geometry child = geometry.getGeometryN(index);
        if (child != geometry) {
          appendTriangles(child, target);
        }
      }
    }

    private static long pointKey(float x, float y) {
      return ((long) Float.floatToIntBits(x) << 32)
          | (Float.floatToIntBits(y) & 0xffff_ffffL);
    }
  }

  private record SegmentKey(long firstPoint, long secondPoint) {}
}
