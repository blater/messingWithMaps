package dn.strategicmap.pipeline;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Produces projection-preserving monochrome terrain candidates for visual review. */
public final class TopographicReliefAssetBuilder {
  static final int OUTPUT_WIDTH = 2_048;
  static final int OUTPUT_HEIGHT = 1_024;
  private static final int PLAIN_TONE = 246;

  private TopographicReliefAssetBuilder() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected <natural-earth-relief.tif> <strategic-regions.geojson> <output-directory>");
    }
    build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
  }

  static void build(Path reliefPath, Path regionsPath, Path outputDirectory) throws IOException {
    BufferedImage source = ImageIO.read(reliefPath.toFile());
    if (source == null) {
      throw new IOException("Cannot decode Natural Earth relief " + reliefPath);
    }
    int[] relief = resampleLuminance(source, OUTPUT_WIDTH, OUTPUT_HEIGHT);
    int waterTone = mode(relief);
    int[] smallScale = boxBlur(relief, OUTPUT_WIDTH, OUTPUT_HEIGHT, 2);
    int[] broadScale = boxBlur(relief, OUTPUT_WIDTH, OUTPUT_HEIGHT, 10);
    JsonValue regions = new JsonReader().parse(Files.readString(regionsPath));
    boolean[] interiorLand = interiorLandMask(regions);

    BufferedImage base = baseRelief(relief, waterTone);
    BufferedImage ink = inkRelief(
        base, relief, smallScale, broadScale, waterTone, interiorLand);
    BufferedImage alignment = alignmentPreview(base, regions);

    Files.createDirectories(outputDirectory);
    write(base, outputDirectory.resolve("topographic-relief-base.png"));
    write(ink, outputDirectory.resolve("topographic-relief-ink.png"));
    write(alignment, outputDirectory.resolve("topographic-relief-alignment-preview.png"));
    System.out.printf(
        "Prepared %s: source=%dx%d output=%dx%d waterTone=%d%n",
        outputDirectory, source.getWidth(), source.getHeight(),
        OUTPUT_WIDTH, OUTPUT_HEIGHT, waterTone);
  }

  private static int[] resampleLuminance(BufferedImage source, int width, int height) {
    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D graphics = scaled.createGraphics();
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(
        RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.drawImage(source, 0, 0, width, height, null);
    graphics.dispose();
    int[] values = new int[width * height];
    scaled.getRaster().getSamples(0, 0, width, height, 0, values);
    return values;
  }

  private static BufferedImage baseRelief(int[] relief, int waterTone) {
    BufferedImage base = new BufferedImage(
        OUTPUT_WIDTH, OUTPUT_HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
    WritableRaster raster = base.getRaster();
    for (int index = 0; index < relief.length; index++) {
      int value = clamp((int) Math.round(PLAIN_TONE + (relief[index] - waterTone) * 1.45));
      raster.setSample(index % OUTPUT_WIDTH, index / OUTPUT_WIDTH, 0, value);
    }
    return toRgb(base);
  }

  private static BufferedImage inkRelief(
      BufferedImage base,
      int[] relief,
      int[] smallScale,
      int[] broadScale,
      int waterTone,
      boolean[] interiorLand) {
    BufferedImage ink = copy(base);
    addTerrainEdges(ink, relief, smallScale, broadScale, waterTone, interiorLand);
    Graphics2D graphics = ink.createGraphics();
    graphics.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    for (int gridY = 7; gridY < OUTPUT_HEIGHT - 7; gridY += 4) {
      for (int gridX = 7; gridX < OUTPUT_WIDTH - 7; gridX += 4) {
        int hash = hash(gridX, gridY);
        int x = gridX + Math.floorMod(hash, 5) - 2;
        int y = gridY + Math.floorMod(hash >>> 8, 5) - 2;
        int index = y * OUTPUT_WIDTH + x;
        if (!interiorLand[index]) {
          continue;
        }
        int localGradientX = smallScale[index + 2] - smallScale[index - 2];
        int localGradientY = smallScale[index + OUTPUT_WIDTH * 2]
            - smallScale[index - OUTPUT_WIDTH * 2];
        int directionX = broadScale[index + 6] - broadScale[index - 6];
        int directionY = broadScale[index + OUTPUT_WIDTH * 6]
            - broadScale[index - OUTPUT_WIDTH * 6];
        if (directionX == 0 && directionY == 0) {
          directionX = localGradientX;
          directionY = localGradientY;
        }
        double gradient = Math.hypot(localGradientX, localGradientY);
        double structure = Math.abs(smallScale[index] - broadScale[index]);
        double terrain = Math.abs(relief[index] - waterTone);
        double strength = gradient * 0.48 + structure * 0.90 + terrain * 0.08;
        if (strength < 5.5
            || unit(hash >>> 16) > Math.min(0.68, (strength - 3.5) / 25.0)) {
          continue;
        }
        double angle = Math.atan2(directionY, directionX);
        double length = Math.min(8.5, 3.0 + strength * 0.15);
        double halfX = Math.cos(angle) * length * 0.5;
        double halfY = Math.sin(angle) * length * 0.5;
        double bend = (unit(hash >>> 24) - 0.5) * 0.8;
        Path2D stroke = new Path2D.Double();
        stroke.moveTo(x - halfX, y - halfY);
        stroke.quadTo(
            x - Math.sin(angle) * bend,
            y + Math.cos(angle) * bend,
            x + halfX,
            y + halfY);
        float width = (float) Math.min(0.98, 0.52 + strength * 0.012);
        int alpha = Math.min(110, 42 + (int) Math.round(strength * 1.6));
        graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(22, 22, 22, alpha));
        graphics.draw(stroke);
      }
    }
    graphics.dispose();
    return ink;
  }

  private static void addTerrainEdges(
      BufferedImage image,
      int[] relief,
      int[] smallScale,
      int[] broadScale,
      int waterTone,
      boolean[] interiorLand) {
    WritableRaster raster = image.getRaster();
    int[] pixel = new int[3];
    for (int y = 1; y < OUTPUT_HEIGHT - 1; y++) {
      for (int x = 1; x < OUTPUT_WIDTH - 1; x++) {
        int index = y * OUTPUT_WIDTH + x;
        if (!interiorLand[index] || Math.abs(relief[index] - waterTone) < 2) {
          continue;
        }
        int fineEdge = Math.max(0, Math.abs(relief[index] - smallScale[index]) - 1);
        int broadEdge = Math.max(0, Math.abs(smallScale[index] - broadScale[index]) - 2);
        int ink = Math.min(42, fineEdge * 3 + broadEdge);
        if (ink == 0) {
          continue;
        }
        raster.getPixel(x, y, pixel);
        int value = Math.max(58, pixel[0] - ink);
        pixel[0] = value;
        pixel[1] = value;
        pixel[2] = value;
        raster.setPixel(x, y, pixel);
      }
    }
  }

  private static BufferedImage alignmentPreview(BufferedImage base, JsonValue regions) {
    BufferedImage preview = copy(base);
    Graphics2D graphics = preview.createGraphics();
    graphics.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setColor(new Color(180, 20, 45, 210));
    graphics.setStroke(new BasicStroke(0.8f));
    for (JsonValue feature = regions.get("features").child;
         feature != null;
         feature = feature.next) {
      drawGeometry(graphics, feature.get("geometry"));
    }
    graphics.dispose();
    return preview;
  }

  private static boolean[] interiorLandMask(JsonValue regions) {
    BufferedImage mask = new BufferedImage(
        OUTPUT_WIDTH, OUTPUT_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
    Graphics2D graphics = mask.createGraphics();
    graphics.setColor(Color.WHITE);
    for (JsonValue feature = regions.get("features").child;
         feature != null;
         feature = feature.next) {
      fillGeometry(graphics, feature.get("geometry"));
    }
    graphics.dispose();
    boolean[] interior = new boolean[OUTPUT_WIDTH * OUTPUT_HEIGHT];
    WritableRaster raster = mask.getRaster();
    for (int y = 3; y < OUTPUT_HEIGHT - 3; y++) {
      for (int x = 3; x < OUTPUT_WIDTH - 3; x++) {
        boolean land = true;
        for (int offsetY = -3; offsetY <= 3 && land; offsetY++) {
          for (int offsetX = -3; offsetX <= 3; offsetX++) {
            if (raster.getSample(x + offsetX, y + offsetY, 0) == 0) {
              land = false;
              break;
            }
          }
        }
        interior[y * OUTPUT_WIDTH + x] = land;
      }
    }
    return interior;
  }

  private static void fillGeometry(Graphics2D graphics, JsonValue geometry) {
    String type = geometry.getString("type");
    JsonValue coordinates = geometry.get("coordinates");
    if (type.equals("Polygon")) {
      graphics.fill(polygonPath(coordinates));
      return;
    }
    for (JsonValue polygon = coordinates.child; polygon != null; polygon = polygon.next) {
      graphics.fill(polygonPath(polygon));
    }
  }

  private static Path2D polygonPath(JsonValue polygon) {
    Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
    for (JsonValue ring = polygon.child; ring != null; ring = ring.next) {
      double previousLongitude = Double.NaN;
      for (JsonValue coordinate = ring.child;
           coordinate != null;
           coordinate = coordinate.next) {
        double longitude = coordinate.getDouble(0);
        double latitude = coordinate.getDouble(1);
        double x = pixelX(longitude, OUTPUT_WIDTH);
        double y = pixelY(latitude, OUTPUT_HEIGHT);
        if (Double.isNaN(previousLongitude)
            || Math.abs(longitude - previousLongitude) > 180.0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
        previousLongitude = longitude;
      }
      path.closePath();
    }
    return path;
  }

  private static void drawGeometry(Graphics2D graphics, JsonValue geometry) {
    String type = geometry.getString("type");
    JsonValue coordinates = geometry.get("coordinates");
    if (type.equals("Polygon")) {
      drawPolygon(graphics, coordinates);
      return;
    }
    for (JsonValue polygon = coordinates.child; polygon != null; polygon = polygon.next) {
      drawPolygon(graphics, polygon);
    }
  }

  private static void drawPolygon(Graphics2D graphics, JsonValue polygon) {
    for (JsonValue ring = polygon.child; ring != null; ring = ring.next) {
      Path2D path = new Path2D.Double();
      double previousLongitude = Double.NaN;
      for (JsonValue coordinate = ring.child;
           coordinate != null;
           coordinate = coordinate.next) {
        double longitude = coordinate.getDouble(0);
        double latitude = coordinate.getDouble(1);
        double x = pixelX(longitude, OUTPUT_WIDTH);
        double y = pixelY(latitude, OUTPUT_HEIGHT);
        if (Double.isNaN(previousLongitude)
            || Math.abs(longitude - previousLongitude) > 180.0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
        previousLongitude = longitude;
      }
      graphics.draw(path);
    }
  }

  static double pixelX(double longitude, int width) {
    return (longitude + 180.0) * width / 360.0;
  }

  static double pixelY(double latitude, int height) {
    return (90.0 - latitude) * height / 180.0;
  }

  private static int[] boxBlur(int[] source, int width, int height, int radius) {
    int[] horizontal = new int[source.length];
    int[] result = new int[source.length];
    int diameter = radius * 2 + 1;
    for (int y = 0; y < height; y++) {
      int sum = 0;
      for (int offset = -radius; offset <= radius; offset++) {
        sum += source[y * width + clampIndex(offset, width)];
      }
      for (int x = 0; x < width; x++) {
        horizontal[y * width + x] = sum / diameter;
        sum -= source[y * width + clampIndex(x - radius, width)];
        sum += source[y * width + clampIndex(x + radius + 1, width)];
      }
    }
    for (int x = 0; x < width; x++) {
      int sum = 0;
      for (int offset = -radius; offset <= radius; offset++) {
        sum += horizontal[clampIndex(offset, height) * width + x];
      }
      for (int y = 0; y < height; y++) {
        result[y * width + x] = sum / diameter;
        sum -= horizontal[clampIndex(y - radius, height) * width + x];
        sum += horizontal[clampIndex(y + radius + 1, height) * width + x];
      }
    }
    return result;
  }

  private static int mode(int[] values) {
    int[] counts = new int[256];
    for (int value : values) {
      counts[value]++;
    }
    int mode = 0;
    for (int value = 1; value < counts.length; value++) {
      if (counts[value] > counts[mode]) {
        mode = value;
      }
    }
    return mode;
  }

  private static BufferedImage copy(BufferedImage source) {
    BufferedImage copy = new BufferedImage(
        source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = copy.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return copy;
  }

  private static BufferedImage toRgb(BufferedImage source) {
    return copy(source);
  }

  private static void write(BufferedImage image, Path destination) throws IOException {
    if (!ImageIO.write(image, "png", destination.toFile())) {
      throw new IOException("PNG writer unavailable for " + destination);
    }
  }

  private static int hash(int x, int y) {
    int value = x * 0x1f1f1f1f ^ y * 0x6d2b79f5;
    value ^= value >>> 16;
    value *= 0x7feb352d;
    value ^= value >>> 15;
    return value;
  }

  private static double unit(int value) {
    return (value & 0xff) / 255.0;
  }

  private static int clampIndex(int value, int size) {
    return Math.max(0, Math.min(size - 1, value));
  }

  private static int clamp(int value) {
    return Math.max(72, Math.min(255, value));
  }
}
