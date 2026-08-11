package dn.strategicmap.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.Point;
import org.junit.jupiter.api.Test;

class MapCameraTest {
  private static final double TOLERANCE = 1.0e-8;
  private final FlatMapProjection projection = new FlatMapProjection();

  @Test
  void resetFitsOneWorldWidthWithoutExposingAWrappedCopy() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    var world = projection.worldBounds();

    Point west = camera.mapToViewport(new Point(world.west(), 0.0), 0);
    Point east = camera.mapToViewport(new Point(world.east(), 0.0), 0);
    Point north = camera.mapToViewport(new Point(0.0, world.north()), 0);
    Point south = camera.mapToViewport(new Point(0.0, world.south()), 0);

    assertEquals(0.0, west.x(), TOLERANCE);
    assertEquals(1280.0, east.x(), TOLERANCE);
    assertEquals(world.width(), camera.visibleMapWidth(), TOLERANCE);
    assertTrue(north.y() >= 0.0);
    assertTrue(south.y() <= 720.0);
  }

  @Test
  void zoomKeepsThePointUnderTheCursor() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    Point cursor = new Point(1070.0, 175.0);
    Point before = camera.viewportToCanonicalMap(cursor);

    camera.zoomAt(cursor, -4.0);

    Point after = camera.viewportToCanonicalMap(cursor);
    assertEquals(0.0, projection.shortestWrappedDeltaX(before.x(), after.x()), TOLERANCE);
    assertEquals(before.y(), after.y(), TOLERANCE);
  }

  @Test
  void zoomStopsAtTheConfiguredLimits() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    Point centre = new Point(640.0, 360.0);

    camera.zoomAt(centre, -1_000.0);
    assertEquals(MapCamera.MAX_ZOOM, camera.state().zoom(), TOLERANCE);

    camera.zoomAt(centre, 1_000.0);
    assertEquals(MapCamera.MIN_ZOOM, camera.state().zoom(), TOLERANCE);
  }

  @Test
  void keyboardMovementWrapsHorizontallyAndClampsAtBothPoles() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    camera.zoomAt(new Point(640.0, 360.0), -8.0);

    camera.panFromKeyboard(1.0, 1.0, 100.0);
    assertTrue(camera.centerMapX() >= projection.worldBounds().west()
        && camera.centerMapX() < projection.worldBounds().east());
    assertEquals(
        MapCamera.DEFAULT_MARGIN_PIXELS,
        camera.viewportYForMapY(projection.worldBounds().north()),
        TOLERANCE);

    camera.panFromKeyboard(0.0, -1.0, 200.0);
    assertEquals(
        720.0 - MapCamera.DEFAULT_MARGIN_PIXELS,
        camera.viewportYForMapY(projection.worldBounds().south()),
        TOLERANCE);
  }

  @Test
  void timeBasedMovementIsIndependentOfFrameRate() {
    MapCamera sixtyFps = new MapCamera(projection, 1280, 720);
    MapCamera thirtyFps = new MapCamera(projection, 1280, 720);
    sixtyFps.zoomAt(new Point(640.0, 360.0), -5.0);
    thirtyFps.zoomAt(new Point(640.0, 360.0), -5.0);

    for (int frame = 0; frame < 60; frame++) {
      sixtyFps.panFromKeyboard(1.0, 0.4, 1.0 / 60.0);
    }
    for (int frame = 0; frame < 30; frame++) {
      thirtyFps.panFromKeyboard(1.0, 0.4, 1.0 / 30.0);
    }

    assertEquals(
        0.0,
        projection.shortestWrappedDeltaX(sixtyFps.centerMapX(), thirtyFps.centerMapX()),
        TOLERANCE);
    assertEquals(sixtyFps.centerMapY(), thirtyFps.centerMapY(), TOLERANCE);
  }

  @Test
  void diagonalMovementIsNormalised() {
    MapCamera eastOnly = new MapCamera(projection, 1280, 720);
    MapCamera diagonal = new MapCamera(projection, 1280, 720);
    eastOnly.zoomAt(new Point(640.0, 360.0), -8.0);
    diagonal.zoomAt(new Point(640.0, 360.0), -8.0);

    eastOnly.panFromKeyboard(1.0, 0.0, 0.05);
    diagonal.panFromKeyboard(1.0, 1.0, 0.05);

    double eastDistance = Math.abs(eastOnly.centerMapX());
    double diagonalDistance = Math.hypot(diagonal.centerMapX(), diagonal.centerMapY());
    assertEquals(eastDistance, diagonalDistance, TOLERANCE);
  }

  @Test
  void resizePreservesTheCentreAndZoom() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    camera.zoomAt(new Point(640.0, 360.0), -3.0);
    camera.panFromKeyboard(1.0, 0.2, 0.1);
    MapCameraState before = camera.state();

    camera.resize(1920, 1080);

    assertEquals(before.centerMapX(), camera.centerMapX(), TOLERANCE);
    assertEquals(before.centerMapY(), camera.centerMapY(), TOLERANCE);
    assertEquals(before.zoom(), camera.state().zoom(), TOLERANCE);
  }

  @Test
  void geographicFocusUsesLatitudeBeforeLongitude() {
    MapCamera camera = new MapCamera(projection, 1280, 720);

    camera.focusGeographic(51.5, -0.1, 4.0);
    Point projected = projection.project(51.5, -0.1);

    assertEquals(projected.x(), camera.centerMapX(), TOLERANCE);
    assertEquals(projected.y(), camera.centerMapY(), TOLERANCE);
    assertEquals(4.0, camera.zoom(), TOLERANCE);
  }

  @Test
  void dragAndNearestCopyStayContinuousAcrossTheHorizontalSeam() {
    MapCamera camera = new MapCamera(projection, 1280, 720);
    camera.zoomAt(new Point(640.0, 360.0), -6.0);
    double oneDegree = projection.worldBounds().width() / 360.0;
    double secondsToSeam = (projection.worldBounds().east() - oneDegree)
        / (MapCamera.DEFAULT_KEYBOARD_SPEED_PIXELS_PER_SECOND * camera.mapUnitsPerPixel());
    camera.panFromKeyboard(1.0, 0.0, secondsToSeam);
    Point fiji = projection.project(-18.0, -178.0);
    int copy = camera.nearestHorizontalWorldCopy(fiji.x());
    double beforeDrag = camera.mapToViewport(fiji, copy).x();

    camera.dragByPixels(45.0, 0.0);
    copy = camera.nearestHorizontalWorldCopy(fiji.x());
    double afterDrag = camera.mapToViewport(fiji, copy).x();

    assertEquals(45.0, afterDrag - beforeDrag, TOLERANCE);
    assertTrue(afterDrag >= 0.0 && afterDrag <= 1280.0);
  }
}
