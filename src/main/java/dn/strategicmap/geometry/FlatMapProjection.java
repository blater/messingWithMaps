package dn.strategicmap.geometry;

/** The initial scaled equirectangular projection: longitude is X and latitude is Y. */
public final class FlatMapProjection {
  private static final double HORIZONTAL_SCALE = 1.00;
  private static final double VERTICAL_SCALE = 1.00;
  public static final MapBounds WORLD_BOUNDS =
      new MapBounds(
          90.0 * VERTICAL_SCALE,
          -90.0 * VERTICAL_SCALE,
          180.0 * HORIZONTAL_SCALE,
          -180.0 * HORIZONTAL_SCALE);

  public MapBounds worldBounds() {
    return WORLD_BOUNDS;
  }

  public Point project(double latitudeDegrees, double longitudeDegrees) {
    return new Point(
        longitudeDegrees * HORIZONTAL_SCALE,
        latitudeDegrees * VERTICAL_SCALE);
  }

  public double canonicalMapX(double mapX) {
    double width = WORLD_BOUNDS.width();
    double wrapped = (mapX - WORLD_BOUNDS.west()) % width;
    if (wrapped < 0.0) {
      wrapped += width;
    }
    return WORLD_BOUNDS.west() + wrapped;
  }

  public double shortestWrappedDeltaX(double firstMapX, double secondMapX) {
    double width = WORLD_BOUNDS.width();
    double delta = (firstMapX - secondMapX) % width;
    if (delta >= width * 0.5) {
      delta -= width;
    } else if (delta < -width * 0.5) {
      delta += width;
    }
    return delta;
  }
}
