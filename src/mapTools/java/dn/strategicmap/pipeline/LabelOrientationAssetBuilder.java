package dn.strategicmap.pipeline;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Emits fixed label orientations so the render loop never chooses or changes label direction. */
public final class LabelOrientationAssetBuilder {
  private LabelOrientationAssetBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 5) {
      throw new IllegalArgumentException(
          "Expected <geographic-labels.tsv> <places.tsv> <political-labels.tsv> <overrides.tsv> <output.tsv>");
    }
    Map<String, Float> orientations = new LinkedHashMap<>();
    addKeys(Path.of(args[0]), "", orientations);
    addKeys(Path.of(args[1]), "", orientations);
    addKeys(Path.of(args[2]), "political.", orientations);
    for (String line : Files.readAllLines(Path.of(args[3]))) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith("labelKey\t")) {
        continue;
      }
      String[] fields = line.split("\\t", -1);
      orientations.put(fields[0], Float.parseFloat(fields[1]));
    }
    Path output = Path.of(args[4]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(output)) {
      writer.write("labelKey\trotationDegrees\n");
      for (Map.Entry<String, Float> entry : orientations.entrySet()) {
        writer.write(entry.getKey());
        writer.write('\t');
        writer.write(Float.toString(entry.getValue()));
        writer.write('\n');
      }
    }
    System.out.printf("Prepared %s: labels=%d%n", output, orientations.size());
  }

  private static void addKeys(Path source, String prefix, Map<String, Float> orientations)
      throws IOException {
    for (String line : Files.readAllLines(source)) {
      if (line.isBlank() || line.startsWith("#") || line.indexOf('\t') < 0
          || line.startsWith("labelId\t") || line.startsWith("placeId\t")
          || line.startsWith("actorId\t")) {
        continue;
      }
      String key = prefix + line.substring(0, line.indexOf('\t'));
      orientations.putIfAbsent(key, 0.0f);
    }
  }
}
