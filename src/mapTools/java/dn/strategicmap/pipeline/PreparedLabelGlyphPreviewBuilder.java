package dn.strategicmap.pipeline;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import dn.strategicmap.data.LoadedWorldMap;
import dn.strategicmap.data.WorldMapAssetLoader;
import dn.strategicmap.geometry.MapRing;

/** Produces a local visual review image from an exact prepared glyph asset. */
public final class PreparedLabelGlyphPreviewBuilder {
  private PreparedLabelGlyphPreviewBuilder() {}

  public static void main(String[] args) throws IOException, FontFormatException {
    if (args.length != 4 && args.length != 6 && args.length != 7 && args.length != 11) {
      throw new IllegalArgumentException(
          "Expected <glyphs.tsv> <serif.ttf> <italic.ttf> <output.png>"
              + " [<catalogue.tsv> <maximum-band> [<map.asset>"
              + " [<west> <south> <east> <north>]]]");
    }
    Set<String> includedKeys = args.length >= 6
        ? includedKeys(Path.of(args[4]), args[5]) : null;
    View view = args.length == 11
        ? new View(Double.parseDouble(args[7]), Double.parseDouble(args[8]),
            Double.parseDouble(args[9]), Double.parseDouble(args[10]))
        : View.WORLD;
    List<Glyph> glyphs = read(Path.of(args[0])).stream()
        .filter(glyph -> includedKeys == null || includedKeys.contains(glyph.labelKey()))
        .toList();
    Font serif = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[1]).toFile());
    Font italic = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[2]).toFile());
    BufferedImage image = new BufferedImage(1_800, 900, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(new Color(238, 231, 207));
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (args.length >= 7) {
      drawMap(graphics, image, view, Path.of(args[6]));
    }
    graphics.setColor(new Color(182, 189, 184));
    for (int longitude = firstGridLine(view.west(), 30); longitude <= view.east(); longitude += 30) {
      int x = view.pixelX(longitude, image);
      graphics.drawLine(x, 0, x, image.getHeight());
    }
    for (int latitude = firstGridLine(view.south(), 30); latitude <= view.north(); latitude += 30) {
      int y = view.pixelY(latitude, image);
      graphics.drawLine(0, y, image.getWidth(), y);
    }
    graphics.setColor(new Color(42, 35, 25));
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    for (Glyph glyph : glyphs) {
      Font font = glyph.face().equals("ITALIC") ? italic : serif;
      float pixelSize = (float) (glyph.fontScaleMapUnits() * 1_000.0
          * view.pixelsPerMapUnit(image));
      graphics.setFont(font.deriveFont(pixelSize));
      AffineTransform original = graphics.getTransform();
      int x = view.pixelX(glyph.mapX(), image);
      int y = view.pixelY(glyph.mapY(), image);
      graphics.rotate(Math.toRadians(-glyph.rotationDegrees()), x, y);
      graphics.drawString(glyph.character(), x, y);
      graphics.setTransform(original);
    }
    graphics.dispose();
    Path output = Path.of(args[3]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    ImageIO.write(image, "png", output.toFile());
    System.out.printf("Prepared %s: glyphs=%d%n", output, glyphs.size());
  }

  private static int firstGridLine(double minimum, int interval) {
    return (int) Math.ceil(minimum / interval) * interval;
  }

  /** Draws each source ring once; this is a build-only review path, never a frame loop. */
  private static void drawMap(
      Graphics2D graphics, BufferedImage image, View view, Path mapAsset) throws IOException {
    LoadedWorldMap world;
    try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(mapAsset.toFile()))) {
      world = WorldMapAssetLoader.load(input, mapAsset.toString());
    }
    graphics.setColor(new Color(218, 210, 180));
    for (int regionIndex = 0; regionIndex < world.regionCount(); regionIndex++) {
      for (var polygon : world.region(regionIndex).geometryParts()) {
        Path2D exterior = path(polygon.exterior(), image, view);
        graphics.fill(exterior);
        graphics.setColor(new Color(145, 139, 119));
        graphics.draw(exterior);
        graphics.setColor(new Color(218, 210, 180));
      }
    }
  }

  private static Path2D path(MapRing ring, BufferedImage image, View view) {
    Path2D path = new Path2D.Double();
    boolean started = false;
    double previousX = Double.NaN;
    for (var point : ring.points()) {
      double x = point.x();
      int pixelX = view.pixelX(x, image);
      int pixelY = view.pixelY(point.y(), image);
      if (!started || Math.abs(x - previousX) > 180.0) {
        path.moveTo(pixelX, pixelY);
        started = true;
      } else {
        path.lineTo(pixelX, pixelY);
      }
      previousX = x;
    }
    path.closePath();
    return path;
  }

  private static List<Glyph> read(Path source) throws IOException {
    List<Glyph> glyphs = new ArrayList<>();
    for (String line : Files.readAllLines(source)) {
      if (line.startsWith("labelKey\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      glyphs.add(new Glyph(fields[0], fields[2], fields[5].equals("\\s") ? " " : fields[5],
          Double.parseDouble(fields[6]), Double.parseDouble(fields[7]),
          Double.parseDouble(fields[8]), Double.parseDouble(fields[9])));
    }
    return glyphs;
  }

  private static Set<String> includedKeys(Path catalogue, String maximumBand) throws IOException {
    List<String> lines = Files.readAllLines(catalogue);
    String[] header = lines.getFirst().split("\\t", -1);
    int keyColumn = List.of(header).indexOf("labelKey");
    int bandColumn = List.of(header).indexOf("minimumBand");
    int maximumOrdinal = bandOrdinal(maximumBand);
    Set<String> keys = new HashSet<>();
    for (int index = 1; index < lines.size(); index++) {
      String[] fields = lines.get(index).split("\\t", -1);
      if (bandOrdinal(fields[bandColumn]) <= maximumOrdinal) {
        keys.add(fields[keyColumn]);
      }
    }
    return keys;
  }

  private static int bandOrdinal(String band) {
    return switch (band) {
      case "WORLD" -> 0;
      case "GRAND" -> 1;
      case "THEATRE" -> 2;
      default -> throw new IllegalArgumentException("Unsupported preview band: " + band);
    };
  }

  private record Glyph(String labelKey, String face, String character, double mapX, double mapY,
                       double rotationDegrees, double fontScaleMapUnits) {}

  private record View(double west, double south, double east, double north) {
    private static final View WORLD = new View(-180.0, -90.0, 180.0, 90.0);

    double pixelsPerMapUnit(BufferedImage image) {
      return Math.min(image.getWidth() / (east - west), image.getHeight() / (north - south));
    }

    int pixelX(double mapX, BufferedImage image) {
      double scale = pixelsPerMapUnit(image);
      double left = (image.getWidth() - (east - west) * scale) * 0.5;
      return (int) Math.round(left + (mapX - west) * scale);
    }

    int pixelY(double mapY, BufferedImage image) {
      double scale = pixelsPerMapUnit(image);
      double top = (image.getHeight() - (north - south) * scale) * 0.5;
      return (int) Math.round(top + (north - mapY) * scale);
    }
  }
}
