package dn.strategicmap.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import dn.strategicmap.camera.MapCamera;
import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.PreparedMapGeometry;

/** Owns prepared region GPU buffers, LOD selection, fine-grid culling, and disposal. */
final class RegionGeometryRenderer {
  private static final String RELIEF_TEXTURE_PATH = "maps/topographic-relief.png";
  private static final float BOUNDARY_COLOR = Color.toFloatBits(0.25f, 0.22f, 0.16f, 1.0f);
  private static final float RELIEF_STRENGTH = 0.55f;
  private static final double FULL_RELIEF_MAX_ZOOM = 5.0;
  private static final String VERTEX_SHADER = """
      attribute vec2 a_position;
      attribute vec4 a_color;
      attribute float a_regionIndex;
      uniform mat4 u_projection;
      uniform float u_worldOffset;
      uniform float u_interactionEnabled;
      uniform float u_hoveredRegionIndex;
      uniform float u_selectedRegionIndex;
      uniform vec4 u_hoverColor;
      uniform vec4 u_selectedColor;
      varying vec4 v_color;
      varying vec2 v_mapPosition;
      void main() {
        v_color = a_color;
        v_mapPosition = a_position;
        if (u_interactionEnabled > 0.5) {
          if (abs(a_regionIndex - u_selectedRegionIndex) < 0.5) {
            v_color = u_selectedColor;
          } else if (abs(a_regionIndex - u_hoveredRegionIndex) < 0.5) {
            v_color = u_hoverColor;
          }
        }
        gl_Position = u_projection * vec4(a_position.x + u_worldOffset, a_position.y, 0.0, 1.0);
      }
      """;
  private static final String FRAGMENT_SHADER = """
      #ifdef GL_ES
      precision mediump float;
      #endif
      varying vec4 v_color;
      varying vec2 v_mapPosition;
      uniform sampler2D u_reliefTexture;
      uniform vec4 u_worldBounds;
      uniform float u_reliefEnabled;
      uniform float u_reliefStrength;
      void main() {
        if (u_reliefEnabled > 0.5) {
          vec2 reliefUv = vec2(
              (v_mapPosition.x - u_worldBounds.x) / u_worldBounds.z,
              (u_worldBounds.y - v_mapPosition.y) / u_worldBounds.w);
          vec3 reliefSample = texture2D(u_reliefTexture, reliefUv).rgb;
          float reliefLuminance = dot(reliefSample, vec3(0.299, 0.587, 0.114));
          float modulation = mix(1.0, reliefLuminance, u_reliefStrength);
          gl_FragColor = vec4(v_color.rgb * modulation, v_color.a);
        } else {
          gl_FragColor = v_color;
        }
      }
      """;

  private final LoadedWorldMap worldMap;
  private final ShaderProgram shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
  private final Texture reliefTexture;
  private final GpuLevel[] levels;
  private final GeometryLodSelector lodSelector;
  private final float[] packedStyles;
  private final boolean outlinesOnly;
  private RegionStyleSnapshot styles;
  private long appliedStyleVersion = Long.MIN_VALUE;
  private int hoveredRegionIndex = -1;
  private int selectedRegionIndex = -1;

  RegionGeometryRenderer(LoadedWorldMap worldMap, RegionStyleSnapshot styles) {
    this(worldMap, styles, false);
  }

  RegionGeometryRenderer(
      LoadedWorldMap worldMap, RegionStyleSnapshot styles, boolean outlinesOnly) {
    this.worldMap = worldMap;
    this.styles = styles;
    this.outlinesOnly = outlinesOnly;
    if (!shader.isCompiled()) {
      throw new IllegalStateException("Strategic map shader failed: " + shader.getLog());
    }
    reliefTexture = outlinesOnly
        ? null : new Texture(Gdx.files.internal(RELIEF_TEXTURE_PATH), true);
    if (reliefTexture != null) {
      reliefTexture.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
      reliefTexture.setWrap(TextureWrap.Repeat, TextureWrap.ClampToEdge);
    }
    PreparedMapGeometry geometry = worldMap.geometry();
    levels = new GpuLevel[geometry.levelCount()];
    for (int index = 0; index < levels.length; index++) {
      levels[index] = new GpuLevel(geometry.level(index));
    }
    lodSelector = new GeometryLodSelector(geometry.level(0).maxErrorMapUnits());
    packedStyles = new float[worldMap.regionCount()];
    applyStyles();
  }

  void setStyles(RegionStyleSnapshot styles) {
    this.styles = styles;
  }

  void setInteraction(String hoveredRegionId, String selectedRegionId) {
    hoveredRegionIndex = regionIndex(hoveredRegionId);
    selectedRegionIndex = regionIndex(selectedRegionId);
  }

