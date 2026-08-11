package dn.strategicmap.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.data.WorldMapAssetLoader;
import dn.strategicmap.geography.Region;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.Point;
import dn.strategicmap.geometry.PreparedMapGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapInteractionTest {
  @Test
  void hoverAndSelectionRemainSeparateAndOceanClickClearsSelection() {
    MapInteraction interaction = new MapInteraction(WorldMapAssetLoader.loadDefault());
    FlatMapProjection projection = new FlatMapProjection();

    Point namibia = projection.project(-22.0, 17.0);
    assertTrue(interaction.updateHover(namibia.x(), namibia.y()));
    assertEquals("NAM", interaction.hoveredRegionId());
    assertTrue(interaction.selectHovered());
    assertEquals("NAM", interaction.selectedRegionId());

    Point cape = projection.project(-33.5, 18.5);
    assertTrue(interaction.updateHover(cape.x(), cape.y()));
    assertEquals("zaf-cape", interaction.hoveredRegionId());
    assertEquals("NAM", interaction.selectedRegionId());

    Point ocean = projection.project(-40.0, 0.0);
    assertTrue(interaction.updateHover(ocean.x(), ocean.y()));
    assertNull(interaction.hoveredRegionId());
    assertTrue(interaction.selectHovered());
    assertNull(interaction.selectedRegionId());
  }

  @Test
  void exactQueryExcludesPolygonHolesAndResolvesSharedBoundariesDeterministically() {
    LoadedWorldMap world = fixtureWorld(
        part(0, 0.0f, 0.0f, 5.0f, 5.0f, square(1.0f, 1.0f, 3.0f, 3.0f)),
        part(1, 5.0f, 0.0f, 10.0f, 5.0f));
    MapInteraction interaction = new MapInteraction(world);

    interaction.updateHover(2.0, 2.0);
    assertNull(interaction.hoveredRegionId());

    interaction.updateHover(4.0, 2.0);
    assertEquals("first", interaction.hoveredRegionId());

    interaction.updateHover(5.0, 2.0);
    assertEquals("first", interaction.hoveredRegionId());
  }

  @Test
  void datelineIslandIsSelectableWithoutScanningTheWorld() {
    MapInteraction interaction = new MapInteraction(WorldMapAssetLoader.loadDefault());
    Point fiji = new FlatMapProjection().project(-18.14, 178.45);

    interaction.updateHover(fiji.x(), fiji.y());

    assertEquals("FJI", interaction.hoveredRegionId());
    assertTrue(interaction.lastCandidateCount() < 64);
  }

  private static LoadedWorldMap fixtureWorld(PreparedMapGeometry.Part... parts) {
    return new LoadedWorldMap(
        "fixture",
        new MapBounds(90.0, -90.0, 180.0, -180.0),
        List.of(
            new Region("first", "First", List.of(), "fixture"),
            new Region("second", "Second", List.of(), "fixture")),
        new PreparedMapGeometry(List.of(parts), List.of()));
  }

  private static PreparedMapGeometry.Part part(
      int regionIndex,
      float west,
      float south,
      float east,
      float north,
      float[]... holes) {
    float[][] rings = new float[holes.length + 1][];
    rings[0] = square(west, south, east, north);
    System.arraycopy(holes, 0, rings, 1, holes.length);
    return new PreparedMapGeometry.Part(
        regionIndex, new MapBounds(north, south, east, west), rings);
  }

  private static float[] square(float west, float south, float east, float north) {
    return new float[] {
        west, south,
        east, south,
        east, north,
        west, north,
        west, south
    };
  }
}
