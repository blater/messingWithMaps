package dn.strategicmap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import dn.editor.map.LabelEditSession;
import dn.editor.map.LabelEditorPanel;
import dn.strategicmap.camera.MapCamera;
import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.data.GeographicLabelLoader;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.geometry.Point;
import dn.strategicmap.geography.Region;
import dn.strategicmap.interaction.MapInteraction;
import dn.strategicmap.interaction.VisiblePlaceQuery;
import dn.strategicmap.render.LabelCandidate;
import dn.strategicmap.render.RegionStyleSnapshot;
import dn.strategicmap.render.StrategicMapRenderer;
import dn.strategicmap.render.ZoomBand;
import dn.util.FrameMetrics;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategic-map lifecycle, input, and screen-space HUD composition.
 */
public final class StrategicMapScreen implements Screen {
  private static final Color OUTSIDE_MAP = new Color(0.035f, 0.055f, 0.075f, 1.0f);
  private static final Color HUD = new Color(0.025f, 0.035f, 0.05f, 0.88f);
  private static final Color TOOLTIP = new Color(0.025f, 0.035f, 0.05f, 0.96f);
  private static final int PAN_MOUSE_BUTTON = Input.Buttons.RIGHT;
  private static final float TOOLTIP_DELAY_SECONDS = 0.35f;
  private static final float TOOLTIP_HEIGHT = 80.0f;

  private final MapCamera mapCamera;
  private final LoadedWorldMap worldMap;
  private final MapInteraction mapInteraction;
  private final VisiblePlaceQuery placeQuery;
  private final OrthographicCamera screenCamera = new OrthographicCamera();
  private final ShapeRenderer hudShapes = new ShapeRenderer();
  private final SpriteBatch sprites = new SpriteBatch();
  private final BitmapFont font = new BitmapFont();
  private final GlyphLayout tooltipLayout = new GlyphLayout();
  private final GLProfiler glProfiler;
  private final FrameMetrics frameMetrics;
  private final StrategicMapRenderer mapRenderer;
  private final LabelEditorPanel labelEditor;
  private final Map<String, String> tooltipAdditions;
  private final boolean outlinesOnly;
  private int screenWidth;
  private int screenHeight;
  private boolean dragging;
  private int lastDragScreenX;
  private int lastDragScreenY;
  private float hoveredSeconds;
  private String tooltipTitle;
  private String tooltipRegionId;
  private String tooltipSource;
  private String tooltipAddition;
  private float tooltipWidth;
  private ZoomBand readoutBand;
  private int readoutLabels = -1;
  private int readoutPlaces = -1;
  private String mapReadout = "";
  private int pointerViewportX;
  private int pointerViewportY;

