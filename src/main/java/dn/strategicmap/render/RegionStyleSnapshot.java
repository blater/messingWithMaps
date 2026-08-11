package dn.strategicmap.render;

import java.util.Map;

/** A versioned table of region styles with a neutral fallback. */
public final class RegionStyleSnapshot {
  private final long version;
  private final RegionStyle neutralStyle;
  private final Map<String, RegionStyle> styles;

  public RegionStyleSnapshot(
      long version,
      RegionStyle neutralStyle,
      Map<String, RegionStyle> styles) {
    this.version = version;
    this.neutralStyle = neutralStyle;
    this.styles = styles;
  }

  public long version() {
    return version;
  }

  public RegionStyle styleFor(String regionId) {
    RegionStyle style = styles.get(regionId);
    return style == null ? neutralStyle : style;
  }

  public RegionStyle neutralStyle() {
    return neutralStyle;
  }
}
