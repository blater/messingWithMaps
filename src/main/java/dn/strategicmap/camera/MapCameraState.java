package dn.strategicmap.camera;

/** An immutable snapshot used by tests, diagnostics, and read-only consumers. */
public record MapCameraState(
    double centerMapX,
    double centerMapY,
    double zoom,
    double mapUnitsPerPixel,
    int viewportWidthPixels,
    int viewportHeightPixels) {}
