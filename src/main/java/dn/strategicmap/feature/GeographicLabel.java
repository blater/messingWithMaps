package dn.strategicmap.feature;

/** Static, non-political geographic label authored in latitude-first coordinates. */
public record GeographicLabel(
    String labelId,
    String name,
    GeographicLabelKind kind,
    double latitudeDegrees,
    double longitudeDegrees,
    MapRank rank,
    String sourceDatasetId) {}
