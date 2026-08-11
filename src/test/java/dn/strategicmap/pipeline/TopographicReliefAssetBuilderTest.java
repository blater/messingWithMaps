package dn.strategicmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TopographicReliefAssetBuilderTest {
  @Test
  void mapsLongitudeAndLatitudeDirectlyToEquirectangularPixels() {
    assertEquals(0.0, TopographicReliefAssetBuilder.pixelX(-180.0, 2_048));
    assertEquals(1_024.0, TopographicReliefAssetBuilder.pixelX(0.0, 2_048));
    assertEquals(0.0, TopographicReliefAssetBuilder.pixelY(90.0, 1_024));
    assertEquals(512.0, TopographicReliefAssetBuilder.pixelY(0.0, 1_024));
    assertEquals(1_024.0, TopographicReliefAssetBuilder.pixelY(-90.0, 1_024));
  }
}
