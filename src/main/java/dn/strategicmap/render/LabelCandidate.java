package dn.strategicmap.render;

import dn.strategicmap.geometry.Point;

/** Prepared text with one fixed canonical map anchor, orientation, and minimum visibility band. */
public record LabelCandidate(
    String stableKey,
    String text,
    Point anchor,
    LabelCategory category,
    ZoomBand minimumBand,
    float rotationDegrees) {}
