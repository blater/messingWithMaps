package dn.strategicmap.data;

import dn.strategicmap.feature.CityStanding;
import dn.strategicmap.feature.MapRank;
import dn.strategicmap.feature.PlaceFeature;
import dn.strategicmap.feature.PlaceKind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Direct loader for the curated late-nineteenth-century place catalogue. */
public final class PlaceFeatureLoader {
  private static final String DEFAULT_RESOURCE = "/maps/places-1895.tsv";

  private PlaceFeatureLoader() {}

  public static List<PlaceFeature> loadDefault() {
    InputStream input = PlaceFeatureLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
    if (input == null) {
      throw new IllegalStateException("Place asset not found: " + DEFAULT_RESOURCE);
    }
    return load(input, DEFAULT_RESOURCE);
  }

  public static List<PlaceFeature> load(InputStream input, String sourceDescription) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      List<PlaceFeature> places = new ArrayList<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        places.add(new PlaceFeature(
            fields[0],
            fields[1],
            Double.parseDouble(fields[2]),
            Double.parseDouble(fields[3]),
            kinds(fields[4]),
            MapRank.valueOf(fields[5]),
            fields[6],
            fields[7],
            CityStanding.valueOf(fields[8])));
      }
      return places;
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load places from " + sourceDescription, exception);
    }
  }

  private static Set<PlaceKind> kinds(String field) {
    EnumSet<PlaceKind> kinds = EnumSet.noneOf(PlaceKind.class);
    for (String value : field.split(",")) {
      kinds.add(PlaceKind.valueOf(value));
    }
    return kinds;
  }
}
