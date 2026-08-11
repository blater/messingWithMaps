package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PreparedLabelGlyphIndexTest {
  @Test
  void loadsEveryStrategicLabelAndSelectsCapitalVariants() {
    PreparedLabelGlyphIndex index = PreparedLabelGlyphIndex.loadDefault();

    assertTrue(index.labelKeys().size() == 393);
    assertTrue(index.labelKeys().contains("water.north-atlantic"));
    assertTrue(index.labelKeys().contains("water.japan"));
    assertTrue(index.labelKeys().contains("political.russian-empire"));
    assertTrue(index.labelKeys().contains("place.paris"));
    assertFalse(index.glyphs("water.north-atlantic", LabelCategory.WATER).isEmpty());
    assertTrue(index.glyphs("water.japan", LabelCategory.SEA).stream()
        .anyMatch(glyph -> glyph.rotationDegrees() != 0.0f));
    assertTrue(index.glyphs("continent.north-america", LabelCategory.LAND).stream()
        .map(dn.strategicmap.label.PreparedLabelGlyph::rotationDegrees)
        .distinct().count() > 1);
    assertTrue(index.glyphs("place.paris", LabelCategory.CAPITAL).stream()
        .allMatch(glyph -> glyph.variant().equals("CAPITAL")));
    assertTrue(index.glyphs("place.monrovia", LabelCategory.MINOR_CAPITAL).stream()
        .allMatch(glyph -> glyph.variant().equals("CAPITAL")));
    assertTrue(index.glyphs("place.monrovia", LabelCategory.MINOR_CAPITAL).stream()
        .anyMatch(glyph -> glyph.character().equals("o")));
    assertTrue(index.glyphs("place.paris", LabelCategory.CITY).stream()
        .allMatch(glyph -> glyph.variant().equals("DEFAULT")));
  }
}