  public StrategicMapScreen(
      long launchStartedNanos,
      boolean diagnosticsEnabled,
      LoadedWorldMap worldMap,
      VisiblePlaceQuery placeQuery,
      RegionStyleSnapshot regionStyles,
      List<LabelCandidate> groupLabels,
      Set<String> capitalPlaceIds,
      Map<String, String> tooltipAdditions,
      LabelEditSession labelEditSession,
      boolean outlinesOnly) {
    screenWidth = Math.max(1, Gdx.graphics.getWidth());
    screenHeight = Math.max(1, Gdx.graphics.getHeight());
    mapCamera = new MapCamera(new FlatMapProjection(), screenWidth, screenHeight);
    this.worldMap = worldMap;
    this.placeQuery = placeQuery;
    this.tooltipAdditions = tooltipAdditions;
    this.outlinesOnly = outlinesOnly;
    mapInteraction = new MapInteraction(worldMap, placeQuery);
    mapRenderer = new StrategicMapRenderer(
        worldMap,
        regionStyles,
        GeographicLabelLoader.loadDefault(),
        placeQuery,
        labelEditSession.enabled(),
        outlinesOnly);
    mapRenderer.setCapitalPlaceIds(capitalPlaceIds);
    mapRenderer.setGroupLabels(groupLabels);
    if (labelEditSession.enabled()) {
      mapRenderer.setLabelLayoutOverrides(labelEditSession.overrides());
    }
    labelEditor = new LabelEditorPanel(
        labelEditSession,
        () -> mapRenderer.setLabelLayoutOverrides(labelEditSession.overrides()));
    if (diagnosticsEnabled) {
      glProfiler = new GLProfiler(Gdx.graphics);
      glProfiler.enable();
      frameMetrics = new FrameMetrics(glProfiler, launchStartedNanos);
    } else {
      glProfiler = null;
      frameMetrics = null;
    }
    font.setColor(Color.WHITE);

    Gdx.input.setInputProcessor(new InputAdapter() {
      @Override
      public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.LEFT) {
          if (labelEditor.enabled()) {
            if (labelEditor.handleClick(screenX, screenY, screenWidth, screenHeight)) {
              return true;
            }
            var target = mapRenderer.labelAt(screenX, screenY);
            if (target != null) {
              labelEditor.select(target);
              return true;
            }
          }
          updateHoverAt(screenX, screenY);
          if (mapInteraction.selectHovered()) {
            updateInteractionRendering();
          }
          return true;
        }
        if (button != PAN_MOUSE_BUTTON) {
          return false;
        }
        dragging = true;
        lastDragScreenX = screenX;
        lastDragScreenY = screenY;
        return true;
      }

      @Override
      public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!dragging) {
          return false;
        }
        mapCamera.dragByPixels(screenX - lastDragScreenX, screenY - lastDragScreenY);
        lastDragScreenX = screenX;
        lastDragScreenY = screenY;
        updateHoverAt(screenX, screenY);
        return true;
      }

      @Override
      public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button != PAN_MOUSE_BUTTON) {
          return false;
        }
        dragging = false;
        return true;
      }

      @Override
      public boolean scrolled(float amountX, float amountY) {
        mapCamera.zoomAt(new Point(Gdx.input.getX(), Gdx.input.getY()), amountY);
        updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
        return true;
      }

      @Override
      public boolean mouseMoved(int screenX, int screenY) {
        updateHoverAt(screenX, screenY);
        return true;
      }

      @Override
      public boolean keyDown(int keycode) {
        if (isResetKey(keycode)) {
          mapCamera.resetToFitWorld();
          updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
          return true;
        }
        return false;
      }
    });
    updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
  }

  @Override
  public void render(float deltaSeconds) {
    double east = eastWestScroll();
    double north = northSouthScroll();
    if (east != 0.0 || north != 0.0) {
      mapCamera.panFromKeyboard(east, north, Math.min(deltaSeconds, 0.1f));
      updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
    }
    if (mapInteraction.hoveredRegionId() != null || mapInteraction.hoveredPlaceId() != null) {
      hoveredSeconds += deltaSeconds;
    }

    if (frameMetrics != null) {
      frameMetrics.beginFrame();
    }
    Gdx.gl.glClearColor(OUTSIDE_MAP.r, OUTSIDE_MAP.g, OUTSIDE_MAP.b, OUTSIDE_MAP.a);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

    screenCamera.setToOrtho(false, screenWidth, screenHeight);
    mapRenderer.render(mapCamera);
    updateMapReadout();
    drawHud();
    if (frameMetrics != null) {
      frameMetrics.endFrame(deltaSeconds);
    }
  }

  private void drawHud() {
    boolean tooltipVisible = !outlinesOnly && !labelEditor.enabled()
        && tooltipTitle != null && hoveredSeconds >= TOOLTIP_DELAY_SECONDS;
    float visibleTooltipWidth = Math.min(tooltipWidth, Math.max(1.0f, screenWidth - 16.0f));
    float tooltipX = tooltipVisible ? tooltipX(visibleTooltipWidth) : 0.0f;
    float tooltipBottom = tooltipVisible ? tooltipBottom() : 0.0f;

    hudShapes.setProjectionMatrix(screenCamera.combined);
    hudShapes.begin(ShapeRenderer.ShapeType.Filled);
    hudShapes.setColor(HUD);
    hudShapes.rect(10.0f, screenHeight - 66.0f, 890.0f, 54.0f);
    if (tooltipVisible) {
      hudShapes.setColor(TOOLTIP);
      hudShapes.rect(tooltipX, tooltipBottom, visibleTooltipWidth, TOOLTIP_HEIGHT);
    }
    if (labelEditor.enabled()) {
      labelEditor.drawShapes(hudShapes, screenWidth);
    }
    hudShapes.end();

    sprites.setProjectionMatrix(screenCamera.combined);
    sprites.begin();
    font.draw(sprites, "The World (1880)", 24.0f, screenHeight - 30.0f);
    font.draw(
        sprites,
        "Right-drag pan   Wheel zoom   WASD/arrows pan   Home/R reset",
        24.0f,
        screenHeight - 51.0f);
    font.draw(
        sprites,
        mapReadout,
        564.0f,
        screenHeight - 30.0f);
    if (tooltipVisible) {
      font.draw(sprites, tooltipTitle, tooltipX + 8.0f, tooltipBottom + 68.0f);
      font.draw(sprites, tooltipRegionId, tooltipX + 8.0f, tooltipBottom + 50.0f);
      if (tooltipAddition != null) {
        font.draw(sprites, tooltipAddition, tooltipX + 8.0f, tooltipBottom + 32.0f);
      }
      font.draw(sprites, tooltipSource, tooltipX + 8.0f, tooltipBottom + 15.0f);
    }
    if (labelEditor.enabled()) {
      labelEditor.drawText(sprites, font, screenWidth);
    }
    sprites.end();
  }

  private void updateHoverAt(int viewportX, int viewportY) {
    pointerViewportX = viewportX;
    pointerViewportY = viewportY;
    boolean changed = mapInteraction.updateHover(
        mapCamera.canonicalMapXAtViewportX(viewportX),
        mapCamera.mapYAtViewportY(viewportY),
        mapCamera.mapUnitsPerPixel() * 10.0);
    if (!changed) {
      return;
    }
    hoveredSeconds = 0.0f;
    updateTooltipContent();
    updateInteractionRendering();
  }

  private void updateInteractionRendering() {
    mapRenderer.setInteraction(
        mapInteraction.hoveredRegionId(), mapInteraction.selectedRegionId());
    mapRenderer.setPlaceInteraction(
        mapInteraction.hoveredPlaceId(), mapInteraction.selectedPlaceId());
  }

  private void updateTooltipContent() {
    if (mapInteraction.hoveredPlaceId() != null) {
      var place = mapInteraction.hoveredPlace();
      tooltipTitle = place.name();
      tooltipRegionId = place.kinds().contains(dn.strategicmap.feature.PlaceKind.PORT)
          ? "Port - " + place.regionId()
          : "City - " + place.regionId();
      tooltipAddition = tooltipAdditions.get(place.regionId());
      tooltipSource = "Source: " + place.sourceDatasetId();
      tooltipWidth = Math.max(180.0f, textWidth(tooltipTitle) + 16.0f);
      tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipRegionId) + 16.0f);
      if (tooltipAddition != null) {
        tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipAddition) + 16.0f);
      }
      tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipSource) + 16.0f);
      return;
    }
    String hoveredRegionId = mapInteraction.hoveredRegionId();
    if (hoveredRegionId == null) {
      tooltipTitle = null;
      tooltipRegionId = null;
      tooltipSource = null;
      tooltipAddition = null;
      tooltipWidth = 0.0f;
      return;
    }
    Region hoveredRegion = region(hoveredRegionId);
    tooltipTitle = hoveredRegion.displayName();
    tooltipRegionId = "Region ID: " + hoveredRegion.regionId();
    tooltipAddition = tooltipAdditions.get(hoveredRegion.regionId());
    tooltipSource = "Source: " + hoveredRegion.sourceDatasetId();
    tooltipWidth = 180.0f;
    tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipTitle) + 16.0f);
    tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipRegionId) + 16.0f);
    if (tooltipAddition != null) {
      tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipAddition) + 16.0f);
    }
    tooltipWidth = Math.max(tooltipWidth, textWidth(tooltipSource) + 16.0f);
  }

  private Region region(String regionId) {
    for (int index = 0; index < worldMap.regionCount(); index++) {
      Region region = worldMap.region(index);
      if (region.regionId().equals(regionId)) {
        return region;
      }
    }
    throw new IllegalStateException("Map interaction returned unknown region " + regionId);
  }

  private float textWidth(String text) {
    tooltipLayout.setText(font, text);
    return tooltipLayout.width;
  }

  private float tooltipX(float visibleTooltipWidth) {
    float pointerX = pointerViewportX;
    float x = pointerX + 16.0f;
    if (x + visibleTooltipWidth > screenWidth - 8.0f) {
      x = pointerX - visibleTooltipWidth - 16.0f;
    }
    return clamp(x, 8.0f, Math.max(8.0f, screenWidth - visibleTooltipWidth - 8.0f));
  }

  private float tooltipBottom() {
    float pointerY = screenHeight - pointerViewportY;
    float bottom = pointerY - TOOLTIP_HEIGHT - 16.0f;
    if (bottom < 8.0f) {
      bottom = pointerY + 16.0f;
    }
    if (bottom + TOOLTIP_HEIGHT > screenHeight - 86.0f) {
      bottom = screenHeight - 86.0f - TOOLTIP_HEIGHT;
    }
    return clamp(bottom, 8.0f, Math.max(8.0f, screenHeight - TOOLTIP_HEIGHT - 8.0f));
  }

  private static float clamp(float value, float minimum, float maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private void updateMapReadout() {
    ZoomBand band = mapRenderer.zoomBand();
    int labels = mapRenderer.visibleLabelCount();
    int places = mapRenderer.visiblePlaceCount();
    if (band == readoutBand
        && labels == readoutLabels
        && places == readoutPlaces) {
      return;
    }
    readoutBand = band;
    readoutLabels = labels;
    readoutPlaces = places;
    mapReadout = "Zoom " + band + "   Labels " + labels
        + "   Places " + places;
  }

  private static double eastWestScroll() {
    double intent = 0.0;
    if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)) {
      intent -= 1.0;
    }
    if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)) {
      intent += 1.0;
    }
    return intent;
  }

  private static double northSouthScroll() {
    double intent = 0.0;
    if (Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)) {
      intent -= 1.0;
    }
    if (Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)) {
      intent += 1.0;
    }
    return intent;
  }

  private static boolean isResetKey(int keycode) {
    return keycode == Input.Keys.HOME || keycode == Input.Keys.R;
  }

  public String baselineReport() {
    return frameMetrics == null ? "Diagnostics disabled" : frameMetrics.report();
  }

  public void focusGeographic(
      double latitudeDegrees, double longitudeDegrees, double zoom) {
    mapCamera.focusGeographic(latitudeDegrees, longitudeDegrees, zoom);
    updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
  }

  @Override
  public void resize(int width, int height) {
    screenWidth = Math.max(1, width);
    screenHeight = Math.max(1, height);
    mapCamera.resize(screenWidth, screenHeight);
    updateHoverAt(Gdx.input.getX(), Gdx.input.getY());
  }

  @Override
  public void show() {
  }

  @Override
  public void pause() {
  }

  @Override
  public void resume() {
  }

  @Override
  public void hide() {
  }

  @Override
  public void dispose() {
    if (glProfiler != null) {
      glProfiler.disable();
    }
    mapRenderer.dispose();
    hudShapes.dispose();
    sprites.dispose();
    font.dispose();
  }
}
