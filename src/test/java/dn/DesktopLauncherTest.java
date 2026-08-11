package dn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesktopLauncherTest {
  @Test
  void recognisesOutlinesOnlyFlag() {
    assertTrue(DesktopLauncher.outlinesOnly(new String[] {"--map-outlines-only"}));
    assertFalse(DesktopLauncher.outlinesOnly(new String[] {"--map-view=0,0,2"}));
  }
}
