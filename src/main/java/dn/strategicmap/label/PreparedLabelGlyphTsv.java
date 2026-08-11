package dn.strategicmap.label;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** File boundary for immutable prepared glyph coordinates. */
public final class PreparedLabelGlyphTsv {
  public static final String HEADER = "labelKey\tvariant\tfontFace\tlineIndex\tglyphIndex\tglyph"
      + "\tbaselineMapX\tbaselineMapY\trotationDegrees\tfontScaleMapUnits";

  private PreparedLabelGlyphTsv() {}

  public static List<PreparedLabelGlyph> read(Path source) throws IOException {
    return parse(Files.readAllLines(source));
  }

  public static List<PreparedLabelGlyph> read(InputStream input) throws IOException {
    try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
         var buffered = new java.io.BufferedReader(reader)) {
      return parse(buffered.lines().toList());
    }
  }

  private static List<PreparedLabelGlyph> parse(List<String> lines) {
    List<PreparedLabelGlyph> glyphs = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank() || line.startsWith("#") || line.equals(HEADER)) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      glyphs.add(new PreparedLabelGlyph(
          fields[0], fields[1], fields[2], Integer.parseInt(fields[3]),
          Integer.parseInt(fields[4]), unescape(fields[5]), Double.parseDouble(fields[6]),
          Double.parseDouble(fields[7]), Float.parseFloat(fields[8]),
          Float.parseFloat(fields[9])));
    }
    return glyphs;
  }

  public static void write(Path destination, List<PreparedLabelGlyph> glyphs) throws IOException {
    Files.createDirectories(destination.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(destination)) {
      writer.write(HEADER);
      writer.write('\n');
      for (PreparedLabelGlyph glyph : glyphs) {
        writer.write(glyph.labelKey() + "\t" + glyph.variant() + "\t" + glyph.fontFace()
            + "\t" + glyph.lineIndex()
            + "\t" + glyph.glyphIndex() + "\t" + escape(glyph.character()) + "\t"
            + glyph.mapX() + "\t" + glyph.mapY() + "\t" + glyph.rotationDegrees() + "\t"
            + glyph.fontScaleMapUnits() + "\n");
      }
    }
  }

  private static String escape(String value) {
    return value.equals(" ") ? "\\s" : value;
  }

  private static String unescape(String value) {
    return value.equals("\\s") ? " " : value;
  }
}
