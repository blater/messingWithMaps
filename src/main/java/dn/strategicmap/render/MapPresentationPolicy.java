package dn.strategicmap.render;

import dn.strategicmap.feature.GeographicLabel;
import dn.strategicmap.feature.GeographicLabelKind;
import dn.strategicmap.feature.MapRank;
import dn.strategicmap.geometry.FlatMapProjection;

/** Central mapping from authored feature importance to visual behaviour. */
public final class MapPresentationPolicy {
  private MapPresentationPolicy() {}

  public static LabelCandidate geographicLabel(
      GeographicLabel label, FlatMapProjection projection, float rotationDegrees) {
    LabelCategory category = switch (label.kind()) {
      case OCEAN -> LabelCategory.WATER;
      case SEA -> LabelCategory.SEA;
      default -> LabelCategory.LAND;
    };
    return new LabelCandidate(
        label.labelId(),
        label.name(),
        projection.project(label.latitudeDegrees(), label.longitudeDegrees()),
        category,
        minimumBand(label.rank()),
        rotationDegrees);
  }

  public static ZoomBand minimumBand(MapRank rank) {
    return switch (rank) {
      case GLOBAL -> ZoomBand.WORLD;
      case GRAND -> ZoomBand.GRAND;
      case MAJOR -> ZoomBand.THEATRE;
      case NATIONAL -> ZoomBand.NATIONAL;
      case SECONDARY -> ZoomBand.REGIONAL;
      case LOCAL -> ZoomBand.LOCAL;
      case DETAIL -> ZoomBand.DETAIL;
    };
  }

  public static ZoomBand placeMinimumBand(MapRank rank) {
    return switch (rank) {
      case GLOBAL, GRAND -> ZoomBand.NATIONAL;
      case MAJOR, NATIONAL -> ZoomBand.REGIONAL;
      case SECONDARY -> ZoomBand.LOCAL;
      case LOCAL, DETAIL -> ZoomBand.DETAIL;
    };
  }
}