  void render(
      OrthographicCamera camera, MapCamera mapCamera, int firstCopy, int lastCopy) {
    int levelIndex = lodSelector.select(mapCamera.mapUnitsPerPixel());
    GpuLevel level = levels[levelIndex];
    MapBounds worldBounds = worldMap.worldBounds();
    double visibleWest = mapCamera.centerMapX() - mapCamera.visibleMapWidth() * 0.5;
    double visibleEast = visibleWest + mapCamera.visibleMapWidth();
    double visibleSouth = mapCamera.centerMapY() - mapCamera.visibleMapHeight() * 0.5;
    double visibleNorth = visibleSouth + mapCamera.visibleMapHeight();

    shader.bind();
    shader.setUniformMatrix("u_projection", camera.combined);
    if (!outlinesOnly) {
      applyStyles();
      reliefTexture.bind(0);
      shader.setUniformi("u_reliefTexture", 0);
      shader.setUniformf(
          "u_worldBounds",
          (float) worldBounds.west(),
          (float) worldBounds.north(),
          (float) worldBounds.width(),
          (float) worldBounds.height());
      shader.setUniformf("u_reliefStrength", reliefStrength(mapCamera.zoom()));
      shader.setUniformf("u_hoveredRegionIndex", hoveredRegionIndex);
      shader.setUniformf("u_selectedRegionIndex", selectedRegionIndex);
      shader.setUniformf("u_hoverColor", 0.75f, 0.73f, 0.48f, 1.0f);
      shader.setUniformf("u_selectedColor", 0.88f, 0.70f, 0.34f, 1.0f);
      shader.setUniformf("u_interactionEnabled", 1.0f);
      shader.setUniformf("u_reliefEnabled", 1.0f);
      for (int copy = firstCopy; copy <= lastCopy; copy++) {
        shader.setUniformf("u_worldOffset", (float) (copy * worldBounds.width()));
        level.renderVisibleFills(
            worldBounds, copy, visibleWest, visibleSouth, visibleEast, visibleNorth);
      }
    }

    Gdx.gl.glLineWidth(1.0f);
    shader.setUniformf("u_interactionEnabled", 0.0f);
    shader.setUniformf("u_reliefEnabled", 0.0f);
    for (int copy = firstCopy; copy <= lastCopy; copy++) {
      shader.setUniformf("u_worldOffset", (float) (copy * worldBounds.width()));
      level.renderVisibleBoundaries(
          worldBounds, copy, visibleWest, visibleSouth, visibleEast, visibleNorth);
    }
  }

  void dispose() {
    for (GpuLevel level : levels) {
      level.dispose();
    }
    if (reliefTexture != null) {
      reliefTexture.dispose();
    }
    shader.dispose();
  }

  /** O(all prepared fill vertices), only when the externally supplied style version changes. */
  private void applyStyles() {
    if (styles.version() == appliedStyleVersion) {
      return;
    }
    for (int regionIndex = 0; regionIndex < packedStyles.length; regionIndex++) {
      packedStyles[regionIndex] = packed(
          styles.styleFor(worldMap.region(regionIndex).regionId()));
    }
    for (GpuLevel level : levels) {
      level.applyStyles(packedStyles);
    }
    appliedStyleVersion = styles.version();
  }

