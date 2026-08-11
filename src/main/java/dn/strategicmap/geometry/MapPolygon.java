package dn.strategicmap.geometry;

import java.util.List;

/** One projected map polygon with an exterior ring and zero or more holes. */
public record MapPolygon(MapRing exterior, List<MapRing> holes) {}
