package dn.strategicmap.render;

/** Selects the two prepared geometry levels from projected screen error with hysteresis. */
final class GeometryLodSelector {
  static final double ENTER_FINE_ERROR_PIXELS = 0.75;
  static final double RETURN_COARSE_ERROR_PIXELS = 0.60;

  private final double coarseMaxErrorMapUnits;
  private int selectedLevel;

  GeometryLodSelector(double coarseMaxErrorMapUnits) {
    this.coarseMaxErrorMapUnits = coarseMaxErrorMapUnits;
  }

  int select(double mapUnitsPerPixel) {
    double errorPixels = coarseMaxErrorMapUnits / mapUnitsPerPixel;
    if (selectedLevel == 0 && errorPixels > ENTER_FINE_ERROR_PIXELS) {
      selectedLevel = 1;
    } else if (selectedLevel == 1 && errorPixels < RETURN_COARSE_ERROR_PIXELS) {
      selectedLevel = 0;
    }
    return selectedLevel;
  }
}
