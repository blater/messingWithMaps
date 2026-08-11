package dn.strategicmap.interaction;

import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.feature.PlaceFeature;

/** Testable region hover and click-selection state, independent of libGDX input. */
public final class MapInteraction {
  private final LoadedWorldMap worldMap;
  private final MapSpatialIndex spatialIndex;
  private final VisiblePlaceQuery placeQuery;
  private int hoveredRegionIndex = -1;
  private int selectedRegionIndex = -1;
  private int hoveredPlaceIndex = -1;
  private int selectedPlaceIndex = -1;

  public MapInteraction(LoadedWorldMap worldMap) {
    this(worldMap, null);
  }

  public MapInteraction(LoadedWorldMap worldMap, VisiblePlaceQuery placeQuery) {
    this.worldMap = worldMap;
    this.placeQuery = placeQuery;
    spatialIndex = new MapSpatialIndex(worldMap.geometry(), worldMap.worldBounds());
  }

  public boolean updateHover(double canonicalMapX, double mapY) {
    return updateHover(canonicalMapX, mapY, 0.0);
  }

  public boolean updateHover(
      double canonicalMapX, double mapY, double placeToleranceMapUnits) {
    int nextPlaceIndex = placeQuery == null
        ? -1
        : placeQuery.nearest(canonicalMapX, mapY, placeToleranceMapUnits);
    int nextRegionIndex = nextPlaceIndex < 0
        ? spatialIndex.regionAt(canonicalMapX, mapY)
        : -1;
    if (nextRegionIndex == hoveredRegionIndex && nextPlaceIndex == hoveredPlaceIndex) {
      return false;
    }
    hoveredRegionIndex = nextRegionIndex;
    hoveredPlaceIndex = nextPlaceIndex;
    return true;
  }

  public boolean selectHovered() {
    if (hoveredRegionIndex == selectedRegionIndex && hoveredPlaceIndex == selectedPlaceIndex) {
      return false;
    }
    selectedRegionIndex = hoveredRegionIndex;
    selectedPlaceIndex = hoveredPlaceIndex;
    return true;
  }

  public String hoveredRegionId() {
    return regionId(hoveredRegionIndex);
  }

  public String selectedRegionId() {
    return regionId(selectedRegionIndex);
  }

  public String hoveredPlaceId() {
    return placeId(hoveredPlaceIndex);
  }

  public String selectedPlaceId() {
    return placeId(selectedPlaceIndex);
  }

  public PlaceFeature hoveredPlace() {
    return hoveredPlaceIndex < 0 ? null : placeQuery.place(hoveredPlaceIndex);
  }

  public int lastCandidateCount() {
    return spatialIndex.lastCandidateCount();
  }

  private String regionId(int regionIndex) {
    return regionIndex < 0 ? null : worldMap.region(regionIndex).regionId();
  }

  private String placeId(int placeIndex) {
    return placeIndex < 0 ? null : placeQuery.place(placeIndex).placeId();
  }
}
