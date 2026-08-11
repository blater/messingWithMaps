package dn.strategicmap.label;

/** One build-shaped glyph origin and tangent in canonical map coordinates. */
public record PreparedLabelGlyph(
    String labelKey,
    String variant,
    String fontFace,
    int lineIndex,
    int glyphIndex,
    String character,
    double mapX,
    double mapY,
    float rotationDegrees,
    float fontScaleMapUnits) {}
