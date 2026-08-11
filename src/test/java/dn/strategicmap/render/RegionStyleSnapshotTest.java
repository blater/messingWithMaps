package dn.strategicmap.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RegionStyleSnapshotTest {
  @Test
  void returnsMappedStylesAndTheNeutralFallback() {
    RegionStyle neutral = new RegionStyle(0.5f, 0.5f, 0.4f, 1.0f);
    RegionStyle italy = new RegionStyle(0.4f, 0.6f, 0.4f, 1.0f);
    RegionStyleSnapshot styles = new RegionStyleSnapshot(7L, neutral, Map.of("italy", italy));

    assertEquals(7L, styles.version());
    assertSame(italy, styles.styleFor("italy"));
    assertSame(neutral, styles.styleFor("unmapped"));
  }
}
