package dn.strategicmap.geography;

import dn.strategicmap.geometry.MapPolygon;
import java.util.List;

/** Neutral geographic data owned by the map asset pipeline, with no political state. */
public record Region(
    String regionId,
    String displayName,
    List<MapPolygon> geometryParts,
    String sourceDatasetId) {}
