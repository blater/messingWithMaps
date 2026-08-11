package dn.strategicmap.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import dn.strategicmap.camera.MapCamera;
import dn.strategicmap.feature.CityStanding;
import dn.strategicmap.feature.PlaceFeature;
import dn.strategicmap.feature.PlaceKind;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.Point;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Screen-sized city and port symbols attached to canonical map anchors. */
final class MapPlaceRenderer {
  private static final Color CITY = new Color(0.95f, 0.84f, 0.56f, 1.0f);
  private static final Color PORT = new Color(0.72f, 0.88f, 0.94f, 1.0f);
  private static final Color OUTLINE = new Color(0.04f, 0.05f, 0.06f, 1.0f);
  private static final Color HOVER = new Color(1.0f, 0.78f, 0.24f, 1.0f);

  private final VisiblePlaceQuery query;
  private final LabelOrientationIndex orientations = LabelOrientationIndex.loadDefault();
  private final OrthographicCamera screenCamera = new OrthographicCamera();
  private final ShapeRenderer shapes = new ShapeRenderer();
  private List<LabelCandidate> labelCandidates;
  private Set<String> capitalPlaceIds = Set.of();
  private String hoveredPlaceId;
  private String selectedPlaceId;
  private int visibleCount;

  MapPlaceRenderer(VisiblePlaceQuery query) {
    this.query = query;
    rebuildLabelCandidates();
  }

  void setCapitalPlaceIds(Set<String> capitalPlaceIds) {
    this.capitalPlaceIds = capitalPlaceIds;
    rebuildLabelCandidates();
  }

  private void rebuildLabelCandidates() {
    labelCandidates = new ArrayList<>(query.placeCount());
    for (int index = 0; index < query.placeCount(); index++) {
      PlaceFeature place = query.place(index);
      boolean port = place.kinds().contains(PlaceKind.PORT);
      labelCandidates.add(new LabelCandidate(
          place.placeId(),
          place.name(),
          query.anchor(index),
          labelCategory(place, port),
          MapPresentationPolicy.placeMinimumBand(place.rank()),
          orientations.rotationDegrees(place.placeId())));
    }
  }

  private LabelCategory labelCategory(PlaceFeature place, boolean port) {
    if (!capitalPlaceIds.contains(place.placeId())) {
      return port ? LabelCategory.PORT : LabelCategory.CITY;
    }
    return place.cityStanding() == CityStanding.MINOR_CAPITAL
        ? LabelCategory.MINOR_CAPITAL
        : LabelCategory.CAPITAL;
  }

  List<LabelCandidate> labelCandidates() {
    return labelCandidates;
  }

  void setInteraction(String hoveredPlaceId, String selectedPlaceId) {
    this.hoveredPlaceId = hoveredPlaceId;
    this.selectedPlaceId = selectedPlaceId;
  }

  void render(MapCamera camera, ZoomBand band, int firstCopy, int lastCopy) {
    int width = camera.viewportWidthPixels();
    int height = camera.viewportHeightPixels();
    screenCamera.setToOrtho(false, width, height);
    visibleCount = 0;
    shapes.setProjectionMatrix(screenCamera.combined);
    shapes.begin(ShapeRenderer.ShapeType.Filled);
    MapBounds world = camera.worldBounds();
    double visibleWest = camera.centerMapX() - camera.visibleMapWidth() * 0.5;
    double visibleEast = visibleWest + camera.visibleMapWidth();
    double visibleSouth = camera.centerMapY() - camera.visibleMapHeight() * 0.5;
    double visibleNorth = visibleSouth + camera.visibleMapHeight();
    for (int copy = firstCopy; copy <= lastCopy; copy++) {
      double offset = copy * world.width();
      double west = Math.max(world.west(), visibleWest - offset);
      double east = Math.min(world.east(), visibleEast - offset);
      double south = Math.max(world.south(), visibleSouth);
      double north = Math.min(world.north(), visibleNorth);
      int count = query.queryVisible(west, south, east, north);
      for (int result = 0; result < count; result++) {
        int placeIndex = query.visibleIndex(result);
        PlaceFeature place = query.place(placeIndex);
        if (!band.includes(MapPresentationPolicy.placeMinimumBand(place.rank()))) {
          continue;
        }
        Point anchor = query.anchor(placeIndex);
        float x = (float) camera.viewportXForMapX(anchor.x() + copy * world.width());
        float y = (float) (height - camera.viewportYForMapY(anchor.y()));
        boolean active = place.placeId().equals(hoveredPlaceId)
            || place.placeId().equals(selectedPlaceId);
        boolean port = place.kinds().contains(PlaceKind.PORT);
        float outerRadius = active ? 6.0f : 4.5f;
        float innerRadius = active ? 5.0f : 3.5f;
        shapes.setColor(OUTLINE);
        drawSymbol(shapes, port, x, y, outerRadius);
        shapes.setColor(active ? HOVER : port ? PORT : CITY);
        drawSymbol(shapes, port, x, y, innerRadius);
        visibleCount++;
      }
    }
    shapes.end();
  }

  int visibleCount() {
    return visibleCount;
  }

  void dispose() {
    shapes.dispose();
  }

  private static void drawSymbol(
      ShapeRenderer shapes, boolean port, float x, float y, float radius) {
    if (port) {
      shapes.rect(x - radius, y - radius, radius * 2.0f, radius * 2.0f);
    } else {
      shapes.circle(x, y, radius, 12);
    }
  }
}
