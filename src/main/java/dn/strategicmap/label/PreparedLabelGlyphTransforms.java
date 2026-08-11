package dn.strategicmap.label;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministically materialises label-level tracking, position, rotation, and scale deltas. */
public final class PreparedLabelGlyphTransforms {
  private PreparedLabelGlyphTransforms() {}

  public static List<PreparedLabelGlyph> apply(
      List<PreparedLabelGlyph> baseGlyphs,
      Map<String, LabelLayoutOverride> overrides) {
    Map<LineKey, Integer> lastGlyphIndex = new HashMap<>();
    for (PreparedLabelGlyph glyph : baseGlyphs) {
      lastGlyphIndex.merge(
          new LineKey(glyph.labelKey(), glyph.variant(), glyph.lineIndex()),
          glyph.glyphIndex(), Math::max);
    }

    List<PreparedLabelGlyph> tracked = new ArrayList<>(baseGlyphs.size());
    Map<VariantKey, Bounds> bounds = new HashMap<>();
    for (PreparedLabelGlyph glyph : baseGlyphs) {
      LabelLayoutOverride override = overrides.getOrDefault(
          glyph.labelKey(), LabelLayoutOverride.IDENTITY);
      int finalIndex = lastGlyphIndex.get(
          new LineKey(glyph.labelKey(), glyph.variant(), glyph.lineIndex()));
      double trackingOffset = override.trackingDeltaMapUnits()
          * (glyph.glyphIndex() - finalIndex * 0.5);
      double radians = Math.toRadians(glyph.rotationDegrees());
      double mapX = glyph.mapX() + Math.cos(radians) * trackingOffset;
      double mapY = glyph.mapY() + Math.sin(radians) * trackingOffset;
      PreparedLabelGlyph shifted = new PreparedLabelGlyph(
          glyph.labelKey(), glyph.variant(), glyph.fontFace(), glyph.lineIndex(), glyph.glyphIndex(),
          glyph.character(), mapX, mapY, glyph.rotationDegrees(), glyph.fontScaleMapUnits());
      tracked.add(shifted);
      bounds.computeIfAbsent(new VariantKey(glyph.labelKey(), glyph.variant()),
          ignored -> new Bounds()).include(mapX, mapY);
    }

    List<PreparedLabelGlyph> result = new ArrayList<>(tracked.size());
    for (PreparedLabelGlyph glyph : tracked) {
      LabelLayoutOverride override = overrides.getOrDefault(
          glyph.labelKey(), LabelLayoutOverride.IDENTITY);
      Bounds labelBounds = bounds.get(new VariantKey(glyph.labelKey(), glyph.variant()));
      double pivotX = labelBounds.centreX();
      double pivotY = labelBounds.centreY();
      double radians = Math.toRadians(override.rotationDeltaDegrees());
      double relativeX = glyph.mapX() - pivotX;
      double relativeY = glyph.mapY() - pivotY;
      double mapX = pivotX + relativeX * Math.cos(radians) - relativeY * Math.sin(radians)
          + override.offsetMapX();
      double mapY = pivotY + relativeX * Math.sin(radians) + relativeY * Math.cos(radians)
          + override.offsetMapY();
      result.add(new PreparedLabelGlyph(
          glyph.labelKey(), glyph.variant(), glyph.fontFace(), glyph.lineIndex(), glyph.glyphIndex(),
          glyph.character(), mapX, mapY,
          (float) (glyph.rotationDegrees() + override.rotationDeltaDegrees()),
          (float) (glyph.fontScaleMapUnits() * override.fontScaleMultiplier())));
    }
    return result;
  }

  private record LineKey(String labelKey, String variant, int lineIndex) {}
  private record VariantKey(String labelKey, String variant) {}

  private static final class Bounds {
    private double west = Double.POSITIVE_INFINITY;
    private double south = Double.POSITIVE_INFINITY;
    private double east = Double.NEGATIVE_INFINITY;
    private double north = Double.NEGATIVE_INFINITY;

    void include(double mapX, double mapY) {
      west = Math.min(west, mapX);
      south = Math.min(south, mapY);
      east = Math.max(east, mapX);
      north = Math.max(north, mapY);
    }

    double centreX() { return (west + east) * 0.5; }
    double centreY() { return (south + north) * 0.5; }
  }
}
