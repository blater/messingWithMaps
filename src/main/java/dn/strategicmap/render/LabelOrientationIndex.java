package dn.strategicmap.render;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Build-prepared fixed label orientations keyed by canonical label ID. */
final class LabelOrientationIndex {
  private static final String RESOURCE = "/presentation/prepared-label-orientations-1895.tsv";
  private final Map<String, Float> degreesByLabelKey;

  private LabelOrientationIndex(Map<String, Float> degreesByLabelKey) {
    this.degreesByLabelKey = degreesByLabelKey;
  }

  static LabelOrientationIndex loadDefault() {
    InputStream input = LabelOrientationIndex.class.getResourceAsStream(RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Prepared label orientations not found: " + RESOURCE);
    }
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      Map<String, Float> rotations = new HashMap<>();
      reader.readLine();
      String line;
      while ((line = reader.readLine()) != null) {
        String[] fields = line.split("\\t", -1);
        rotations.put(fields[0], Float.parseFloat(fields[1]));
      }
      return new LabelOrientationIndex(rotations);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Unable to load prepared label orientations", exception);
    }
  }

  float rotationDegrees(String labelKey) {
    Float rotation = degreesByLabelKey.get(labelKey);
    return rotation == null ? 0.0f : rotation;
  }
}
