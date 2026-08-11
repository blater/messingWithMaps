package dn.strategicmap.interaction;

import dn.strategicmap.feature.PlaceFeature;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.Point;
import java.util.ArrayList;
import java.util.List;

/** Fixed-grid viewport and nearest-point queries over canonical place anchors. */
public final class VisiblePlaceQuery {
  private static final int COLUMNS = 72;
  private static final int ROWS = 36;

  private final List<PlaceFeature> places;
  private final MapBounds worldBounds;
  private final Point[] anchors;
  private final int[][] placeIndexesByCell;
  private final int[] visibleIndexes;
  private int visibleCount;

  public VisiblePlaceQuery(List<PlaceFeature> places, FlatMapProjection projection) {
    this.places = places;
    worldBounds = projection.worldBounds();
    anchors = new Point[places.size()];
    List<List<Integer>> cells = new ArrayList<>(COLUMNS * ROWS);
    for (int index = 0; index < COLUMNS * ROWS; index++) {
      cells.add(new ArrayList<>());
    }
    for (int index = 0; index < places.size(); index++) {
      PlaceFeature place = places.get(index);
      Point anchor = projection.project(place.latitudeDegrees(), place.longitudeDegrees());
      anchors[index] = anchor;
      cells.get(row(anchor.y()) * COLUMNS + column(anchor.x())).add(index);
    }
    placeIndexesByCell = new int[cells.size()][];
    for (int index = 0; index < cells.size(); index++) {
      List<Integer> cell = cells.get(index);
      int[] placeIndexes = new int[cell.size()];
      for (int placeIndex = 0; placeIndex < cell.size(); placeIndex++) {
        placeIndexes[placeIndex] = cell.get(placeIndex);
      }
      placeIndexesByCell[index] = placeIndexes;
    }
    visibleIndexes = new int[places.size()];
  }

  /** O(overlapped fixed grid cells plus places in them), with no query allocation. */
  public int queryVisible(double west, double south, double east, double north) {
    visibleCount = 0;
    if (west >= east || south >= north) {
      return 0;
    }
    int firstColumn = column(west);
    int lastColumn = column(Math.nextDown(east));
    int firstRow = row(south);
    int lastRow = row(Math.nextDown(north));
    for (int row = firstRow; row <= lastRow; row++) {
      for (int column = firstColumn; column <= lastColumn; column++) {
        int[] candidates = placeIndexesByCell[row * COLUMNS + column];
        for (int placeIndex : candidates) {
          Point anchor = anchors[placeIndex];
          if (anchor.x() >= west
              && anchor.x() <= east
              && anchor.y() >= south
              && anchor.y() <= north) {
            visibleIndexes[visibleCount++] = placeIndex;
          }
        }
      }
    }
    return visibleCount;
  }

  /** O(cells touched by the small hit radius plus their candidates), with no allocation. */
  public int nearest(double canonicalMapX, double mapY, double toleranceMapUnits) {
    double west = Math.max(worldBounds.west(), canonicalMapX - toleranceMapUnits);
    double east = Math.min(worldBounds.east(), canonicalMapX + toleranceMapUnits);
    double south = Math.max(worldBounds.south(), mapY - toleranceMapUnits);
    double north = Math.min(worldBounds.north(), mapY + toleranceMapUnits);
    int nearestIndex = -1;
    double nearestDistanceSquared = toleranceMapUnits * toleranceMapUnits;
    for (int row = row(south); row <= row(north); row++) {
      for (int column = column(west); column <= column(east); column++) {
        for (int placeIndex : placeIndexesByCell[row * COLUMNS + column]) {
          Point anchor = anchors[placeIndex];
          double deltaX = anchor.x() - canonicalMapX;
          double deltaY = anchor.y() - mapY;
          double distanceSquared = deltaX * deltaX + deltaY * deltaY;
          if (distanceSquared <= nearestDistanceSquared) {
            nearestDistanceSquared = distanceSquared;
            nearestIndex = placeIndex;
          }
        }
      }
    }
    return nearestIndex;
  }

  public int visibleIndex(int resultIndex) {
    return visibleIndexes[resultIndex];
  }

  public Point anchor(int placeIndex) {
    return anchors[placeIndex];
  }

  public PlaceFeature place(int placeIndex) {
    return places.get(placeIndex);
  }

  public int placeCount() {
    return places.size();
  }

  private int column(double mapX) {
    return gridIndex(mapX, worldBounds.west(), worldBounds.width() / COLUMNS, COLUMNS);
  }

  private int row(double mapY) {
    return gridIndex(mapY, worldBounds.south(), worldBounds.height() / ROWS, ROWS);
  }

  private static int gridIndex(double value, double minimum, double size, int count) {
    return Math.max(0, Math.min(count - 1, (int) Math.floor((value - minimum) / size)));
  }
}
