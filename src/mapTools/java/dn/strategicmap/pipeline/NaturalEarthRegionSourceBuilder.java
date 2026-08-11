package dn.strategicmap.pipeline;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies the flat strategic-region grouping to the pinned Natural Earth sources. */
public final class NaturalEarthRegionSourceBuilder {
  // Natural Earth's modern Admin-0 dataset includes present-day leases, buffer zones, and
  // disputed tracts. They were not separately playable regions in the 1880–95 presentation.
  private static final Set<String> EXCLUDED_MODERN_ADMIN0_CODES = Set.of(
      "BRT", "CNM", "CSI", "CYN", "ESB", "IOA", "IOT", "KAB", "KAS", "SPI", "UMI", "USG",
      "WSB");

  // Keep stable Natural Earth IDs while preventing modern constitutional terminology in labels.
  private static final Map<String, String> HISTORICAL_ADMIN0_NAMES = Map.of("HKG", "Hong Kong");

  private NaturalEarthRegionSourceBuilder() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Expected <admin-0.geojson> <admin-1.geojson> <grouping.tsv> <output.geojson>");
    }
    build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
  }

  private static void build(
      Path admin0Path, Path admin1Path, Path groupingPath, Path outputPath) throws IOException {
    Map<String, RegionGroup> groupBySourceCode = readGroups(groupingPath);
    Set<String> groupedCountries = new HashSet<>();
    for (RegionGroup group : groupBySourceCode.values()) {
      groupedCountries.add(group.countryCode());
    }
    JsonValue admin0 = parse(admin0Path);
    JsonValue admin1 = parse(admin1Path);
    List<SelectedFeature> selected = new ArrayList<>();
    Set<String> resolvedSourceCodes = new HashSet<>();

    for (JsonValue feature = admin0.get("features").child;
         feature != null;
         feature = feature.next) {
      JsonValue properties = feature.get("properties");
      String countryCode = properties.getString("ADM0_A3");
      if (!countryCode.equals("ATA")
          && !EXCLUDED_MODERN_ADMIN0_CODES.contains(countryCode)
          && !groupedCountries.contains(countryCode)) {
        selected.add(new SelectedFeature(
            countryCode,
            HISTORICAL_ADMIN0_NAMES.getOrDefault(countryCode, properties.getString("ADMIN")),
            feature.get("geometry")));
      }
    }

    for (JsonValue feature = admin1.get("features").child;
         feature != null;
         feature = feature.next) {
      JsonValue properties = feature.get("properties");
      String sourceCode = properties.getString("adm1_code");
      String countryCode = properties.getString("adm0_a3");
      RegionGroup group = groupBySourceCode.get(sourceCode);
      if (group == null && groupedCountries.contains(countryCode)) {
        throw new IllegalStateException(
            "No strategic region grouping for " + sourceCode + " in " + countryCode);
      }
      if (group != null) {
        if (!countryCode.equals(group.countryCode())) {
          throw new IllegalStateException(
              sourceCode + " belongs to " + countryCode + ", not " + group.countryCode());
        }
        resolvedSourceCodes.add(sourceCode);
        selected.add(new SelectedFeature(
            group.regionId(),
            group.displayName(),
            feature.get("geometry")));
      }
    }

    for (String sourceCode : groupBySourceCode.keySet()) {
      if (!resolvedSourceCodes.contains(sourceCode)) {
        throw new IllegalStateException(
            "Cannot resolve Natural Earth Admin-1 source code " + sourceCode);
      }
    }

    Files.createDirectories(outputPath.toAbsolutePath().getParent());
    write(selected, outputPath);
    int regionCount = new HashSet<>(selected.stream()
        .map(SelectedFeature::regionId)
        .toList()).size();
    System.out.printf("Prepared %s: regions=%d features=%d groupedCountries=%d%n",
        outputPath, regionCount, selected.size(), groupedCountries.size());
  }

  private static JsonValue parse(Path path) throws IOException {
    try {
      return new JsonReader().parse(Files.readString(path));
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Failed parsing " + path, failure);
    }
  }

  private static Map<String, RegionGroup> readGroups(Path path) throws IOException {
    Map<String, RegionGroup> groupBySourceCode = new LinkedHashMap<>();
    for (String line : Files.readAllLines(path)) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith("countryCode\t")) {
        continue;
      }
      String[] fields = line.split("\t", -1);
      if (fields.length != 4) {
        throw new IOException("Cannot read strategic region grouping row: " + line);
      }
      RegionGroup group = new RegionGroup(fields[0], fields[1], fields[2]);
      for (String sourceCode : fields[3].split(",")) {
        if (groupBySourceCode.put(sourceCode, group) != null) {
          throw new IllegalStateException(
              "Natural Earth Admin-1 source code assigned twice: " + sourceCode);
        }
      }
    }
    return groupBySourceCode;
  }

  /** O(selected source bytes); this runs only when source data or the grouping changes. */
  private static void write(List<SelectedFeature> selected, Path outputPath) throws IOException {
    try (BufferedWriter output = Files.newBufferedWriter(outputPath)) {
      output.write("{\"type\":\"FeatureCollection\",\"name\":\"natural-earth-strategic-regions\",\"features\":[");
      for (int index = 0; index < selected.size(); index++) {
        if (index > 0) {
          output.write(',');
        }
        SelectedFeature feature = selected.get(index);
        output.write("{\"type\":\"Feature\",\"properties\":{\"regionId\":");
        output.write(jsonString(feature.regionId()));
        output.write(",\"displayName\":");
        output.write(jsonString(feature.displayName()));
        output.write("},\"geometry\":");
        output.write(feature.geometry().toJson(JsonWriter.OutputType.json));
        output.write('}');
      }
      output.write("]}");
    }
  }

  private static String jsonString(String value) {
    return JsonWriter.OutputType.json.quoteValue(value);
  }

  private record SelectedFeature(String regionId, String displayName, JsonValue geometry) {}

  private record RegionGroup(String countryCode, String regionId, String displayName) {}
}
