package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ZoomBandPolicyTest {
  @Test
  void appliesHysteresisAroundBandBoundaries() {
    ZoomBandPolicy policy = new ZoomBandPolicy();

    assertEquals(ZoomBand.WORLD, policy.update(1.45));
    assertEquals(ZoomBand.GRAND, policy.update(1.50));
    assertEquals(ZoomBand.GRAND, policy.update(1.22));
    assertEquals(ZoomBand.WORLD, policy.update(1.20));
  }

  @Test
  void largeZoomJumpsCrossOnlyTheFixedSetOfBands() {
    ZoomBandPolicy policy = new ZoomBandPolicy();

    assertEquals(ZoomBand.DETAIL, policy.update(32.0));
    assertEquals(ZoomBand.WORLD, policy.update(1.0));
  }

  @Test
  void exposesSevenProgressiveLabelLevels() {
    ZoomBandPolicy policy = new ZoomBandPolicy();

    assertEquals(ZoomBand.WORLD, policy.update(1.0));
    assertEquals(ZoomBand.GRAND, policy.update(1.50));
    assertEquals(ZoomBand.THEATRE, policy.update(2.10));
    assertEquals(ZoomBand.NATIONAL, policy.update(3.10));
    assertEquals(ZoomBand.REGIONAL, policy.update(5.00));
    assertEquals(ZoomBand.LOCAL, policy.update(9.00));
    assertEquals(ZoomBand.DETAIL, policy.update(17.00));
  }
}
