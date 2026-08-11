package dn.strategicmap.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.feature.CityStanding;
import dn.strategicmap.feature.PlaceKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceFeatureLoaderTest {
  @Test
  void loadsLatitudeFirstAndCombinedCityPortKind() {
    String source = "placeId\tname\tlatitude\tlongitude\tkinds\trank\tregionId\tsource"
        + "\tcityStanding\n"
        + "place.test\tTest\t51.5\t-0.1\tCITY,PORT\tGLOBAL\tregion.test\tsource"
        + "\tMAJOR_CAPITAL\n";

    var places = PlaceFeatureLoader.load(
        new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "fixture");

    assertEquals(51.5, places.getFirst().latitudeDegrees());
    assertEquals(-0.1, places.getFirst().longitudeDegrees());
    assertTrue(places.getFirst().kinds().contains(PlaceKind.CITY));
    assertTrue(places.getFirst().kinds().contains(PlaceKind.PORT));
    assertEquals("MAJOR_CAPITAL", places.getFirst().cityStanding().name());
  }

  @Test
  void defaultCatalogueHasBroadStrategicCoverage() {
    var places = PlaceFeatureLoader.loadDefault();

    assertTrue(places.size() >= 180);
    assertTrue(places.stream().anyMatch(place -> place.name().equals("London")));
    assertTrue(places.stream().anyMatch(place -> place.name().equals("Calcutta")));
    assertTrue(places.stream().anyMatch(place -> place.name().equals("Cape Town")));
    assertTrue(places.stream().anyMatch(place -> place.placeId().equals("place.banjul")
        && place.name().equals("Bathurst")));
    assertTrue(places.stream().anyMatch(place -> place.placeId().equals("place.toamasina")
        && place.name().equals("Tamatave")));
    assertTrue(places.stream().anyMatch(place -> place.placeId().equals("place.london")
        && place.cityStanding() == CityStanding.IMPERIAL_CAPITAL));
    assertTrue(places.stream().anyMatch(place -> place.placeId().equals("place.washington")
        && place.cityStanding() == CityStanding.MAJOR_CAPITAL));
    assertEquals(3, places.stream().filter(place -> Set.of(
        "place.monrovia", "place.bloemfontein", "place.pretoria").contains(place.placeId()))
        .count());
    assertTrue(places.stream().filter(place -> Set.of(
        "place.monrovia", "place.bloemfontein", "place.pretoria").contains(place.placeId()))
        .allMatch(place -> place.cityStanding() == CityStanding.MINOR_CAPITAL));
    assertTrue(places.stream().noneMatch(place -> Set.of(
        "place.abidjan", "place.honiara", "place.pointe-noire", "place.tsingtao")
        .contains(place.placeId())));
  }
}
