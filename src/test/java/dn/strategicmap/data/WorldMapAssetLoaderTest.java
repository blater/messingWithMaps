package dn.strategicmap.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dn.strategicmap.geography.Region;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.PreparedMapGeometry;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldMapAssetLoaderTest {
  private static final String SOURCE_DIGEST =
      "aa3c6cd4c515c6b2e789fe618c0c726a7e11bb561c56bd36ae43428ef4acd342";

  @Test
  void loadsTheGeneratedWorldAndRepresentativeRegions() {
    LoadedWorldMap world = WorldMapAssetLoader.loadDefault();

    assertEquals(SOURCE_DIGEST, world.sourceDigest());
    assertEquals(350, world.regionCount());
    assertEquals(4_159, world.geometry().partCount());
    assertTrue(
        regionIds(world).containsAll(Set.of(
            "NAM",
            "zaf-cape",
            "nga-north",
            "ind-northwest",
            "chn-northwest",
            "rus-south",
            "rus-kaliningrad",
            "pol-south",
            "fra-alsace-lorraine",
            "swe-north",
            "tur-east",
            "aus-west-central",
            "hawaii",
            "glp",
            "mtq",
            "guf",
            "bes-bonaire",
            "prt-azores",
            "FJI")));
    assertEquals(350, regionIds(world).size());
    assertEquals(1, region(world, "NAM").geometryParts().size());
    assertEquals("Hong Kong", region(world, "HKG").displayName());
    assertTrue(regionIds(world).stream().noneMatch(Set.of(
        "BRT", "CNM", "CSI", "CYN", "ESB", "IOA", "IOT", "KAB", "KAS", "SPI", "UMI",
        "USG", "WSB")::contains));
  }

  @Test
  void loadsOneCoarseGlobalLevelAndOneFineTileGrid() {
    PreparedMapGeometry geometry = WorldMapAssetLoader.loadDefault().geometry();
    PreparedMapGeometry.Level coarse = geometry.level(0);
    PreparedMapGeometry.Level fine = geometry.level(1);

    assertEquals(2, geometry.levelCount());
    assertEquals(0.05, coarse.maxErrorMapUnits());
    assertEquals(1, coarse.columns());
    assertEquals(1, coarse.rows());
    assertEquals(1, coarse.chunkCount());
    assertTrue(triangleCount(coarse) > 0);
    assertEquals(8, fine.columns());
    assertEquals(4, fine.rows());
    assertEquals(32, fine.chunkCount());
    assertTrue(triangleCount(coarse) * 7 < triangleCount(fine));
  }

  @Test
  void canonicalGeometryMapsEveryPartAndKeepsFijiOnBothSeams() {
    LoadedWorldMap world = WorldMapAssetLoader.loadDefault();
    boolean fijiTouchesEast = false;
    boolean fijiTouchesWest = false;
    int unmappedParts = 0;
    int fijiIndex = regionIndex(world, "FJI");
    var worldBounds = FlatMapProjection.WORLD_BOUNDS;

    for (int index = 0; index < world.geometry().partCount(); index++) {
      PreparedMapGeometry.Part part = world.geometry().part(index);
      if (part.regionIndex() < 0) {
        unmappedParts++;
      }
      if (part.regionIndex() == fijiIndex) {
        fijiTouchesEast |= part.bounds().east() == worldBounds.east();
        fijiTouchesWest |= part.bounds().west() == worldBounds.west();
      }
    }

    assertEquals(0, unmappedParts);
    assertTrue(fijiTouchesEast);
    assertTrue(fijiTouchesWest);
  }

  @Test
  void fineTileGridKeepsFijiGeometryAtBothHorizontalEdges() {
    LoadedWorldMap world = WorldMapAssetLoader.loadDefault();
    PreparedMapGeometry.Level fine = world.geometry().level(1);
    int fijiIndex = regionIndex(world, "FJI");

    assertTrue(chunkContainsRegion(fine.chunk(0, 1), fijiIndex)
        || chunkContainsRegion(fine.chunk(0, 2), fijiIndex));
    assertTrue(chunkContainsRegion(fine.chunk(7, 1), fijiIndex)
        || chunkContainsRegion(fine.chunk(7, 2), fijiIndex));
  }

  @Test
  void fineTriangulationPreservesARepresentativeRegionWithAHole() {
    LoadedWorldMap world = WorldMapAssetLoader.loadDefault();
    PreparedMapGeometry geometry = world.geometry();
    for (int regionIndex = 0; regionIndex < world.regionCount(); regionIndex++) {
      double polygonArea = 0.0;
      boolean hasHole = false;
      for (int partIndex = 0; partIndex < geometry.partCount(); partIndex++) {
        PreparedMapGeometry.Part part = geometry.part(partIndex);
        if (part.regionIndex() != regionIndex) {
          continue;
        }
        polygonArea += Math.abs(ringArea(part, 0));
        for (int ring = 1; ring < part.ringCount(); ring++) {
          polygonArea -= Math.abs(ringArea(part, ring));
          hasHole = true;
        }
      }
      if (hasHole && polygonArea >= 1.0) {
        assertEquals(
            polygonArea,
            triangleAreaForRegion(geometry.level(1), regionIndex),
            polygonArea * 1.0e-4);
        return;
      }
    }
    fail("Expected at least one region with a usable polygon hole");
  }

  @Test
  void everyRegionSurvivesCoverageSimplification() {
    LoadedWorldMap world = WorldMapAssetLoader.loadDefault();
    Set<Integer> coarseRegions = new HashSet<>();
    PreparedMapGeometry.Level coarse = world.geometry().level(0);
    for (int chunkIndex = 0; chunkIndex < coarse.chunkCount(); chunkIndex++) {
      PreparedMapGeometry.Chunk chunk = coarse.chunk(chunkIndex);
      for (int fillIndex = 0; fillIndex < chunk.fillCount(); fillIndex++) {
        coarseRegions.add(chunk.fill(fillIndex).regionIndex());
      }
    }
    assertEquals(world.regionCount(), coarseRegions.size());
  }

  @Test
  void preparedTrianglesDoNotCrossTheWholeFlatMap() {
    PreparedMapGeometry geometry = WorldMapAssetLoader.loadDefault().geometry();
    double halfWorldWidth = FlatMapProjection.WORLD_BOUNDS.width() * 0.5;
    for (int levelIndex = 0; levelIndex < geometry.levelCount(); levelIndex++) {
      PreparedMapGeometry.Level level = geometry.level(levelIndex);
      for (int chunkIndex = 0; chunkIndex < level.chunkCount(); chunkIndex++) {
        PreparedMapGeometry.Chunk chunk = level.chunk(chunkIndex);
        for (int fillIndex = 0; fillIndex < chunk.fillCount(); fillIndex++) {
          PreparedMapGeometry.Fill fill = chunk.fill(fillIndex);
          for (int vertex = 0; vertex < fill.vertexCount(); vertex += 3) {
            assertTrue(edgeWidth(fill, vertex, vertex + 1) <= halfWorldWidth);
            assertTrue(edgeWidth(fill, vertex + 1, vertex + 2) <= halfWorldWidth);
            assertTrue(edgeWidth(fill, vertex + 2, vertex) <= halfWorldWidth);
          }
        }
      }
    }
  }

  @Test
  void truncatedAssetReportsTheResourceAndReadOperation() throws IOException {
    byte[] complete;
    try (InputStream input = WorldMapAssetLoaderTest.class.getResourceAsStream(
        "/maps/strategic_world.map")) {
      complete = input.readAllBytes();
    }
    byte[] truncated = Arrays.copyOf(complete, complete.length / 2);

    try {
      WorldMapAssetLoader.load(new ByteArrayInputStream(truncated), "truncated-test.map");
      fail("Expected the truncated asset to fail");
    } catch (IllegalStateException failure) {
      assertTrue(failure.getMessage().contains("truncated-test.map"));
      assertTrue(failure.getMessage().contains("prepared render levels"));
      assertInstanceOf(EOFException.class, failure.getCause());
    }
  }

  private static int triangleCount(PreparedMapGeometry.Level level) {
    int vertexCount = 0;
    for (int chunkIndex = 0; chunkIndex < level.chunkCount(); chunkIndex++) {
      vertexCount += level.chunk(chunkIndex).fillVertexCount();
    }
    return vertexCount / 3;
  }

  private static boolean chunkContainsRegion(PreparedMapGeometry.Chunk chunk, int regionIndex) {
    for (int fillIndex = 0; fillIndex < chunk.fillCount(); fillIndex++) {
      if (chunk.fill(fillIndex).regionIndex() == regionIndex) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> regionIds(LoadedWorldMap world) {
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < world.regionCount(); index++) {
      ids.add(world.region(index).regionId());
    }
    return ids;
  }

  private static Region region(LoadedWorldMap world, String regionId) {
    return world.region(regionIndex(world, regionId));
  }

  private static int regionIndex(LoadedWorldMap world, String regionId) {
    for (int index = 0; index < world.regionCount(); index++) {
      if (world.region(index).regionId().equals(regionId)) {
        return index;
      }
    }
    throw new AssertionError("Missing region " + regionId);
  }

  private static double ringArea(PreparedMapGeometry.Part part, int ringIndex) {
    double twiceArea = 0.0;
    for (int point = 1; point < part.ringPointCount(ringIndex); point++) {
      twiceArea += part.ringMapX(ringIndex, point - 1) * part.ringMapY(ringIndex, point)
          - part.ringMapX(ringIndex, point) * part.ringMapY(ringIndex, point - 1);
    }
    return twiceArea * 0.5;
  }

  private static double triangleAreaForRegion(
      PreparedMapGeometry.Level level, int regionIndex) {
    double area = 0.0;
    for (int chunkIndex = 0; chunkIndex < level.chunkCount(); chunkIndex++) {
      PreparedMapGeometry.Chunk chunk = level.chunk(chunkIndex);
      for (int fillIndex = 0; fillIndex < chunk.fillCount(); fillIndex++) {
        PreparedMapGeometry.Fill fill = chunk.fill(fillIndex);
        if (fill.regionIndex() == regionIndex) {
          area += triangleArea(fill);
        }
      }
    }
    return area;
  }

  private static double triangleArea(PreparedMapGeometry.Fill fill) {
    double area = 0.0;
    for (int vertex = 0; vertex < fill.vertexCount(); vertex += 3) {
      double ax = fill.mapX(vertex);
      double ay = fill.mapY(vertex);
      double bx = fill.mapX(vertex + 1);
      double by = fill.mapY(vertex + 1);
      double cx = fill.mapX(vertex + 2);
      double cy = fill.mapY(vertex + 2);
      area += Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) * 0.5;
    }
    return area;
  }

  private static double edgeWidth(PreparedMapGeometry.Fill fill, int first, int second) {
    return Math.abs(fill.mapX(first) - fill.mapX(second));
  }
}
