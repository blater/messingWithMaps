package dn.strategicmap.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Matrix4;
import dn.strategicmap.camera.MapCamera;
import dn.strategicmap.feature.GeographicLabel;
import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.label.PreparedLabelGlyph;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.LabelLayoutOverrideTsv;
import dn.strategicmap.label.LabelZoomBandOverride;
import dn.strategicmap.label.PreparedLabelSelection;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Batches fixed-orientation labels using the map's late-nineteenth-century type hierarchy. */
final class MapLabelRenderer {
  private static final String OVERRIDES_RESOURCE = "/presentation/label-layout-overrides.tsv";
  private static final int FONT_PIXEL_SIZE = 48;
  private static final float BUILD_FONT_PIXEL_SIZE = 1_000.0f;
  private static final String EXTRA_GLYPHS = "ÀÁÂÄÅÆÇÈÉÊËÌÍÎÏÑÒÓÔÖÙÚÛÜÝ"
      + "àáâäåæçèéêëìíîïñòóôöùúûüýÿŒœŠšŽžŁłĐđ";

  private final OrthographicCamera screenCamera = new OrthographicCamera();
  private final SpriteBatch batch = new SpriteBatch();
  private final Matrix4 identityTransform = new Matrix4();
  private final Matrix4 rotationTransform = new Matrix4();
  private final BitmapFont serif = loadFont("fonts/EBGaramond-Regular.ttf");
  private final BitmapFont italic = loadFont("fonts/EBGaramond-Italic.ttf");
  private final LabelOrientationIndex orientations = LabelOrientationIndex.loadDefault();
  private final PreparedLabelGlyphIndex basePreparedGlyphs;
  private final boolean labelEditMode;
  private PreparedLabelGlyphIndex preparedGlyphs;
  private Map<String, LabelLayoutOverride> labelOverrides;
  private final List<LabelCandidate> geographicCandidates;
  private List<LabelCandidate> groupCandidates = List.of();
  private List<LabelCandidate> placeCandidates = List.of();
  private PreparedLabel[] preparedLabels;
  private PreparedGlyphLabel[] preparedGlyphLabels = new PreparedGlyphLabel[0];
  private int visibleCount;
  private int lastScreenHeight;

  MapLabelRenderer(List<GeographicLabel> labels, FlatMapProjection projection) {
    this(labels, projection, false);
  }

  MapLabelRenderer(
      List<GeographicLabel> labels, FlatMapProjection projection, boolean labelEditMode) {
    this.labelEditMode = labelEditMode;
    basePreparedGlyphs = labelEditMode
        ? PreparedLabelGlyphIndex.loadBase() : PreparedLabelGlyphIndex.loadDefault();
    preparedGlyphs = basePreparedGlyphs;
    labelOverrides = labelEditMode ? Map.of() : loadDefaultOverrides();
    geographicCandidates = new ArrayList<>(labels.size());
    for (GeographicLabel label : labels) {
      geographicCandidates.add(MapPresentationPolicy.geographicLabel(
          label, projection, orientations.rotationDegrees(label.labelId())));
    }
    rebuildPreparedLabels();
  }

  void setGroupCandidates(List<LabelCandidate> candidates) {
    groupCandidates = candidates;
    rebuildPreparedLabels();
  }

  void setPlaceCandidates(List<LabelCandidate> candidates) {
    placeCandidates = candidates;
    rebuildPreparedLabels();
  }

  void setLayoutOverrides(Map<String, LabelLayoutOverride> overrides) {
    labelOverrides = overrides;
    preparedGlyphs = basePreparedGlyphs.withOverrides(overrides);
    rebuildPreparedLabels();
  }

  PreparedLabelSelection labelAt(int viewportX, int viewportY) {
    float screenY = lastScreenHeight - viewportY;
    for (int index = preparedGlyphLabels.length - 1; index >= 0; index--) {
      PreparedGlyphLabel label = preparedGlyphLabels[index];
      if (label.contains(viewportX, screenY)) {
        return new PreparedLabelSelection(
            label.candidate().stableKey(),
            LabelZoomBandOverride.valueOf(label.candidate().minimumBand().name()));
      }
    }
    return null;
  }

