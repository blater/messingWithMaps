package dn.strategicmap.geometry;

import java.util.List;

/** Canonical polygon rings and render levels prepared by the offline map pipeline. */
public final class PreparedMapGeometry {
  private final List<Part> parts;
  private final List<Level> levels;

  public PreparedMapGeometry(List<Part> parts, List<Level> levels) {
    this.parts = parts;
    this.levels = levels;
  }

  public int partCount() {
    return parts.size();
  }

  public Part part(int index) {
    return parts.get(index);
  }

  public int levelCount() {
    return levels.size();
  }

  public Level level(int index) {
    return levels.get(index);
  }

  /** One canonical source polygon part used by geography and later exact interaction. */
  public static final class Part {
    private final int regionIndex;
    private final MapBounds bounds;
    private final float[][] rings;

    public Part(int regionIndex, MapBounds bounds, float[][] rings) {
      this.regionIndex = regionIndex;
      this.bounds = bounds;
      this.rings = rings;
    }

    public int regionIndex() {
      return regionIndex;
    }

    public MapBounds bounds() {
      return bounds;
    }

    public int ringCount() {
      return rings.length;
    }

    public int ringPointCount(int ringIndex) {
      return rings[ringIndex].length / 2;
    }

    public float ringMapX(int ringIndex, int pointIndex) {
      return rings[ringIndex][pointIndex * 2];
    }

    public float ringMapY(int ringIndex, int pointIndex) {
      return rings[ringIndex][pointIndex * 2 + 1];
    }
  }

  /** One regular-grid render level. Chunks are stored in row-major order from south-west. */
  public static final class Level {
    private final double maxErrorMapUnits;
    private final int columns;
    private final int rows;
    private final List<Chunk> chunks;

    public Level(double maxErrorMapUnits, int columns, int rows, List<Chunk> chunks) {
      this.maxErrorMapUnits = maxErrorMapUnits;
      this.columns = columns;
      this.rows = rows;
      this.chunks = chunks;
    }

    public double maxErrorMapUnits() {
      return maxErrorMapUnits;
    }

    public int columns() {
      return columns;
    }

    public int rows() {
      return rows;
    }

    public int chunkCount() {
      return chunks.size();
    }

    public Chunk chunk(int index) {
      return chunks.get(index);
    }

    public Chunk chunk(int column, int row) {
      return chunks.get(row * columns + column);
    }
  }

  /** One ready-to-upload render chunk. Primitive storage is owned by the loaded map asset. */
  public static final class Chunk {
    private final MapBounds bounds;
    private final List<Fill> fills;
    private final float[] boundaryLines;
    private final int fillVertexCount;

    public Chunk(MapBounds bounds, List<Fill> fills, float[] boundaryLines) {
      this.bounds = bounds;
      this.fills = fills;
      this.boundaryLines = boundaryLines;
      int vertices = 0;
      for (Fill fill : fills) {
        vertices += fill.vertexCount();
      }
      fillVertexCount = vertices;
    }

    public MapBounds bounds() {
      return bounds;
    }

    public int fillCount() {
      return fills.size();
    }

    public Fill fill(int index) {
      return fills.get(index);
    }

    public int fillVertexCount() {
      return fillVertexCount;
    }

    public int boundaryVertexCount() {
      return boundaryLines.length / 2;
    }

    public float boundaryMapX(int vertexIndex) {
      return boundaryLines[vertexIndex * 2];
    }

    public float boundaryMapY(int vertexIndex) {
      return boundaryLines[vertexIndex * 2 + 1];
    }
  }

  /** Triangles for one region within a render chunk. */
  public static final class Fill {
    private final int regionIndex;
    private final float[] triangles;

    public Fill(int regionIndex, float[] triangles) {
      this.regionIndex = regionIndex;
      this.triangles = triangles;
    }

    public int regionIndex() {
      return regionIndex;
    }

    public int vertexCount() {
      return triangles.length / 2;
    }

    public float mapX(int vertexIndex) {
      return triangles[vertexIndex * 2];
    }

    public float mapY(int vertexIndex) {
      return triangles[vertexIndex * 2 + 1];
    }
  }
}
