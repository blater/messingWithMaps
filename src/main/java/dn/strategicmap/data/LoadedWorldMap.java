package dn.strategicmap.data;

import dn.strategicmap.geography.Region;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.PreparedMapGeometry;
import java.util.List;

/** Geography and prepared render geometry loaded from one generated world-map asset. */
public final class LoadedWorldMap {
  private final String sourceDigest;
  private final MapBounds worldBounds;
  private final List<Region> regions;
  private final PreparedMapGeometry geometry;

  public LoadedWorldMap(
      String sourceDigest,
      MapBounds worldBounds,
      List<Region> regions,
      PreparedMapGeometry geometry) {
    this.sourceDigest = sourceDigest;
    this.worldBounds = worldBounds;
    this.regions = regions;
    this.geometry = geometry;
  }

  public String sourceDigest() {
    return sourceDigest;
  }

  public MapBounds worldBounds() {
    return worldBounds;
  }

  public int regionCount() {
    return regions.size();
  }

  public Region region(int index) {
    return regions.get(index);
  }

  public PreparedMapGeometry geometry() {
    return geometry;
  }
}
