package dn.strategicmap.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dn.strategicmap.feature.MapRank;
import dn.strategicmap.feature.CityStanding;
import dn.strategicmap.feature.PlaceFeature;
import dn.strategicmap.feature.PlaceKind;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.Point;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VisiblePlaceQueryTest {
  @Test
  void findsOnlyPlacesInTheViewportAndNearestHitRadius() {
    var places = List.of(
        new PlaceFeature(
            "london", "London", 51.5, -0.1, Set.of(PlaceKind.CITY),
            MapRank.GLOBAL, "gbr-england-wales", "test", CityStanding.IMPERIAL_CAPITAL),
        new PlaceFeature(
            "tokyo", "Tokyo", 35.7, 139.7, Set.of(PlaceKind.CITY, PlaceKind.PORT),
            MapRank.GLOBAL, "jpn-central", "test", CityStanding.IMPERIAL_CAPITAL));
    FlatMapProjection projection = new FlatMapProjection();
    VisiblePlaceQuery query = new VisiblePlaceQuery(places, projection);
    Point london = projection.project(51.5, -0.1);
    Point nearLondon = projection.project(51.49, -0.12);
    Point farAway = projection.project(20.0, 20.0);

    assertEquals(1, query.queryVisible(
        london.x() - 10.0, london.y() - 7.0, london.x() + 10.0, london.y() + 8.0));
    assertEquals(0, query.visibleIndex(0));
    assertEquals(0, query.nearest(nearLondon.x(), nearLondon.y(), 0.1));
    assertEquals(-1, query.nearest(farAway.x(), farAway.y(), 0.1));
  }
}
