package dn.strategicmap.data;

import dn.strategicmap.geography.Region;
import dn.strategicmap.geometry.MapBounds;
import dn.strategicmap.geometry.MapPolygon;
import dn.strategicmap.geometry.MapRing;
import dn.strategicmap.geometry.Point;
import dn.strategicmap.geometry.PreparedMapGeometry;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads the generated strategic-map binary without parsing source GeoJSON. */
public final class WorldMapAssetLoader {
  private static final int MAGIC = 0x44524d50;
  private static final int VERSION = 2;
  private static final String DEFAULT_RESOURCE = "/maps/strategic_world.map";

  private WorldMapAssetLoader() {}

  public static LoadedWorldMap loadDefault() {
    InputStream input = WorldMapAssetLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Strategic map asset not found: " + DEFAULT_RESOURCE);
    }
    return load(input, DEFAULT_RESOURCE);
  }

  public static LoadedWorldMap load(InputStream input, String sourceDescription) {
    String operation = "header";
    try (DataInputStream data = new DataInputStream(new BufferedInputStream(input))) {
      if (data.readInt() != MAGIC) {
        throw new IOException("unrecognised map asset header");
      }
      if (data.readInt() != VERSION) {
        throw new IOException("unsupported map asset version");
      }
      String sourceDigest = data.readUTF();
      String sourceDatasetId = data.readUTF();
      MapBounds worldBounds = readBounds(data);
      int regionCount = data.readInt();
      int partCount = data.readInt();

      operation = "region table";
      List<RegionHeader> regionHeaders = new ArrayList<>(regionCount);
      for (int index = 0; index < regionCount; index++) {
        regionHeaders.add(new RegionHeader(data.readUTF(), data.readUTF()));
      }

      operation = "canonical polygon parts";
      List<PreparedMapGeometry.Part> parts = new ArrayList<>(partCount);
      List<List<MapPolygon>> regionParts = new ArrayList<>(regionCount);
      for (int index = 0; index < regionCount; index++) {
        regionParts.add(new ArrayList<>());
      }
      for (int partIndex = 0; partIndex < partCount; partIndex++) {
        int regionIndex = data.readInt();
        MapBounds bounds = readBounds(data);
        int ringCount = data.readInt();
        float[][] rings = new float[ringCount][];
        for (int ringIndex = 0; ringIndex < ringCount; ringIndex++) {
          int pointCount = data.readInt();
          float[] ring = new float[pointCount * 2];
          for (int coordinate = 0; coordinate < ring.length; coordinate++) {
            ring[coordinate] = data.readFloat();
          }
          rings[ringIndex] = ring;
        }
        PreparedMapGeometry.Part part = new PreparedMapGeometry.Part(regionIndex, bounds, rings);
        parts.add(part);
        if (regionIndex >= 0) {
          regionParts.get(regionIndex).add(toMapPolygon(part));
        }
      }

      operation = "prepared render levels";
      int levelCount = data.readInt();
      List<PreparedMapGeometry.Level> levels = new ArrayList<>(levelCount);
      for (int levelIndex = 0; levelIndex < levelCount; levelIndex++) {
        double maxErrorMapUnits = data.readDouble();
        int columns = data.readInt();
        int rows = data.readInt();
        int chunkCount = data.readInt();
        List<PreparedMapGeometry.Chunk> chunks = new ArrayList<>(chunkCount);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
          MapBounds bounds = readBounds(data);
          int fillCount = data.readInt();
          List<PreparedMapGeometry.Fill> fills = new ArrayList<>(fillCount);
          for (int fillIndex = 0; fillIndex < fillCount; fillIndex++) {
            int regionIndex = data.readInt();
            int vertexCount = data.readInt();
            float[] triangles = readFloats(data, vertexCount * 2);
            fills.add(new PreparedMapGeometry.Fill(regionIndex, triangles));
          }
          int boundaryVertexCount = data.readInt();
          float[] boundaryLines = readFloats(data, boundaryVertexCount * 2);
          chunks.add(new PreparedMapGeometry.Chunk(bounds, fills, boundaryLines));
        }
        levels.add(new PreparedMapGeometry.Level(
            maxErrorMapUnits, columns, rows, chunks));
      }

      operation = "region geometry assembly";
      List<Region> regions = new ArrayList<>(regionCount);
      for (int index = 0; index < regionCount; index++) {
        RegionHeader header = regionHeaders.get(index);
        regions.add(new Region(
            header.regionId(), header.displayName(), regionParts.get(index), sourceDatasetId));
      }
      return new LoadedWorldMap(
          sourceDigest, worldBounds, regions, new PreparedMapGeometry(parts, levels));
    } catch (EOFException failure) {
      throw new IllegalStateException(
          "Failed reading " + sourceDescription + " during " + operation, failure);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Failed reading " + sourceDescription + " during " + operation + ": "
              + failure.getMessage(),
          failure);
    }
  }

  private static MapBounds readBounds(DataInputStream data) throws IOException {
    double west = data.readDouble();
    double south = data.readDouble();
    double east = data.readDouble();
    double north = data.readDouble();
    return new MapBounds(north, south, east, west);
  }

  private static float[] readFloats(DataInputStream data, int count) throws IOException {
    float[] values = new float[count];
    for (int index = 0; index < values.length; index++) {
      values[index] = data.readFloat();
    }
    return values;
  }

  private static MapPolygon toMapPolygon(PreparedMapGeometry.Part part) {
    MapRing exterior = toMapRing(part, 0);
    List<MapRing> holes = new ArrayList<>(Math.max(0, part.ringCount() - 1));
    for (int ringIndex = 1; ringIndex < part.ringCount(); ringIndex++) {
      holes.add(toMapRing(part, ringIndex));
    }
    return new MapPolygon(exterior, holes);
  }

  private static MapRing toMapRing(PreparedMapGeometry.Part part, int ringIndex) {
    int pointCount = part.ringPointCount(ringIndex);
    List<Point> points = new ArrayList<>(pointCount);
    for (int pointIndex = 0; pointIndex < pointCount; pointIndex++) {
      points.add(new Point(
          part.ringMapX(ringIndex, pointIndex), part.ringMapY(ringIndex, pointIndex)));
    }
    return new MapRing(points);
  }

  private record RegionHeader(String regionId, String displayName) {}
}
