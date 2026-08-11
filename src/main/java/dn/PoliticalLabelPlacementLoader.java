package dn;

import dn.strategicmap.geometry.FlatMapProjection;
import dn.strategicmap.render.ZoomBand;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the small integration-owned catalogue of prepared political label placements. */
public final class PoliticalLabelPlacementLoader {
  private static final String DEFAULT_RESOURCE =
      "/presentation/political-labels-1895.tsv";

  private PoliticalLabelPlacementLoader() {}

  public static Map<String, PoliticalLabelPlacement> loadDefault() {
    InputStream input = PoliticalLabelPlacementLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
    if (input == null) {
      throw new IllegalStateException(
          "Political label placement asset not found: " + DEFAULT_RESOURCE);
    }
    return load(input, DEFAULT_RESOURCE);
  }

  static Map<String, PoliticalLabelPlacement> load(
      InputStream input, String sourceDescription) {
    FlatMapProjection projection = new FlatMapProjection();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      Map<String, PoliticalLabelPlacement> placements = new LinkedHashMap<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        String actorId = fields[0];
        var placement = new PoliticalLabelPlacement(
            actorId,
            projection.project(Double.parseDouble(fields[1]), Double.parseDouble(fields[2])),
            ZoomBand.valueOf(fields[3]),
            Float.parseFloat(fields[4]));
        if (placements.putIfAbsent(actorId, placement) != null) {
          throw new IllegalArgumentException("Duplicate actor ID " + actorId);
        }
      }
      return Map.copyOf(placements);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load political label placements from " + sourceDescription, exception);
    }
  }
}
