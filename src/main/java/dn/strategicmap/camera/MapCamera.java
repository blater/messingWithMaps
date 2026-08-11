package dn.strategicmap.camera;

import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.Point;

/** Input-independent navigation for a flat, horizontally wrapping map. */
public final class MapCamera {
  public static final double MIN_ZOOM = 1.0;
  public static final double MAX_ZOOM = 32.0;
  public static final double DEFAULT_KEYBOARD_SPEED_PIXELS_PER_SECOND = 850.0;
  public static final double DEFAULT_MARGIN_PIXELS = 72.0;
  private static final double WHEEL_ZOOM_BASE = 1.18;

  private final FlatMapProjection projection;
  private final MapBounds worldBounds;
  private final double marginPixels;
  private int viewportWidthPixels;
  private int viewportHeightPixels;
  private double centerMapX;
  private double centerMapY;
  private double zoom;

  public MapCamera(FlatMapProjection projection, int viewportWidthPixels, int viewportHeightPixels) {
    this(projection, viewportWidthPixels, viewportHeightPixels, DEFAULT_MARGIN_PIXELS);
  }

  public MapCamera(
      FlatMapProjection projection,
      int viewportWidthPixels,
      int viewportHeightPixels,
      double marginPixels) {
    this.projection = projection;
    this.worldBounds = projection.worldBounds();
    this.marginPixels = marginPixels;
    this.viewportWidthPixels = viewportWidthPixels;
    this.viewportHeightPixels = viewportHeightPixels;
    resetToFitWorld();
  }

  public void resize(int widthPixels, int heightPixels) {
    viewportWidthPixels = widthPixels;
    viewportHeightPixels = heightPixels;
    clampVerticalCenter();
  }

  public void resetToFitWorld() {
    zoom = MIN_ZOOM;
    centerMapX = worldBounds.centerX();
    centerMapY = worldBounds.centerY();
  }

  public void focusGeographic(
      double latitudeDegrees, double longitudeDegrees, double requestedZoom) {
    Point centre = projection.project(latitudeDegrees, longitudeDegrees);
    zoom = clamp(requestedZoom, MIN_ZOOM, MAX_ZOOM);
    centerMapX = projection.canonicalMapX(centre.x());
    centerMapY = centre.y();
    clampVerticalCenter();
  }

  public void zoomAt(Point viewportPoint, double wheelSteps) {
    double beforeMapX = rawMapXAt(viewportPoint.x());
    double beforeMapY = rawMapYAt(viewportPoint.y());
    zoom = clamp(zoom * Math.pow(WHEEL_ZOOM_BASE, -wheelSteps), MIN_ZOOM, MAX_ZOOM);
    centerMapX += beforeMapX - rawMapXAt(viewportPoint.x());
    centerMapY += beforeMapY - rawMapYAt(viewportPoint.y());
    centerMapX = projection.canonicalMapX(centerMapX);
    clampVerticalCenter();
  }

  public void dragByPixels(double deltaScreenX, double deltaScreenY) {
    double unitsPerPixel = mapUnitsPerPixel();
    centerMapX = projection.canonicalMapX(centerMapX - deltaScreenX * unitsPerPixel);
    centerMapY += deltaScreenY * unitsPerPixel;
    clampVerticalCenter();
  }

  public void panFromKeyboard(double east, double north, double deltaSeconds) {
    panFromKeyboard(east, north, deltaSeconds, DEFAULT_KEYBOARD_SPEED_PIXELS_PER_SECOND);
  }

  public void panFromKeyboard(
      double east,
      double north,
      double deltaSeconds,
      double speedPixelsPerSecond) {
    double length = Math.hypot(east, north);
    if (length > 1.0) {
      east /= length;
      north /= length;
    }
    double distance = speedPixelsPerSecond * deltaSeconds * mapUnitsPerPixel();
    centerMapX = projection.canonicalMapX(centerMapX + east * distance);
    centerMapY += north * distance;
    clampVerticalCenter();
  }

  public Point viewportToCanonicalMap(Point viewportPoint) {
    return new Point(
        canonicalMapXAtViewportX(viewportPoint.x()),
        mapYAtViewportY(viewportPoint.y()));
  }

  public double canonicalMapXAtViewportX(double viewportX) {
    return projection.canonicalMapX(rawMapXAt(viewportX));
  }

  public double mapYAtViewportY(double viewportY) {
    return rawMapYAt(viewportY);
  }

  public Point mapToViewport(Point mapPoint, int horizontalWorldCopy) {
    double copiedMapX = mapPoint.x() + horizontalWorldCopy * worldBounds.width();
    return new Point(
        viewportXForMapX(copiedMapX),
        viewportYForMapY(mapPoint.y()));
  }

  public int nearestHorizontalWorldCopy(double canonicalMapX) {
    return (int) Math.round((centerMapX - canonicalMapX) / worldBounds.width());
  }

  public double viewportXForMapX(double mapXInWorldCopy) {
    return (mapXInWorldCopy - centerMapX) / mapUnitsPerPixel() + viewportWidthPixels * 0.5;
  }

  public double viewportYForMapY(double mapY) {
    return (centerMapY - mapY) / mapUnitsPerPixel() + viewportHeightPixels * 0.5;
  }

  public double visibleMapWidth() {
    return viewportWidthPixels * mapUnitsPerPixel();
  }

  public double visibleMapHeight() {
    return viewportHeightPixels * mapUnitsPerPixel();
  }

  public double mapUnitsPerPixel() {
    return fitMapUnitsPerPixel() / zoom;
  }

  public double centerMapX() {
    return centerMapX;
  }

  public double centerMapY() {
    return centerMapY;
  }

  public double zoom() {
    return zoom;
  }

  public int viewportWidthPixels() {
    return viewportWidthPixels;
  }

  public int viewportHeightPixels() {
    return viewportHeightPixels;
  }

  public MapCameraState state() {
    return new MapCameraState(
        centerMapX,
        centerMapY,
        zoom,
        mapUnitsPerPixel(),
        viewportWidthPixels,
        viewportHeightPixels);
  }

  public MapBounds worldBounds() {
    return worldBounds;
  }

  private double rawMapXAt(double viewportX) {
    return centerMapX + (viewportX - viewportWidthPixels * 0.5) * mapUnitsPerPixel();
  }

  private double rawMapYAt(double viewportY) {
    return centerMapY - (viewportY - viewportHeightPixels * 0.5) * mapUnitsPerPixel();
  }

  private double fitMapUnitsPerPixel() {
    return Math.max(
        worldBounds.width() / viewportWidthPixels,
        worldBounds.height() / viewportHeightPixels);
  }

  private void clampVerticalCenter() {
    double unitsPerPixel = mapUnitsPerPixel();
    double halfVisibleHeight = viewportHeightPixels * unitsPerPixel * 0.5;
    double marginMapUnits = marginPixels * unitsPerPixel;
    double minimumCenter = worldBounds.south() + halfVisibleHeight - marginMapUnits;
    double maximumCenter = worldBounds.north() - halfVisibleHeight + marginMapUnits;
    centerMapY = minimumCenter > maximumCenter
        ? worldBounds.centerY()
        : clamp(centerMapY, minimumCenter, maximumCenter);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }
}
