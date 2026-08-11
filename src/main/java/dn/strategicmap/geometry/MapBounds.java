package dn.strategicmap.geometry;

/** Inclusive projected bounds in north, south, east, west constructor order. */
public record MapBounds(double north, double south, double east, double west) {
  public double width() {
    return east - west;
  }

  public double height() {
    return north - south;
  }

  public double centerX() {
    return (west + east) * 0.5;
  }

  public double centerY() {
    return (south + north) * 0.5;
  }
}
