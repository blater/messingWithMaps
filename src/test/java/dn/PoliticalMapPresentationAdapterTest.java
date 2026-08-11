package dn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.politics.PoliticalSnapshotLoader;
import dn.strategicmap.data.PlaceFeatureLoader;
import dn.strategicmap.data.WorldMapAssetLoader;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import dn.strategicmap.render.LabelCandidate;
import dn.strategicmap.render.LabelCategory;
import dn.strategicmap.render.RegionStyle;
import dn.strategicmap.render.ZoomBand;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoliticalMapPresentationAdapterTest {
  @Test
  void translatesPoliticsIntoGenericMapPresentationNearTheCapital() {
    var world = WorldMapAssetLoader.loadDefault();
    var places = new VisiblePlaceQuery(
        PlaceFeatureLoader.loadDefault(), new FlatMapProjection());

    var presentation = new PoliticalMapPresentationAdapter(
        PoliticalLabelPlacementLoader.loadDefault()).compose(
            PoliticalSnapshotLoader.loadDefault(), world, places);

    RegionStyle neutral = presentation.regionStyles().neutralStyle();
    assertEquals(
        new RegionStyle(0xc7 / 255.0f, 0x97 / 255.0f, 0x8c / 255.0f, 1.0f),
        presentation.regionStyles().styleFor("gbr-england-wales"));
    assertEquals(
        presentation.regionStyles().styleFor("deu-northeast"),
        presentation.regionStyles().styleFor("rus-kaliningrad"));
    assertEquals(
        presentation.regionStyles().styleFor("deu-northeast"),
        presentation.regionStyles().styleFor("fra-alsace-lorraine"));
    assertEquals(
        presentation.regionStyles().styleFor("deu-northeast"),
        presentation.regionStyles().styleFor("pol-west"));
    assertEquals(
        presentation.regionStyles().styleFor("rus-central"),
        presentation.regionStyles().styleFor("pol-east"));
    assertEquals(
        presentation.regionStyles().styleFor("AUT"),
        presentation.regionStyles().styleFor("pol-south"));
    assertEquals(
        presentation.regionStyles().styleFor("tur-marmara-west"),
        presentation.regionStyles().styleFor("BGR"));
    for (int index = 0; index < world.regionCount(); index++) {
      assertNotEquals(
          neutral,
          presentation.regionStyles().styleFor(world.region(index).regionId()),
          world.region(index).regionId());
    }
    Map<String, String> playableNames = Map.of(
        "spanish-empire", "The Spanish Empire",
        "british-empire", "The British Empire",
        "french-empire", "The French Republic",
        "german-empire", "The German Empire",
        "austria-hungary", "The Austro-Hungarian Empire",
        "qing-empire", "Qing Dynasty",
        "japanese-empire", "Empire of Japan",
        "united-states", "The United States of America",
        "russian-empire", "The Russian Empire",
        "italian-empire", "The Kingdom of Italy");
    for (Map.Entry<String, String> entry : playableNames.entrySet()) {
      LabelCandidate label = presentation.groupLabels().stream()
          .filter(candidate -> candidate.stableKey().equals("political." + entry.getKey()))
          .findFirst()
          .orElseThrow();
      assertEquals(entry.getValue(), label.text());
      assertEquals(LabelCategory.PRIMARY_GROUP, label.category());
    }
    var britishLabel = presentation.groupLabels().stream()
        .filter(label -> label.stableKey().equals("political.british-empire"))
        .findFirst()
        .orElseThrow();
    var britishPlacement = new FlatMapProjection().project(54.0, -3.0);
    assertEquals(britishPlacement.x(), britishLabel.anchor().x(), 1.0e-6);
    assertEquals(britishPlacement.y(), britishLabel.anchor().y(), 1.0e-6);
    assertEquals(ZoomBand.GRAND, britishLabel.minimumBand());
    assertTrue(presentation.capitalPlaceIds().contains("place.london"));
    var ottomanLabel = presentation.groupLabels().stream()
        .filter(label -> label.stableKey().equals("political.ottoman-empire"))
        .findFirst()
        .orElseThrow();
    assertEquals(LabelCategory.SECONDARY_GROUP, ottomanLabel.category());
    assertEquals(ZoomBand.NATIONAL, ottomanLabel.minimumBand());
    assertEquals(
        ZoomBand.REGIONAL,
        presentation.groupLabels().stream()
            .filter(label -> label.stableKey().equals("political.italian-empire"))
            .findFirst()
            .orElseThrow()
            .minimumBand());
    var bulgarianLabel = presentation.groupLabels().stream()
        .filter(label -> label.stableKey().equals("political.bulgaria"))
        .findFirst()
        .orElseThrow();
    assertEquals("Bulgaria (vassal of Ottoman Empire)", bulgarianLabel.text());
    assertTrue(presentation.tooltipAdditions()
        .get("gbr-england-wales").contains("circa 1895"));
    assertEquals(
        "Bulgaria autonomous control - vassal of Ottoman Empire - circa 1895",
        presentation.tooltipAdditions().get("BGR"));
  }
}
