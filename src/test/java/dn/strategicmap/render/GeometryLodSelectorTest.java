package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeometryLodSelectorTest {
  @Test
  void selectsFromPixelErrorAndUsesHysteresis() {
    GeometryLodSelector selector = new GeometryLodSelector(0.05);

    assertEquals(0, selector.select(0.10));
    assertEquals(1, selector.select(0.06));
    assertEquals(1, selector.select(0.075));
    assertEquals(0, selector.select(0.09));
  }

  @Test
  void keepsTheCurrentLevelAtBothThresholds() {
    GeometryLodSelector selector = new GeometryLodSelector(0.05);

    assertEquals(0, selector.select(0.05 / GeometryLodSelector.ENTER_FINE_ERROR_PIXELS));
    assertEquals(1, selector.select(0.05 / 0.80));
    assertEquals(1, selector.select(0.05 / GeometryLodSelector.RETURN_COARSE_ERROR_PIXELS));
  }
}