  private static Mesh mesh(boolean isStatic, int vertexCount) {
    return new Mesh(
        isStatic,
        vertexCount,
        0,
        new VertexAttribute(
            VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
        VertexAttribute.ColorPacked(),
        new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_regionIndex"));
  }

  private int regionIndex(String regionId) {
    if (regionId == null) {
      return -1;
    }
    for (int index = 0; index < worldMap.regionCount(); index++) {
      if (worldMap.region(index).regionId().equals(regionId)) {
        return index;
      }
    }
    return -1;
  }

  private static float packed(RegionStyle style) {
    return Color.toFloatBits(style.red(), style.green(), style.blue(), style.alpha());
  }

  /** Keeps the intentionally broad source texture from dominating close tactical views. */
  private static float reliefStrength(double zoom) {
    return (float) (RELIEF_STRENGTH * Math.min(1.0, FULL_RELIEF_MAX_ZOOM / zoom));
  }

  /** One level is a fixed regular grid, so each frame visits visible cells rather than regions. */
  private final class GpuLevel {
    private final int columns;
    private final int rows;
    private final GpuChunk[] chunks;

    private GpuLevel(PreparedMapGeometry.Level level) {
      columns = level.columns();
      rows = level.rows();
      chunks = new GpuChunk[level.chunkCount()];
      for (int index = 0; index < chunks.length; index++) {
        chunks[index] = new GpuChunk(level.chunk(index));
      }
    }

    private void renderVisibleFills(
        MapBounds worldBounds,
        int worldCopy,
        double visibleWest,
        double visibleSouth,
        double visibleEast,
        double visibleNorth) {
      renderVisible(
          worldBounds,
          worldCopy,
          visibleWest,
          visibleSouth,
          visibleEast,
          visibleNorth,
          true);
    }

    private void renderVisibleBoundaries(
        MapBounds worldBounds,
        int worldCopy,
        double visibleWest,
        double visibleSouth,
        double visibleEast,
        double visibleNorth) {
      renderVisible(
          worldBounds,
          worldCopy,
          visibleWest,
          visibleSouth,
          visibleEast,
          visibleNorth,
          false);
    }

    /** O(visible grid cells), at most columns * rows, with no frame allocation. */
    private void renderVisible(
        MapBounds worldBounds,
        int worldCopy,
        double visibleWest,
        double visibleSouth,
        double visibleEast,
        double visibleNorth,
        boolean fills) {
      double offset = worldCopy * worldBounds.width();
      double canonicalNorth = Math.min(worldBounds.north(), visibleNorth);
      double canonicalSouth = Math.max(worldBounds.south(), visibleSouth);
      double canonicalEast = Math.min(worldBounds.east(), visibleEast - offset);
      double canonicalWest = Math.max(worldBounds.west(), visibleWest - offset);
      if (canonicalWest >= canonicalEast || canonicalSouth >= canonicalNorth) {
        return;
      }

      double tileWidth = worldBounds.width() / columns;
      double tileHeight = worldBounds.height() / rows;
      int firstColumn = gridIndex(canonicalWest, worldBounds.west(), tileWidth, columns);
      int lastColumn = gridIndex(
          Math.nextDown(canonicalEast), worldBounds.west(), tileWidth, columns);
      int firstRow = gridIndex(canonicalSouth, worldBounds.south(), tileHeight, rows);
      int lastRow = gridIndex(
          Math.nextDown(canonicalNorth), worldBounds.south(), tileHeight, rows);
      for (int row = firstRow; row <= lastRow; row++) {
        for (int column = firstColumn; column <= lastColumn; column++) {
          GpuChunk chunk = chunks[row * columns + column];
          if (fills) {
            chunk.renderFill();
          } else {
            chunk.renderBoundary();
          }
        }
      }
    }

    private void applyStyles(float[] stylesByRegion) {
      for (GpuChunk chunk : chunks) {
        chunk.applyStyles(stylesByRegion);
      }
    }

    private void dispose() {
      for (GpuChunk chunk : chunks) {
        chunk.dispose();
      }
    }
  }

  private final class GpuChunk {
    private final Mesh fillMesh;
    private final float[] fillVertices;
    private final int[] regionByFillVertex;
    private final Mesh boundaryMesh;

    private GpuChunk(PreparedMapGeometry.Chunk chunk) {
      int fillVertexCount = chunk.fillVertexCount();
      fillVertices = new float[fillVertexCount * 4];
      regionByFillVertex = new int[fillVertexCount];
      int targetVertex = 0;
      for (int fillIndex = 0; fillIndex < chunk.fillCount(); fillIndex++) {
        PreparedMapGeometry.Fill fill = chunk.fill(fillIndex);
        for (int vertex = 0; vertex < fill.vertexCount(); vertex++) {
          fillVertices[targetVertex * 4] = fill.mapX(vertex);
          fillVertices[targetVertex * 4 + 1] = fill.mapY(vertex);
          fillVertices[targetVertex * 4 + 3] = fill.regionIndex();
          regionByFillVertex[targetVertex] = fill.regionIndex();
          targetVertex++;
        }
      }
      fillMesh = fillVertexCount == 0 ? null : mesh(false, fillVertexCount);
      if (fillMesh != null) {
        fillMesh.setVertices(fillVertices);
      }

      int boundaryVertexCount = chunk.boundaryVertexCount();
      boundaryMesh = boundaryVertexCount == 0 ? null : mesh(true, boundaryVertexCount);
      if (boundaryMesh != null) {
        float[] boundaryVertices = new float[boundaryVertexCount * 4];
        for (int vertex = 0; vertex < boundaryVertexCount; vertex++) {
          boundaryVertices[vertex * 4] = chunk.boundaryMapX(vertex);
          boundaryVertices[vertex * 4 + 1] = chunk.boundaryMapY(vertex);
          boundaryVertices[vertex * 4 + 2] = BOUNDARY_COLOR;
          boundaryVertices[vertex * 4 + 3] = -1.0f;
        }
        boundaryMesh.setVertices(boundaryVertices);
      }
    }

    private void applyStyles(float[] stylesByRegion) {
      if (fillMesh == null) {
        return;
      }
      for (int vertex = 0; vertex < regionByFillVertex.length; vertex++) {
        fillVertices[vertex * 4 + 2] = stylesByRegion[regionByFillVertex[vertex]];
      }
      fillMesh.updateVertices(0, fillVertices);
    }

    private void renderFill() {
      if (fillMesh != null) {
        fillMesh.render(shader, GL20.GL_TRIANGLES);
      }
    }

    private void renderBoundary() {
      if (boundaryMesh != null) {
        boundaryMesh.render(shader, GL20.GL_LINES);
      }
    }

    private void dispose() {
      if (fillMesh != null) {
        fillMesh.dispose();
      }
      if (boundaryMesh != null) {
        boundaryMesh.dispose();
      }
    }
  }

  private static int gridIndex(double value, double minimum, double size, int count) {
    return Math.max(0, Math.min(count - 1, (int) Math.floor((value - minimum) / size)));
  }
}
