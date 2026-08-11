package dn.strategicmap.geometry;

import java.util.List;

/** A closed ring in projected map coordinates, as supplied by the asset pipeline. */
public record MapRing(List<Point> points) {}
