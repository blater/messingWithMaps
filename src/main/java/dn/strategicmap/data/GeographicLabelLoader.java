package dn.strategicmap.data;

import dn.strategicmap.feature.GeographicLabel;
import dn.strategicmap.feature.GeographicLabelKind;
import dn.strategicmap.feature.MapRank;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Direct loader for the small curated geographic-label resource. */
public final class GeographicLabelLoader {
  private static final String DEFAULT_RESOURCE = "/maps/geographic-labels.tsv";

  private GeographicLabelLoader() {}

  public static List<GeographicLabel> loadDefault() {
    InputStream input = GeographicLabelLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Geographic label asset not found: " + DEFAULT_RESOURCE);
    }
    return load(input, DEFAULT_RESOURCE);
  }

  public static List<GeographicLabel> load(InputStream input, String sourceDescription) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      List<GeographicLabel> labels = new ArrayList<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        labels.add(new GeographicLabel(
            fields[0],
            fields[1],
            GeographicLabelKind.valueOf(fields[2]),
            Double.parseDouble(fields[3]),
            Double.parseDouble(fields[4]),
            MapRank.valueOf(fields[5]),
            fields[6]));
      }
      return labels;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load geographic labels from " + sourceDescription, exception);
    }
  }
}
