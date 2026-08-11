package dn.strategicmap.label;

/** Build- and editor-owned adjustments applied to one prepared label composition. */
public record LabelLayoutOverride(
    double trackingDeltaMapUnits,
    double offsetMapX,
    double offsetMapY,
    double rotationDeltaDegrees,
    double fontScaleMultiplier,
    boolean hidden,
    LabelZoomBandOverride minimumBandOverride) {
  public static final LabelLayoutOverride IDENTITY =
      new LabelLayoutOverride(
          0.0, 0.0, 0.0, 0.0, 1.0, false, LabelZoomBandOverride.DEFAULT);

  public LabelLayoutOverride(
      double trackingDeltaMapUnits,
      double offsetMapX,
      double offsetMapY,
      double rotationDeltaDegrees,
      double fontScaleMultiplier) {
    this(
        trackingDeltaMapUnits, offsetMapX, offsetMapY, rotationDeltaDegrees,
        fontScaleMultiplier, false, LabelZoomBandOverride.DEFAULT);
  }

  public boolean isIdentity() {
    return equals(IDENTITY);
  }
}
