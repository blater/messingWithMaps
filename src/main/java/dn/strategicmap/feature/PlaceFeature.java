package dn.strategicmap.feature;

import java.util.Set;

/** Static historical place; mutable campaign control remains outside the map component. */
public record PlaceFeature(
    String placeId,
    String name,
    double latitudeDegrees,
    double longitudeDegrees,
    Set<PlaceKind> kinds,
    MapRank rank,
    String regionId,
    String sourceDatasetId,
    CityStanding cityStanding) {}
