package dn.strategicmap.pipeline;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dn.strategicmap.label.LabelLayoutOverride;
import dn.strategicmap.label.LabelLayoutOverrideTsv;
import dn.strategicmap.label.PreparedLabelGlyph;
import dn.strategicmap.label.PreparedLabelGlyphTransforms;
import dn.strategicmap.label.PreparedLabelGlyphTsv;

/** Shapes reviewed label lines onto their fixed map-space baselines at build time. */
public final class PreparedLabelGlyphAssetBuilder {
  private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);

  private PreparedLabelGlyphAssetBuilder() {}

  public static void main(String[] args) throws IOException, FontFormatException {
    if (args.length != 4 && args.length != 5) {
      throw new IllegalArgumentException(
          "Expected <prepared-label-lines.tsv> <serif.ttf> <italic.ttf> <output.tsv>"
              + " [<layout-overrides.tsv>]");
    }
    List<LabelLine> lines = LabelLine.read(Path.of(args[0]));
    Font serif = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[1]).toFile()).deriveFont(1_000f);
    Font italic = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[2]).toFile()).deriveFont(1_000f);
    Path output = Path.of(args[3]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    List<PreparedLabelGlyph> prepared = new ArrayList<>();
    Map<LineVariantKey, Integer> nextLineIndex = new HashMap<>();
    for (LabelLine line : lines) {
      int lineIndex = nextLineIndex.merge(
          new LineVariantKey(line.labelKey(), line.variant()), 1, Integer::sum) - 1;
      Font font = line.fontFace() == FontFace.ITALIC ? italic : serif;
      List<Glyph> lineGlyphs = shape(line, font);
      for (int glyphIndex = 0; glyphIndex < lineGlyphs.size(); glyphIndex++) {
        Glyph glyph = lineGlyphs.get(glyphIndex);
        prepared.add(new PreparedLabelGlyph(
            line.labelKey(), line.variant(), line.fontFace().name(), lineIndex, glyphIndex,
            glyph.character(),
            glyph.x(), glyph.y(), (float) glyph.rotationDegrees(),
            (float) glyph.fontScaleMapUnits()));
      }
    }
    Map<String, LabelLayoutOverride> overrides = args.length == 5
        ? LabelLayoutOverrideTsv.read(Path.of(args[4])) : Map.of();
    PreparedLabelGlyphTsv.write(output, PreparedLabelGlyphTransforms.apply(prepared, overrides));
    System.out.printf("Prepared %s: lines=%d%n", output, lines.size());
  }

  private static List<Glyph> shape(LabelLine line, Font font) {
    GlyphVector glyphVector = font.createGlyphVector(FONT_CONTEXT, line.text());
    double capHeight = font.getLineMetrics("H", FONT_CONTEXT).getAscent();
    double scale = line.capHeightMapUnits() / capHeight;
    double[] naturalAdvances = new double[line.text().length()];
    double[] placementAdvances = new double[line.text().length()];
    for (int index = 0; index < naturalAdvances.length; index++) {
      double start = glyphVector.getGlyphPosition(index).getX();
      double end = glyphVector.getGlyphPosition(index + 1).getX();
      naturalAdvances[index] = (end - start) * scale;
      placementAdvances[index] = naturalAdvances[index] + line.trackingMapUnits();
    }
    double placementSpan = 0.0;
    for (int index = 0; index + 1 < placementAdvances.length; index++) {
      placementSpan += placementAdvances[index];
    }
    double distance = 0.0;
    List<Glyph> result = new ArrayList<>(placementAdvances.length);
    for (int index = 0; index < placementAdvances.length; index++) {
      double pathDistance = placementSpan == 0.0
          ? 0.0 : line.path().length() * distance / placementSpan;
      PathSample sample = line.path().sample(pathDistance);
      result.add(new Glyph(
          String.valueOf(line.text().charAt(index)),
          sample.x(),
          sample.y(),
          sample.rotationDegrees(), scale));
      distance += placementAdvances[index];
    }
    return result;
  }

  private record Glyph(String character, double x, double y, double rotationDegrees,
                       double fontScaleMapUnits) {}

  private record LabelLine(
      String labelKey,
      String variant,
      FontFace fontFace,
      String text,
      double capHeightMapUnits,
      double trackingMapUnits,
      Polyline path) {
    static List<LabelLine> read(Path source) throws IOException {
      List<LabelLine> lines = new ArrayList<>();
      for (String line : Files.readAllLines(source)) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        lines.add(fields.length == 6
            ? new LabelLine(
                fields[0], "DEFAULT", FontFace.valueOf(fields[1]), fields[2],
                Double.parseDouble(fields[3]), Double.parseDouble(fields[4]),
                Polyline.parse(fields[5]))
            : new LabelLine(
                fields[0], fields[1], FontFace.valueOf(fields[2]), fields[3],
                Double.parseDouble(fields[4]), Double.parseDouble(fields[5]),
                Polyline.parse(fields[6])));
      }
      return lines;
    }
  }

  private record LineVariantKey(String labelKey, String variant) {}

  private enum FontFace { SERIF, ITALIC }

  private record Point(double x, double y) {}

  private record Polyline(List<Point> points) {
    static Polyline parse(String value) {
      List<Point> points = new ArrayList<>();
      for (String coordinate : value.split(";")) {
        String[] fields = coordinate.split(",", -1);
        points.add(new Point(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])));
      }
      return new Polyline(points);
    }

    PathSample sample(double distance) {
      double remaining = Math.max(0.0, Math.min(distance, length()));
      for (int index = 1; index < points.size(); index++) {
        Point start = points.get(index - 1);
        Point end = points.get(index);
        double segment = Math.hypot(end.x() - start.x(), end.y() - start.y());
        if (remaining <= segment || index == points.size() - 1) {
          double fraction = segment == 0.0 ? 0.0 : remaining / segment;
          return new PathSample(
              start.x() + (end.x() - start.x()) * fraction,
              start.y() + (end.y() - start.y()) * fraction,
              Math.toDegrees(Math.atan2(end.y() - start.y(), end.x() - start.x())));
        }
        remaining -= segment;
      }
      throw new IllegalStateException("Label path needs at least two distinct points");
    }

    private double length() {
      double result = 0.0;
      for (int index = 1; index < points.size(); index++) {
        Point start = points.get(index - 1);
        Point end = points.get(index);
        result += Math.hypot(end.x() - start.x(), end.y() - start.y());
      }
      return result;
    }
  }

  private record PathSample(double x, double y, double rotationDegrees) {}
}
