package dn.strategicmap.interaction;

import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.PreparedMapGeometry;
import java.util.ArrayList;
import java.util.List;

/** Bounds-grid candidate lookup followed by exact canonical polygon tests. */
final class MapSpatialIndex {
  private static final int COLUMNS = 72;
  private static final int ROWS = 36;
  private static final double BOUNDARY_TOLERANCE = 1.0e-7;

  private final PreparedMapGeometry geometry;
  private final MapBounds worldBounds;
  private final double cellWidth;
  private final double cellHeight;
  private final int[][] partIndexesByCell;
  private int lastCandidateCount;

  MapSpatialIndex(PreparedMapGeometry geometry, MapBounds worldBounds) {
    this.geometry = geometry;
    this.worldBounds = worldBounds;
    cellWidth = worldBounds.width() / COLUMNS;
    cellHeight = worldBounds.height() / ROWS;

    List<List<Integer>> cells = new ArrayList<>(COLUMNS * ROWS);
    for (int index = 0; index < COLUMNS * ROWS; index++) {
      cells.add(new ArrayList<>());
    }

    // Load-time only. Each part visits the fixed 5-degree cells overlapped by its bounds.
    for (int partIndex = 0; partIndex < geometry.partCount(); partIndex++) {
      MapBounds bounds = geometry.part(partIndex).bounds();
      int firstColumn = column(bounds.west());
      int lastColumn = column(Math.nextDown(bounds.east()));
      int firstRow = row(bounds.south());
      int lastRow = row(Math.nextDown(bounds.north()));
      for (int row = firstRow; row <= lastRow; row++) {
        for (int column = firstColumn; column <= lastColumn; column++) {
          cells.get(row * COLUMNS + column).add(partIndex);
        }
      }
    }

    partIndexesByCell = new int[cells.size()][];
    for (int index = 0; index < cells.size(); index++) {
      List<Integer> cell = cells.get(index);
      int[] partIndexes = new int[cell.size()];
      for (int partIndex = 0; partIndex < cell.size(); partIndex++) {
        partIndexes[partIndex] = cell.get(partIndex);
      }
      partIndexesByCell[index] = partIndexes;
    }
  }

  /** O(candidates * candidate-ring vertices), with no query allocation. */
  int regionAt(double canonicalMapX, double mapY) {
    if (mapY < worldBounds.south() || mapY > worldBounds.north()) {
      lastCandidateCount = 0;
      return -1;
    }
    int[] candidates = partIndexesByCell[row(mapY) * COLUMNS + column(canonicalMapX)];
    lastCandidateCount = candidates.length;
    int matchedRegionIndex = -1;
    for (int partIndex : candidates) {
      PreparedMapGeometry.Part part = geometry.part(partIndex);
      MapBounds bounds = part.bounds();
      if (canonicalMapX < bounds.west()
          || canonicalMapX > bounds.east()
          || mapY < bounds.south()
          || mapY > bounds.north()
          || !contains(part, canonicalMapX, mapY)) {
        continue;
      }
      if (matchedRegionIndex < 0 || part.regionIndex() < matchedRegionIndex) {
        matchedRegionIndex = part.regionIndex();
      }
    }
    return matchedRegionIndex;
  }

  int lastCandidateCount() {
    return lastCandidateCount;
  }

  private static boolean contains(
      PreparedMapGeometry.Part part, double mapX, double mapY) {
    if (ringLocation(part, 0, mapX, mapY) == 0) {
      return false;
    }
    for (int ringIndex = 1; ringIndex < part.ringCount(); ringIndex++) {
      if (ringLocation(part, ringIndex, mapX, mapY) == 1) {
        return false;
      }
    }
    return true;
  }

  /** Returns 0 outside, 1 inside, and 2 on the boundary. */
  private static int ringLocation(
      PreparedMapGeometry.Part part, int ringIndex, double mapX, double mapY) {
    boolean inside = false;
    int segmentCount = part.ringPointCount(ringIndex) - 1;
    for (int index = 0; index < segmentCount; index++) {
      double firstX = part.ringMapX(ringIndex, index);
      double firstY = part.ringMapY(ringIndex, index);
      double secondX = part.ringMapX(ringIndex, index + 1);
      double secondY = part.ringMapY(ringIndex, index + 1);
      if (onSegment(firstX, firstY, secondX, secondY, mapX, mapY)) {
        return 2;
      }
      if ((firstY > mapY) != (secondY > mapY)
          && mapX < (secondX - firstX) * (mapY - firstY) / (secondY - firstY) + firstX) {
        inside = !inside;
      }
    }
    return inside ? 1 : 0;
  }

  private static boolean onSegment(
      double firstX,
      double firstY,
      double secondX,
      double secondY,
      double mapX,
      double mapY) {
    double cross = (mapX - firstX) * (secondY - firstY)
        - (mapY - firstY) * (secondX - firstX);
    double scale = Math.abs(secondX - firstX) + Math.abs(secondY - firstY) + 1.0;
    return Math.abs(cross) <= BOUNDARY_TOLERANCE * scale
        && mapX >= Math.min(firstX, secondX) - BOUNDARY_TOLERANCE
        && mapX <= Math.max(firstX, secondX) + BOUNDARY_TOLERANCE
        && mapY >= Math.min(firstY, secondY) - BOUNDARY_TOLERANCE
        && mapY <= Math.max(firstY, secondY) + BOUNDARY_TOLERANCE;
  }

  private int column(double mapX) {
    return gridIndex(mapX, worldBounds.west(), cellWidth, COLUMNS);
  }

  private int row(double mapY) {
    return gridIndex(mapY, worldBounds.south(), cellHeight, ROWS);
  }

  private static int gridIndex(double value, double minimum, double size, int count) {
    return Math.max(0, Math.min(count - 1, (int) Math.floor((value - minimum) / size)));
  }
}
