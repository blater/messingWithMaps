package dn.strategicmap.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FlatMapProjectionTest {
  private static final double TOLERANCE = 1.0e-9;
  private final FlatMapProjection projection = new FlatMapProjection();

  @Test
  void mapsLatitudeAndLongitudeToMapYAndX() {
    Point result = projection.project(35.6895, 139.6917);

    assertEquals(
        139.6917 * projection.worldBounds().width() / 360.0,
        result.x(),
        TOLERANCE);
    assertEquals(
        35.6895 * projection.worldBounds().height() / 180.0,
        result.y(),
        TOLERANCE);
  }

  @Test
  void canonicalisesHorizontalWorldCopies() {
    MapBounds world = projection.worldBounds();
    double oneDegree = world.width() / 360.0;

    assertEquals(
        world.west() + oneDegree,
        projection.canonicalMapX(world.east() + oneDegree),
        TOLERANCE);
    assertEquals(
        world.east() - oneDegree,
        projection.canonicalMapX(world.west() - oneDegree),
        TOLERANCE);
    assertEquals(
        -2.0 * oneDegree,
        projection.shortestWrappedDeltaX(
            world.east() - oneDegree, world.west() + oneDegree),
        TOLERANCE);
  }
}
