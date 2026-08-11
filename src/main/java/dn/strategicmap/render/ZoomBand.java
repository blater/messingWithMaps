package dn.strategicmap.render;

public enum ZoomBand {
  WORLD,
  GRAND,
  THEATRE,
  NATIONAL,
  REGIONAL,
  LOCAL,
  DETAIL;

  public boolean includes(ZoomBand minimumBand) {
    return ordinal() >= minimumBand.ordinal();
  }
}