  void render(MapCamera mapCamera, ZoomBand zoomBand, int firstCopy, int lastCopy) {
    int screenWidth = mapCamera.viewportWidthPixels();
    int screenHeight = mapCamera.viewportHeightPixels();
    lastScreenHeight = screenHeight;
    screenCamera.setToOrtho(false, screenWidth, screenHeight);
    visibleCount = 0;

    batch.setProjectionMatrix(screenCamera.combined);
    batch.setTransformMatrix(identityTransform);
    batch.begin();
    renderFace(serif, LabelTypography.Face.SERIF, mapCamera, zoomBand, firstCopy, lastCopy,
        screenWidth, screenHeight);
    renderFace(italic, LabelTypography.Face.ITALIC, mapCamera, zoomBand, firstCopy, lastCopy,
        screenWidth, screenHeight);
    batch.end();
    serif.getData().setScale(1.0f);
    italic.getData().setScale(1.0f);
  }

  int visibleCount() {
    return visibleCount;
  }

  void dispose() {
    batch.dispose();
    serif.dispose();
    italic.dispose();
  }

  private void renderFace(
      BitmapFont font,
      LabelTypography.Face face,
      MapCamera mapCamera,
      ZoomBand zoomBand,
      int firstCopy,
      int lastCopy,
      int screenWidth,
      int screenHeight) {
    for (PreparedLabel prepared : preparedLabels) {
      LabelCandidate candidate = prepared.candidate();
      LabelLayoutOverride override = override(candidate);
      float opacity = opacityAt(candidate, override, zoomBand, mapCamera.zoom());
      if (prepared.typography().face() != face
          || !visibleAt(override, opacity, labelEditMode)) {
        continue;
      }
      GlyphLayout layout = prepared.layout();
      for (int copy = firstCopy; copy <= lastCopy; copy++) {
        double copiedMapX = candidate.anchor().x() + copy * mapCamera.worldBounds().width();
        float anchorX = (float) mapCamera.viewportXForMapX(copiedMapX);
        float anchorY = (float) (screenHeight - mapCamera.viewportYForMapY(candidate.anchor().y()));
        if (!intersectsViewport(candidate, layout, anchorX, anchorY, screenWidth, screenHeight)) {
          continue;
        }
        visibleCount++;
        font.getData().setScale(prepared.typography().scale());
        Color colour = prepared.typography().colour();
        font.setColor(
            colour.r, colour.g, colour.b, renderedAlpha(colour.a, opacity, override));
        drawAtAuthoredAnchor(font, prepared, anchorX, anchorY);
      }
    }
    renderPreparedGlyphs(face, mapCamera, zoomBand, firstCopy, lastCopy,
        screenWidth, screenHeight);
  }

  private void renderPreparedGlyphs(
      LabelTypography.Face face,
      MapCamera mapCamera,
      ZoomBand zoomBand,
      int firstCopy,
      int lastCopy,
      int screenWidth,
      int screenHeight) {
    for (PreparedGlyphLabel label : preparedGlyphLabels) {
      LabelCandidate candidate = label.candidate();
      LabelLayoutOverride override = override(candidate);
      float opacity = opacityAt(candidate, override, zoomBand, mapCamera.zoom());
      if (label.face() != face) {
        continue;
      }
      label.resetBounds();
      if (!visibleAt(override, opacity, labelEditMode)) {
        continue;
      }
      Color colour = LabelTypography.forCategory(candidate.category()).colour();
      batch.setColor(
          colour.r, colour.g, colour.b, renderedAlpha(colour.a, opacity, override));
      boolean bold = LabelTypography.forCategory(candidate.category()).bold();
      for (int copy = firstCopy; copy <= lastCopy; copy++) {
        if (!label.intersects(mapCamera, copy)) {
          continue;
        }
        boolean visible = false;
        for (DrawablePreparedGlyph glyph : label.glyphs()) {
          float x = (float) mapCamera.viewportXForMapX(
              glyph.prepared().mapX() + copy * mapCamera.worldBounds().width());
          float y = (float) (screenHeight
              - mapCamera.viewportYForMapY(glyph.prepared().mapY()));
          if (x < -32.0f || x > screenWidth + 32.0f || y < -32.0f || y > screenHeight + 32.0f) {
            continue;
          }
          visible = true;
          float scale = preparedFontScale(glyph, mapCamera.mapUnitsPerPixel());
          float radius = (float) Math.hypot(
              glyph.bitmap().width * scale, glyph.bitmap().height * scale);
          label.include(x - radius, y - radius, x + radius, y + radius);
          drawPreparedGlyph(batch, glyph, x, y, mapCamera.mapUnitsPerPixel(), 0.0f);
          if (bold) {
            drawPreparedGlyph(batch, glyph, x, y, mapCamera.mapUnitsPerPixel(), 0.45f);
          }
        }
        if (visible) {
          visibleCount++;
        }
      }
    }
    batch.setColor(Color.WHITE);
  }

