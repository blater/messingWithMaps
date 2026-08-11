package dn;

import dn.politics.PoliticalActor;
import dn.politics.PoliticalDependency;
import dn.politics.PoliticalSnapshot;
import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.feature.MapRank;
import dn.strategicmap.feature.PlaceFeature;
import dn.strategicmap.geometry.MapPolygon;
import dn.strategicmap.geometry.MapRing;
import dn.strategicmap.geometry.Point;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import dn.strategicmap.render.LabelCandidate;
import dn.strategicmap.render.LabelCategory;
import dn.strategicmap.render.RegionStyle;
import dn.strategicmap.render.RegionStyleSnapshot;
import dn.strategicmap.render.ZoomBand;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The only code that joins political facts to strategic-map presentation contracts. */
public final class PoliticalMapPresentationAdapter {
  private static final String ACTOR_COLOURS_RESOURCE = "/presentation/actor-colours.tsv";
  private static final RegionStyle NEUTRAL = new RegionStyle(0.78f, 0.74f, 0.62f, 1.0f);

  private final Map<String, PoliticalLabelPlacement> labelPlacements;
  private final Map<String, RegionStyle> actorColours;

  public PoliticalMapPresentationAdapter(
      Map<String, PoliticalLabelPlacement> labelPlacements) {
    this.labelPlacements = Map.copyOf(labelPlacements);
    actorColours = loadActorColours();
  }

  /**
   * Load-time composition. The capital lookup is O(actors * places): currently 59 * 249,
   * bounded and simpler than maintaining another index outside the runtime render path.
   */
  public PoliticalMapPresentation compose(
      PoliticalSnapshot politics, LoadedWorldMap worldMap, VisiblePlaceQuery places) {
    Map<String, PoliticalActor> actorsById = new HashMap<>();
    Map<String, RegionStyle> stylesByActor = new HashMap<>();
    for (int index = 0; index < politics.actorCount(); index++) {
      PoliticalActor actor = politics.actor(index);
      actorsById.put(actor.actorId(), actor);
      stylesByActor.put(actor.actorId(), styleFor(actor));
    }
    for (int index = 0; index < politics.actorCount(); index++) {
      PoliticalActor actor = politics.actor(index);
      politics.dependencyFor(actor.actorId()).ifPresent(dependency ->
          stylesByActor.put(
              actor.actorId(), stylesByActor.get(dependency.suzerainActorId())));
    }

    Map<String, RegionStyle> stylesByRegion = new HashMap<>();
    Map<String, String> tooltipAdditions = new HashMap<>();
    Map<String, Boolean> visibleActors = new HashMap<>();
    for (int index = 0; index < worldMap.regionCount(); index++) {
      String regionId = worldMap.region(index).regionId();
      String actorId = politics.controllerId(regionId);
      PoliticalActor actor = actorsById.get(actorId);
      if (actor == null) {
        continue;
      }
      stylesByRegion.put(regionId, stylesByActor.get(actorId));
      tooltipAdditions.put(regionId, controlDescription(actor, politics, actorsById));
      visibleActors.put(actorId, Boolean.TRUE);
    }

    List<LabelCandidate> labels = new ArrayList<>();
    Set<String> capitalPlaceIds = new HashSet<>();
    for (int index = 0; index < politics.actorCount(); index++) {
      PoliticalActor actor = politics.actor(index);
      if (!visibleActors.containsKey(actor.actorId())) {
        continue;
      }
      PoliticalLabelPlacement placement = labelPlacements.get(actor.actorId());
      int capitalPlaceIndex = capitalPlaceIndex(actor.capitalRegionId(), places);
      Point anchor = placement == null
          ? capitalAnchor(actor.capitalRegionId(), worldMap, places, capitalPlaceIndex)
          : placement.anchor();
      if (capitalPlaceIndex >= 0) {
        capitalPlaceIds.add(places.place(capitalPlaceIndex).placeId());
      }
      boolean playable = actor.playable();
      String labelText = politics.dependencyFor(actor.actorId())
          .map(dependency -> dependentLabel(actor, dependency, actorsById))
          .orElse(actor.displayName());
      labels.add(new LabelCandidate(
          "political." + actor.actorId(),
          labelText,
          anchor,
          playable ? LabelCategory.PRIMARY_GROUP : LabelCategory.SECONDARY_GROUP,
          placement == null
              ? (playable ? ZoomBand.GRAND : ZoomBand.NATIONAL)
              : placement.minimumBand(),
          placement == null ? 0.0f : placement.rotationDegrees()));
    }
    return new PoliticalMapPresentation(
        new RegionStyleSnapshot(2L, NEUTRAL, stylesByRegion),
        labels,
        Set.copyOf(capitalPlaceIds),
        tooltipAdditions);
  }

