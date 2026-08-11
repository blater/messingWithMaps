package dn.strategicmap.render;

/** One presentation-owned semantic zoom decision with threshold hysteresis. */
public final class ZoomBandPolicy {
  private static final double HYSTERESIS = 0.10;
  private static final double[] BOUNDARIES = {1.35, 1.9, 2.8, 4.5, 8.0, 15.0};
  private static final double[] MIDPOINTS = midpoints();

  private ZoomBand current = ZoomBand.WORLD;

  public ZoomBand update(double zoom) {
    // At most six iterations: the number of semantic boundaries is fixed.
    while (current.ordinal() < BOUNDARIES.length
        && zoom >= BOUNDARIES[current.ordinal()] * (1.0 + HYSTERESIS)) {
      current = ZoomBand.values()[current.ordinal() + 1];
    }
    while (current.ordinal() > 0
        && zoom < BOUNDARIES[current.ordinal() - 1] * (1.0 - HYSTERESIS)) {
      current = ZoomBand.values()[current.ordinal() - 1];
    }
    return current;
  }

  public ZoomBand current() {
    return current;
  }

  static double boundaryInto(ZoomBand band) {
    return band == ZoomBand.WORLD ? 1.0 : BOUNDARIES[band.ordinal() - 1];
  }

  static double midpointZoom(ZoomBand band) {
    return MIDPOINTS[band.ordinal()];
  }

  static double boundaryAfter(ZoomBand band) {
    return BOUNDARIES[band.ordinal()];
  }

  static double midpointTwoBandsAfter(ZoomBand band) {
    return MIDPOINTS[band.ordinal() + 2];
  }

  private static double[] midpoints() {
    double[] midpoints = new double[BOUNDARIES.length];
    for (int ordinal = 0; ordinal < BOUNDARIES.length; ordinal++) {
      double lower = ordinal == 0 ? 1.0 : BOUNDARIES[ordinal - 1];
      midpoints[ordinal] = Math.sqrt(lower * BOUNDARIES[ordinal]);
    }
    return midpoints;
  }
}