  private static void drawPreparedGlyph(
      SpriteBatch batch,
      DrawablePreparedGlyph glyph,
      float baselineX,
      float baselineY,
      double mapUnitsPerPixel,
      float screenOffsetX) {
    float scale = preparedFontScale(glyph, mapUnitsPerPixel);
    float xOffset = glyph.bitmap().xoffset * scale;
    float yOffset = glyph.bitmap().yoffset * scale;
    batch.draw(
        glyph.region(),
        baselineX + xOffset + screenOffsetX,
        baselineY + yOffset,
        -xOffset,
        -yOffset,
        glyph.bitmap().width * scale,
        glyph.bitmap().height * scale,
        1.0f,
        1.0f,
        glyph.prepared().rotationDegrees());
  }

  private static float preparedFontScale(
      DrawablePreparedGlyph glyph, double mapUnitsPerPixel) {
    return (float) (glyph.prepared().fontScaleMapUnits() * BUILD_FONT_PIXEL_SIZE
        / (mapUnitsPerPixel * FONT_PIXEL_SIZE));
  }

  private LabelLayoutOverride override(LabelCandidate candidate) {
    return labelOverrides.getOrDefault(candidate.stableKey(), LabelLayoutOverride.IDENTITY);
  }

  private float renderedAlpha(
      float normalAlpha, float zoomOpacity, LabelLayoutOverride override) {
    float editorOpacity = override.hidden() && labelEditMode ? 0.25f : 1.0f;
    return normalAlpha * zoomOpacity * editorOpacity;
  }

  static boolean visibleAt(
      LabelLayoutOverride override,
      float opacity,
      boolean labelEditMode) {
    return (!override.hidden() || labelEditMode) && opacity > 0.0f;
  }

  static float opacityAt(
      LabelCandidate candidate,
      LabelLayoutOverride override,
      ZoomBand currentBand,
      double zoom) {
    ZoomBand baseBand = effectiveMinimumBand(candidate, override);
    if (baseBand.ordinal() > ZoomBand.THEATRE.ordinal()) {
      return currentBand.includes(baseBand) ? 1.0f : 0.0f;
    }
    double fadeInStart = ZoomBandPolicy.boundaryInto(baseBand);
    double fullOpacityAt = baseBand == ZoomBand.WORLD
        ? fadeInStart : ZoomBandPolicy.midpointZoom(baseBand);
    double fadeOutStart = ZoomBandPolicy.boundaryAfter(baseBand);
    double fadeOutEnd = ZoomBandPolicy.midpointTwoBandsAfter(baseBand);
    if (zoom < fadeInStart || zoom >= fadeOutEnd) {
      return 0.0f;
    }
    if (zoom < fullOpacityAt) {
      return (float) ((zoom - fadeInStart) / (fullOpacityAt - fadeInStart));
    }
    if (zoom <= fadeOutStart) {
      return 1.0f;
    }
    return (float) (1.0 - (zoom - fadeOutStart) / (fadeOutEnd - fadeOutStart));
  }

  static ZoomBand effectiveMinimumBand(
      LabelCandidate candidate, LabelLayoutOverride override) {
    return override.minimumBandOverride() == LabelZoomBandOverride.DEFAULT
        ? candidate.minimumBand()
        : ZoomBand.valueOf(override.minimumBandOverride().name());
  }

  private static Map<String, LabelLayoutOverride> loadDefaultOverrides() {
    InputStream input = MapLabelRenderer.class.getResourceAsStream(OVERRIDES_RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Label overrides not found: " + OVERRIDES_RESOURCE);
    }
    try {
      return LabelLayoutOverrideTsv.read(input);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Unable to load label overrides", exception);
    }
  }

