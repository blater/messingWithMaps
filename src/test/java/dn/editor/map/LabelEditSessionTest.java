package dn.editor.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.LabelLayoutOverrideTsv;
import dn.strategicmap.label.LabelZoomBandOverride;
import dn.strategicmap.label.PreparedLabelGlyph;
import dn.strategicmap.label.PreparedLabelGlyphTsv;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelEditSessionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void saveWritesBothBuildOverridesAndImmediatelyUsableRuntimeCoordinates() throws Exception {
    Path base = temporaryDirectory.resolve("base.tsv");
    Path runtime = temporaryDirectory.resolve("runtime.tsv");
    Path overrides = temporaryDirectory.resolve("overrides.tsv");
    PreparedLabelGlyphTsv.write(base, List.of(
        glyph(0, 0.0), glyph(1, 10.0)));
    LabelEditSession session = LabelEditSession.open(overrides, base, runtime);
    session.select("water.test", LabelZoomBandOverride.GRAND);
    session.adjust(LabelEditSession.Property.TRACKING, 1);
    session.adjust(LabelEditSession.Property.EAST_WEST, 1);

    session.save();

    LabelLayoutOverride saved = LabelLayoutOverrideTsv.read(overrides).get("water.test");
    assertEquals(0.10, saved.trackingDeltaMapUnits(), 1.0e-9);
    assertEquals(0.50, saved.offsetMapX(), 1.0e-9);
    List<PreparedLabelGlyph> materialized = PreparedLabelGlyphTsv.read(runtime);
    assertEquals(0.45, materialized.get(0).mapX(), 1.0e-6);
    assertEquals(10.55, materialized.get(1).mapX(), 1.0e-6);
  }

  @Test
  void clearRemovesOnlyTheSelectedLabelsOverride() throws Exception {
    Path base = temporaryDirectory.resolve("base.tsv");
    Path runtime = temporaryDirectory.resolve("runtime.tsv");
    Path overrides = temporaryDirectory.resolve("overrides.tsv");
    PreparedLabelGlyphTsv.write(base, List.of(
        glyph("label.one", 0.0), glyph("label.two", 10.0)));
    LabelEditSession session = LabelEditSession.open(overrides, base, runtime);
    session.select("label.one", LabelZoomBandOverride.REGIONAL);
    session.adjust(LabelEditSession.Property.EAST_WEST, 1);
    session.select("label.two", LabelZoomBandOverride.LOCAL);
    session.adjust(LabelEditSession.Property.EAST_WEST, 1);

    assertTrue(session.clearSelected());

    assertTrue(session.overrides().containsKey("label.one"));
    assertFalse(session.overrides().containsKey("label.two"));
  }

  @Test
  void undoAndRedoRetainOneMostRecentEdit() throws Exception {
    LabelEditSession session = sessionWithOneLabel();
    session.select("water.test", LabelZoomBandOverride.GRAND);
    session.adjust(LabelEditSession.Property.EAST_WEST, 1);
    session.adjust(LabelEditSession.Property.NORTH_SOUTH, 1);

    assertTrue(session.undo());
    assertEquals(0.50, session.selectedOverride().offsetMapX(), 1.0e-9);
    assertEquals(0.0, session.selectedOverride().offsetMapY(), 1.0e-9);
    assertFalse(session.canUndo());
    assertTrue(session.canRedo());

    assertTrue(session.redo());
    assertEquals(0.50, session.selectedOverride().offsetMapY(), 1.0e-9);
  }

  @Test
  void spacingVisibilityAndZoomBandAreIndependentEdits() throws Exception {
    LabelEditSession session = sessionWithOneLabel();
    session.select("water.test", LabelZoomBandOverride.GRAND);
    session.adjust(LabelEditSession.Property.TRACKING, 1);
    session.adjust(LabelEditSession.Property.EAST_WEST, 1);

    assertTrue(session.clearSpacing());
    assertEquals(0.0, session.selectedOverride().trackingDeltaMapUnits(), 1.0e-9);
    assertEquals(0.50, session.selectedOverride().offsetMapX(), 1.0e-9);
    assertTrue(session.toggleHidden());
    assertTrue(session.selectedOverride().hidden());
    assertTrue(session.adjust(LabelEditSession.Property.ZOOM_BAND, 1));
    assertEquals(LabelZoomBandOverride.THEATRE, session.selectedMinimumBand());
    assertTrue(session.selectedMinimumBandIsOverridden());

    session.save();
    LabelLayoutOverride saved = LabelLayoutOverrideTsv.read(
        temporaryDirectory.resolve("one-overrides.tsv")).get("water.test");
    assertTrue(saved.hidden());
    assertEquals(LabelZoomBandOverride.THEATRE, saved.minimumBandOverride());
  }

  private LabelEditSession sessionWithOneLabel() throws Exception {
    Path base = temporaryDirectory.resolve("one-base.tsv");
    PreparedLabelGlyphTsv.write(base, List.of(glyph(0, 0.0), glyph(1, 10.0)));
    return LabelEditSession.open(
        temporaryDirectory.resolve("one-overrides.tsv"), base,
        temporaryDirectory.resolve("one-runtime.tsv"));
  }

  private static PreparedLabelGlyph glyph(int glyphIndex, double mapX) {
    return new PreparedLabelGlyph(
        "water.test", "DEFAULT", "SERIF", 0, glyphIndex, glyphIndex == 0 ? "A" : "B",
        mapX, 0.0, 0.0f, 0.001f);
  }

  private static PreparedLabelGlyph glyph(String labelKey, double mapX) {
    return new PreparedLabelGlyph(
        labelKey, "DEFAULT", "SERIF", 0, 0, "A", mapX, 0.0, 0.0f, 0.001f);
  }
}
