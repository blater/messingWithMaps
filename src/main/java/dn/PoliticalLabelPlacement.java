package dn;

import dn.strategicmap.geometry.Point;
import dn.strategicmap.render.ZoomBand;

/** Integration-owned, prepared map placement for one political actor label. */
public record PoliticalLabelPlacement(
    String actorId,
    Point anchor,
    ZoomBand minimumBand,
    float rotationDegrees) {}