  private static boolean intersectsViewport(
      LabelCandidate candidate,
      GlyphLayout layout,
      float anchorX,
      float anchorY,
      int screenWidth,
      int screenHeight) {
    float halfWidth = layout.width * 0.5f;
    float halfHeight = layout.height * 0.5f;
    if (candidate.rotationDegrees() != 0.0f) {
      float radius = (float) Math.hypot(halfWidth, halfHeight);
      halfWidth = radius;
      halfHeight = radius;
    }
    return anchorX + halfWidth >= 0.0f
        && anchorX - halfWidth <= screenWidth
        && anchorY + halfHeight >= 0.0f
        && anchorY - halfHeight <= screenHeight;
  }

  private void drawAtAuthoredAnchor(
      BitmapFont font, PreparedLabel prepared, float anchorX, float anchorY) {
    GlyphLayout layout = prepared.layout();
    float drawX = anchorX - layout.width * 0.5f;
    float drawY = anchorY + layout.height * 0.5f;
    float rotation = prepared.candidate().rotationDegrees();
    if (rotation != 0.0f) {
      rotationTransform.idt()
          .translate(anchorX, anchorY, 0.0f)
          .rotate(0.0f, 0.0f, 1.0f, rotation)
          .translate(-anchorX, -anchorY, 0.0f);
      batch.setTransformMatrix(rotationTransform);
    }
    font.draw(batch, layout, drawX, drawY);
    if (prepared.typography().bold()) {
      font.draw(batch, layout, drawX + 0.45f, drawY);
    }
    if (rotation != 0.0f) {
      batch.setTransformMatrix(identityTransform);
    }
  }

  private void rebuildPreparedLabels() {
    List<LabelCandidate> combined = new ArrayList<>(
        geographicCandidates.size() + groupCandidates.size() + placeCandidates.size());
    combined.addAll(geographicCandidates);
    combined.addAll(groupCandidates);
    combined.addAll(placeCandidates);
    List<PreparedLabel> liveLabels = new ArrayList<>();
    Map<String, LabelCandidate> candidates = new HashMap<>(combined.size());
    for (LabelCandidate candidate : combined) {
      candidates.put(candidate.stableKey(), candidate);
      if (preparedGlyphs.glyphs(candidate.stableKey(), candidate.category()) != null) {
        continue;
      }
      LabelTypography typography = LabelTypography.forCategory(candidate.category());
      BitmapFont font = typography.face() == LabelTypography.Face.SERIF ? serif : italic;
      font.getData().setScale(typography.scale());
      liveLabels.add(new PreparedLabel(
          candidate,
          typography,
          new GlyphLayout(font, typography.displayText(candidate.text()))));
    }
    preparedLabels = liveLabels.toArray(PreparedLabel[]::new);
    preparedGlyphLabels = prepareGlyphLabels(candidates);
    serif.getData().setScale(1.0f);
    italic.getData().setScale(1.0f);
  }

  private PreparedGlyphLabel[] prepareGlyphLabels(Map<String, LabelCandidate> candidates) {
    List<PreparedGlyphLabel> labels = new ArrayList<>(preparedGlyphs.labelKeys().size());
    for (String labelKey : preparedGlyphs.labelKeys()) {
      LabelCandidate candidate = candidates.get(labelKey);
      if (candidate == null) {
        continue;
      }
      List<DrawablePreparedGlyph> drawableGlyphs = new ArrayList<>();
      LabelTypography.Face face = null;
      List<PreparedLabelGlyph> selectedGlyphs = preparedGlyphs.glyphs(
          labelKey, candidate.category());
      for (PreparedLabelGlyph prepared : selectedGlyphs) {
        face = LabelTypography.Face.valueOf(prepared.fontFace());
        if (prepared.character().equals(" ")) {
          continue;
        }
        BitmapFont font = face == LabelTypography.Face.SERIF ? serif : italic;
        BitmapFont.Glyph bitmap = font.getData().getGlyph(prepared.character().charAt(0));
        if (bitmap == null) {
          throw new IllegalStateException(
              "Bundled font has no glyph for prepared label " + labelKey + ": "
                  + prepared.character());
        }
        TextureRegion region = new TextureRegion(font.getRegion(bitmap.page));
        region.setRegion(bitmap.u, bitmap.v, bitmap.u2, bitmap.v2);
        region.flip(false, true);
        drawableGlyphs.add(new DrawablePreparedGlyph(prepared, bitmap, region));
      }
      if (face != null) {
        labels.add(new PreparedGlyphLabel(candidate, face, drawableGlyphs));
      }
    }
    return labels.toArray(PreparedGlyphLabel[]::new);
  }

