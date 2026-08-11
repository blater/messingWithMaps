package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.geometry.Point;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.LabelZoomBandOverride;
import org.junit.jupiter.api.Test;

class LabelOverrideVisibilityTest {
  private static final LabelCandidate LABEL = label(ZoomBand.REGIONAL);

  @Test
  void hiddenLabelsRemainEditableButDoNotRenderNormally() {
    LabelLayoutOverride hidden = override(true, LabelZoomBandOverride.DEFAULT);

    assertFalse(MapLabelRenderer.visibleAt(hidden, 1.0f, false));
    assertTrue(MapLabelRenderer.visibleAt(hidden, 1.0f, true));
  }

  @Test
  void explicitMinimumBandReplacesTheCatalogueBand() {
    LabelLayoutOverride theatre = override(false, LabelZoomBandOverride.THEATRE);

    assertEquals(ZoomBand.THEATRE, MapLabelRenderer.effectiveMinimumBand(LABEL, theatre));
    assertTrue(MapLabelRenderer.opacityAt(
        LABEL, theatre, ZoomBand.THEATRE, ZoomBandPolicy.midpointZoom(ZoomBand.THEATRE)) > 0.0f);
    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        LABEL, theatre, ZoomBand.GRAND, ZoomBandPolicy.boundaryInto(ZoomBand.THEATRE)));
  }

  @Test
  void worldGrandAndTheatreFadeInAndOutAcrossTwoLowerBands() {
    LabelLayoutOverride unchanged = LabelLayoutOverride.IDENTITY;

    assertEquals(1.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.WORLD), unchanged, ZoomBand.WORLD, 1.0));
    float worldDuringGrand = MapLabelRenderer.opacityAt(
        label(ZoomBand.WORLD), unchanged, ZoomBand.GRAND,
        ZoomBandPolicy.midpointZoom(ZoomBand.GRAND));
    assertTrue(worldDuringGrand > 0.0f && worldDuringGrand < 1.0f);
    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.WORLD), unchanged, ZoomBand.THEATRE,
        ZoomBandPolicy.midpointZoom(ZoomBand.THEATRE)));

    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.GRAND), unchanged, ZoomBand.WORLD,
        ZoomBandPolicy.boundaryInto(ZoomBand.GRAND)));
    assertEquals(1.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.GRAND), unchanged, ZoomBand.GRAND,
        ZoomBandPolicy.midpointZoom(ZoomBand.GRAND)));
    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.GRAND), unchanged, ZoomBand.NATIONAL,
        ZoomBandPolicy.midpointZoom(ZoomBand.NATIONAL)));

    assertEquals(1.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.THEATRE), unchanged, ZoomBand.THEATRE,
        ZoomBandPolicy.midpointZoom(ZoomBand.THEATRE)));
    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        label(ZoomBand.THEATRE), unchanged, ZoomBand.REGIONAL,
        ZoomBandPolicy.midpointZoom(ZoomBand.REGIONAL)));
  }

  @Test
  void nationalAndCloserBandsKeepCumulativeHardVisibility() {
    LabelCandidate national = label(ZoomBand.NATIONAL);

    assertEquals(0.0f, MapLabelRenderer.opacityAt(
        national, LabelLayoutOverride.IDENTITY, ZoomBand.THEATRE, 2.7));
    assertEquals(1.0f, MapLabelRenderer.opacityAt(
        national, LabelLayoutOverride.IDENTITY, ZoomBand.NATIONAL, 3.1));
    assertEquals(1.0f, MapLabelRenderer.opacityAt(
        national, LabelLayoutOverride.IDENTITY, ZoomBand.DETAIL, 20.0));
  }

  private static LabelLayoutOverride override(
      boolean hidden, LabelZoomBandOverride minimumBand) {
    return new LabelLayoutOverride(0.0, 0.0, 0.0, 0.0, 1.0, hidden, minimumBand);
  }

  private static LabelCandidate label(ZoomBand minimumBand) {
    return new LabelCandidate(
        "label.test", "Test", new Point(0.0, 0.0), LabelCategory.LAND,
        minimumBand, 0.0f);
  }
}
