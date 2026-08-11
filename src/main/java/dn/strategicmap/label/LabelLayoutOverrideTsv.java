package dn.strategicmap.label;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Stable TSV boundary shared by the development editor and offline glyph preparation. */
public final class LabelLayoutOverrideTsv {
  private static final String HEADER = "labelKey\ttrackingDeltaMapUnits\toffsetMapX\toffsetMapY"
      + "\trotationDeltaDegrees\tfontScaleMultiplier\thidden\tminimumBandOverride";

  private LabelLayoutOverrideTsv() {}

  public static Map<String, LabelLayoutOverride> read(Path source) throws IOException {
    if (!Files.exists(source)) {
      return new LinkedHashMap<>();
    }
    return parse(Files.readAllLines(source));
  }

  public static Map<String, LabelLayoutOverride> read(InputStream input) throws IOException {
    try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
         var buffered = new java.io.BufferedReader(reader)) {
      return parse(buffered.lines().toList());
    }
  }

  private static Map<String, LabelLayoutOverride> parse(Iterable<String> lines) {
    Map<String, LabelLayoutOverride> overrides = new LinkedHashMap<>();
    for (String line : lines) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      overrides.put(fields[0], new LabelLayoutOverride(
          Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
          Double.parseDouble(fields[3]), Double.parseDouble(fields[4]),
          Double.parseDouble(fields[5]),
          fields.length >= 7 && Boolean.parseBoolean(fields[6]),
          fields.length >= 8
              ? LabelZoomBandOverride.valueOf(fields[7])
              : LabelZoomBandOverride.DEFAULT));
    }
    return overrides;
  }

  public static void write(Path destination, Map<String, LabelLayoutOverride> overrides)
      throws IOException {
    Files.createDirectories(destination.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(destination)) {
      writer.write("# Development-authored label deltas. The build prepares immutable glyphs.\n");
      writer.write(HEADER);
      writer.write('\n');
      for (var entry : new TreeMap<>(overrides).entrySet()) {
        LabelLayoutOverride value = entry.getValue();
        if (value.isIdentity()) {
          continue;
        }
        writer.write(entry.getKey());
        writer.write('\t');
        writer.write(Double.toString(value.trackingDeltaMapUnits()));
        writer.write('\t');
        writer.write(Double.toString(value.offsetMapX()));
        writer.write('\t');
        writer.write(Double.toString(value.offsetMapY()));
        writer.write('\t');
        writer.write(Double.toString(value.rotationDeltaDegrees()));
        writer.write('\t');
        writer.write(Double.toString(value.fontScaleMultiplier()));
        writer.write('\t');
        writer.write(Boolean.toString(value.hidden()));
        writer.write('\t');
        writer.write(value.minimumBandOverride().name());
        writer.write('\n');
      }
    }
  }
}
