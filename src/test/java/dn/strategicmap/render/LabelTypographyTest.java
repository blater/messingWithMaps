package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabelTypographyTest {
  @Test
  void appliesTheReviewedLateNineteenthCenturyHierarchy() {
    assertEquals("A  T  L  A  N  T  I  C", LabelTypography.WATER.displayText("Atlantic"));
    assertEquals("S  O  U  T  H     A  T  L  A  N  T  I  C",
        LabelTypography.WATER.displayText("South Atlantic"));
    assertEquals("PARIS", LabelTypography.CAPITAL.displayText("Paris"));
    assertEquals("Bosnia", LabelTypography.LAND.displayText("Bosnia"));
    assertEquals("THE GERMAN EMPIRE", LabelTypography.PRIMARY_GROUP.displayText(
        "The German Empire"));
    assertTrue(LabelTypography.CAPITAL.bold());
    assertTrue(LabelTypography.WATER.scale() > LabelTypography.PRIMARY_GROUP.scale());
    assertTrue(LabelTypography.PRIMARY_GROUP.scale() > LabelTypography.CAPITAL.scale());
  }
}
