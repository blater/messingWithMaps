package dn.strategicmap.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import dn.strategicmap.camera.MapCamera;
import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.feature.GeographicLabel;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.PreparedLabelSelection;

/** Owns strategic-map draw order while the region renderer owns optimised GPU geometry. */
public final class StrategicMapRenderer {
  private static final Color OCEAN = new Color(0.64f, 0.72f, 0.73f, 1.0f);

  private final LoadedWorldMap worldMap;
  private final OrthographicCamera worldCamera = new OrthographicCamera();
  private final ShapeRenderer oceanRenderer = new ShapeRenderer();
  private final RegionGeometryRenderer regionRenderer;
  private final MapLabelRenderer labelRenderer;
  private final MapPlaceRenderer placeRenderer;
  private final ZoomBandPolicy zoomBandPolicy = new ZoomBandPolicy();
  private final boolean outlinesOnly;
  private ZoomBand zoomBand = ZoomBand.WORLD;

  public StrategicMapRenderer(
      LoadedWorldMap worldMap,
      RegionStyleSnapshot styles,
      List<GeographicLabel> geographicLabels,
      VisiblePlaceQuery placeQuery) {
    this(worldMap, styles, geographicLabels, placeQuery, false, false);
  }

  public StrategicMapRenderer(
      LoadedWorldMap worldMap,
      RegionStyleSnapshot styles,
      List<GeographicLabel> geographicLabels,
      VisiblePlaceQuery placeQuery,
      boolean labelEditMode) {
    this(worldMap, styles, geographicLabels, placeQuery, labelEditMode, false);
  }

  public StrategicMapRenderer(
      LoadedWorldMap worldMap,
      RegionStyleSnapshot styles,
      List<GeographicLabel> geographicLabels,
      VisiblePlaceQuery placeQuery,
      boolean labelEditMode,
      boolean outlinesOnly) {
    this.worldMap = worldMap;
    this.outlinesOnly = outlinesOnly;
    regionRenderer = new RegionGeometryRenderer(worldMap, styles, outlinesOnly);
    labelRenderer = new MapLabelRenderer(
        geographicLabels, new FlatMapProjection(), labelEditMode);
    placeRenderer = new MapPlaceRenderer(placeQuery);
    labelRenderer.setPlaceCandidates(placeRenderer.labelCandidates());
  }

  public void setStyles(RegionStyleSnapshot styles) {
    regionRenderer.setStyles(styles);
  }

  public void setInteraction(String hoveredRegionId, String selectedRegionId) {
    regionRenderer.setInteraction(hoveredRegionId, selectedRegionId);
  }

  public void setPlaceInteraction(String hoveredPlaceId, String selectedPlaceId) {
    placeRenderer.setInteraction(hoveredPlaceId, selectedPlaceId);
  }

  /** Applies generic place-label emphasis supplied by the application composition layer. */
  public void setCapitalPlaceIds(Set<String> capitalPlaceIds) {
    placeRenderer.setCapitalPlaceIds(capitalPlaceIds);
    labelRenderer.setPlaceCandidates(placeRenderer.labelCandidates());
  }

  public void setGroupLabels(List<LabelCandidate> groupLabels) {
    labelRenderer.setGroupCandidates(groupLabels);
  }

  public void setLabelLayoutOverrides(Map<String, LabelLayoutOverride> overrides) {
    labelRenderer.setLayoutOverrides(overrides);
  }

  public PreparedLabelSelection labelAt(int viewportX, int viewportY) {
    return labelRenderer.labelAt(viewportX, viewportY);
  }

  public void render(MapCamera mapCamera) {
    zoomBand = zoomBandPolicy.update(mapCamera.zoom());
    updateCamera(mapCamera);
    MapBounds worldBounds = worldMap.worldBounds();
    double visibleWest = mapCamera.centerMapX() - mapCamera.visibleMapWidth() * 0.5;
    double visibleEast = visibleWest + mapCamera.visibleMapWidth();
    int firstCopy = (int) Math.ceil((visibleWest - worldBounds.east()) / worldBounds.width());
    int lastCopy = (int) Math.floor((visibleEast - worldBounds.west()) / worldBounds.width());
    if (lastCopy - firstCopy + 1 > 3) {
      int middleCopy = (int) Math.round(
          (mapCamera.centerMapX() - worldBounds.centerX()) / worldBounds.width());
      firstCopy = middleCopy - 1;
      lastCopy = middleCopy + 1;
    }

    oceanRenderer.setProjectionMatrix(worldCamera.combined);
    oceanRenderer.begin(ShapeRenderer.ShapeType.Filled);
    oceanRenderer.setColor(OCEAN);
    for (int copy = firstCopy; copy <= lastCopy; copy++) {
      oceanRenderer.rect(
          (float) (worldBounds.west() + copy * worldBounds.width()),
          (float) worldBounds.south(),
          (float) worldBounds.width(),
          (float) worldBounds.height());
    }
    oceanRenderer.end();

    regionRenderer.render(worldCamera, mapCamera, firstCopy, lastCopy);
    if (outlinesOnly) {
      return;
    }
    placeRenderer.render(mapCamera, zoomBand, firstCopy, lastCopy);
    labelRenderer.render(mapCamera, zoomBand, firstCopy, lastCopy);
  }

  public ZoomBand zoomBand() {
    return zoomBand;
  }

  public int visibleLabelCount() {
    return labelRenderer.visibleCount();
  }

  public int visiblePlaceCount() {
    return placeRenderer.visibleCount();
  }

  public void dispose() {
    regionRenderer.dispose();
    placeRenderer.dispose();
    labelRenderer.dispose();
    oceanRenderer.dispose();
  }

  private void updateCamera(MapCamera mapCamera) {
    worldCamera.setToOrtho(
        false,
        (float) mapCamera.visibleMapWidth(),
        (float) mapCamera.visibleMapHeight());
    worldCamera.position.set((float) mapCamera.centerMapX(), (float) mapCamera.centerMapY(), 0.0f);
    worldCamera.update();
  }
}