  private RegionStyle styleFor(PoliticalActor actor) {
    RegionStyle style = actorColours.get(actor.mapColourId());
    if (style == null) {
      throw new IllegalStateException(
          "Actor colour not found: " + actor.mapColourId() + " for " + actor.actorId());
    }
    return style;
  }

  private static Map<String, RegionStyle> loadActorColours() {
    InputStream input = PoliticalMapPresentationAdapter.class
        .getResourceAsStream(ACTOR_COLOURS_RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Actor colours not found: " + ACTOR_COLOURS_RESOURCE);
    }
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      Map<String, RegionStyle> colours = new HashMap<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        colours.put(fields[0], colour(fields[1]));
      }
      return colours;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load actor colours from " + ACTOR_COLOURS_RESOURCE, exception);
    }
  }

  private static RegionStyle colour(String rgb) {
    int value = Integer.parseInt(rgb, 16);
    return new RegionStyle(
        ((value >> 16) & 0xff) / 255.0f,
        ((value >> 8) & 0xff) / 255.0f,
        (value & 0xff) / 255.0f,
        1.0f);
  }

  private static String controlDescription(
      PoliticalActor actor,
      PoliticalSnapshot politics,
      Map<String, PoliticalActor> actorsById) {
    return politics.dependencyFor(actor.actorId())
        .map(dependency -> actor.displayName()
            + " autonomous control - "
            + dependencyDescription(dependency, actorsById)
            + " - "
            + politics.representedDate())
        .orElseGet(() ->
            actor.displayName() + " control - " + politics.representedDate());
  }

  private static String dependentLabel(
      PoliticalActor actor,
      PoliticalDependency dependency,
      Map<String, PoliticalActor> actorsById) {
    return actor.displayName() + " (" + dependencyDescription(dependency, actorsById) + ")";
  }

  private static String dependencyDescription(
      PoliticalDependency dependency, Map<String, PoliticalActor> actorsById) {
    PoliticalActor suzerain = actorsById.get(dependency.suzerainActorId());
    return switch (dependency.kind()) {
      case VASSAL -> "vassal of " + suzerain.displayName();
    };
  }

  /** Load/composition-time O(places + capital region geometry), never a frame loop. */
  private static Point capitalAnchor(
      String capitalRegionId,
      LoadedWorldMap worldMap,
      VisiblePlaceQuery places,
      int capitalPlaceIndex) {
    if (capitalPlaceIndex >= 0) {
      return places.anchor(capitalPlaceIndex);
    }
    for (int index = 0; index < worldMap.regionCount(); index++) {
      if (worldMap.region(index).regionId().equals(capitalRegionId)) {
        return largestRingCentre(worldMap.region(index).geometryParts());
      }
    }
    return new Point(0.0, 0.0);
  }

  private static int capitalPlaceIndex(String capitalRegionId, VisiblePlaceQuery places) {
    int selectedPlace = -1;
    MapRank selectedRank = null;
    for (int index = 0; index < places.placeCount(); index++) {
      PlaceFeature place = places.place(index);
      if (place.regionId().equals(capitalRegionId)
          && (selectedRank == null || place.rank().ordinal() < selectedRank.ordinal())) {
        selectedPlace = index;
        selectedRank = place.rank();
      }
    }
    if (selectedPlace >= 0) {
      return selectedPlace;
    }
    return -1;
  }

  private static Point largestRingCentre(List<MapPolygon> polygons) {
    MapRing largest = polygons.getFirst().exterior();
    for (MapPolygon polygon : polygons) {
      if (polygon.exterior().points().size() > largest.points().size()) {
        largest = polygon.exterior();
      }
    }
    double west = Double.POSITIVE_INFINITY;
    double east = Double.NEGATIVE_INFINITY;
    double south = Double.POSITIVE_INFINITY;
    double north = Double.NEGATIVE_INFINITY;
    for (Point point : largest.points()) {
      west = Math.min(west, point.x());
      east = Math.max(east, point.x());
      south = Math.min(south, point.y());
      north = Math.max(north, point.y());
    }
    return new Point((west + east) * 0.5, (south + north) * 0.5);
  }
}