  private static BitmapFont loadFont(String resource) {
    FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal(resource));
    FreeTypeFontGenerator.FreeTypeFontParameter parameter =
        new FreeTypeFontGenerator.FreeTypeFontParameter();
    parameter.size = FONT_PIXEL_SIZE;
    parameter.characters = FreeTypeFontGenerator.DEFAULT_CHARS + EXTRA_GLYPHS;
    BitmapFont font = generator.generateFont(parameter);
    generator.dispose();
    font.setUseIntegerPositions(false);
    return font;
  }

  private record PreparedLabel(
      LabelCandidate candidate, LabelTypography typography, GlyphLayout layout) {}

  private static final class PreparedGlyphLabel {
    private final LabelCandidate candidate;
    private final LabelTypography.Face face;
    private final List<DrawablePreparedGlyph> glyphs;
    private final double mapWest;
    private final double mapSouth;
    private final double mapEast;
    private final double mapNorth;
    private float west;
    private float south;
    private float east;
    private float north;

    private PreparedGlyphLabel(
        LabelCandidate candidate,
        LabelTypography.Face face,
        List<DrawablePreparedGlyph> glyphs) {
      this.candidate = candidate;
      this.face = face;
      this.glyphs = glyphs;
      double west = Double.POSITIVE_INFINITY;
      double south = Double.POSITIVE_INFINITY;
      double east = Double.NEGATIVE_INFINITY;
      double north = Double.NEGATIVE_INFINITY;
      for (DrawablePreparedGlyph glyph : glyphs) {
        double mapScale = glyph.prepared().fontScaleMapUnits()
            * BUILD_FONT_PIXEL_SIZE / FONT_PIXEL_SIZE;
        double radius = Math.hypot(glyph.bitmap().width, glyph.bitmap().height) * mapScale;
        west = Math.min(west, glyph.prepared().mapX() - radius);
        south = Math.min(south, glyph.prepared().mapY() - radius);
        east = Math.max(east, glyph.prepared().mapX() + radius);
        north = Math.max(north, glyph.prepared().mapY() + radius);
      }
      mapWest = west;
      mapSouth = south;
      mapEast = east;
      mapNorth = north;
      resetBounds();
    }

    LabelCandidate candidate() { return candidate; }
    LabelTypography.Face face() { return face; }
    List<DrawablePreparedGlyph> glyphs() { return glyphs; }

    boolean intersects(MapCamera camera, int copy) {
      double offset = copy * camera.worldBounds().width();
      double visibleWest = camera.centerMapX() - camera.visibleMapWidth() * 0.5;
      double visibleEast = visibleWest + camera.visibleMapWidth();
      double visibleSouth = camera.centerMapY() - camera.visibleMapHeight() * 0.5;
      double visibleNorth = visibleSouth + camera.visibleMapHeight();
      return mapEast + offset >= visibleWest
          && mapWest + offset <= visibleEast
          && mapNorth >= visibleSouth
          && mapSouth <= visibleNorth;
    }

    void resetBounds() {
      west = Float.POSITIVE_INFINITY;
      south = Float.POSITIVE_INFINITY;
      east = Float.NEGATIVE_INFINITY;
      north = Float.NEGATIVE_INFINITY;
    }

    void include(float glyphWest, float glyphSouth, float glyphEast, float glyphNorth) {
      west = Math.min(west, glyphWest);
      south = Math.min(south, glyphSouth);
      east = Math.max(east, glyphEast);
      north = Math.max(north, glyphNorth);
    }

    boolean contains(float screenX, float screenY) {
      return screenX >= west && screenX <= east && screenY >= south && screenY <= north;
    }
  }

  private record DrawablePreparedGlyph(
      PreparedLabelGlyph prepared,
      BitmapFont.Glyph bitmap,
      TextureRegion region) {}
}
