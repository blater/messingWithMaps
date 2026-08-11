package dn;

import dn.strategicmap.feature.MapRank;
import dn.strategicmap.feature.CityStanding;
import dn.strategicmap.render.MapPresentationPolicy;
import dn.strategicmap.render.ZoomBand;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Integration-owned build adapter for the complete fixed strategic-label glyph catalogue.
 *
 * <p>The reviewed reference-derived GRAND/THEATRE baselines remain authoritative. Later bands
 * start from the catalogue anchor, fixed authoring orientation, period typography, and a subtle
 * category-appropriate curve. The resulting line file is an authoring/build artefact; runtime
 * receives only shaped glyph coordinates. This pass is linear in 90 physical labels, 245 places,
 * and 59 actors and never runs in a frame path.</p>
 */
public final class StrategicLabelLineAssetBuilder {
  private static final String DEFAULT_VARIANT = "DEFAULT";
  private static final String CAPITAL_VARIANT = "CAPITAL";

  private StrategicLabelLineAssetBuilder() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 8) {
      throw new IllegalArgumentException(
          "Expected <reviewed-lines.tsv> <geographic-labels.tsv> <places.tsv>"
              + " <political-placements.tsv> <actors.tsv> <dependencies.tsv>"
              + " <orientation-overrides.tsv> <output.tsv>");
    }
    build(
        Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
        Path.of(args[4]), Path.of(args[5]), Path.of(args[6]), Path.of(args[7]));
  }

  static void build(
      Path reviewedLinesPath,
      Path geographicLabelsPath,
      Path placesPath,
      Path politicalPlacementsPath,
      Path actorsPath,
      Path dependenciesPath,
      Path orientationsPath,
      Path outputPath) throws IOException {
    List<PreparedLine> lines = new ArrayList<>();
    Set<String> reviewedKeys = readReviewedLines(reviewedLinesPath, lines);
    Map<String, Double> orientations = readOrientations(orientationsPath);
    addGeographicLines(geographicLabelsPath, reviewedKeys, orientations, lines);

    List<Place> places = readPlaces(placesPath);
    Map<String, Place> capitalPlacesByRegion = capitalPlacesByRegion(places);
    Set<String> capitalPlaceIds = addPoliticalLines(
        politicalPlacementsPath, actorsPath, dependenciesPath, capitalPlacesByRegion,
        reviewedKeys, orientations, lines);
    addPlaceLines(places, orientations, lines);

    Files.createDirectories(outputPath.toAbsolutePath().getParent());
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
      writer.write("labelKey\tvariant\tfontFace\ttext\tcapHeightMapUnits"
          + "\ttrackingMapUnits\tpathMapCoordinates\n");
      for (PreparedLine line : lines) {
        writer.write(line.labelKey() + "\t" + line.variant() + "\t" + line.fontFace()
            + "\t" + line.text() + "\t" + line.capHeightMapUnits() + "\t"
            + line.trackingMapUnits() + "\t" + line.pathMapCoordinates() + "\n");
      }
    }
    System.out.printf(Locale.ROOT,
        "Prepared %s: labels=%d lines=%d reviewed=%d capitals=%d%n",
        outputPath, lines.stream().map(PreparedLine::labelKey).distinct().count(), lines.size(),
        reviewedKeys.size(), capitalPlaceIds.size());
  }

  private static Set<String> readReviewedLines(Path source, List<PreparedLine> destination)
      throws IOException {
    Set<String> keys = new HashSet<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "labelKey\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      destination.add(new PreparedLine(
          fields[0], DEFAULT_VARIANT, fields[1], fields[2], Double.parseDouble(fields[3]),
          Double.parseDouble(fields[4]), fields[5]));
      keys.add(fields[0]);
    }
    return keys;
  }

  private static void addGeographicLines(
      Path source,
      Set<String> reviewedKeys,
      Map<String, Double> orientations,
      List<PreparedLine> destination) throws IOException {
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "labelId\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      String labelKey = fields[0];
      if (reviewedKeys.contains(labelKey)) {
        continue;
      }
      String kind = fields[2];
      ZoomBand band = MapPresentationPolicy.minimumBand(MapRank.valueOf(fields[5]));
      boolean sea = kind.equals("OCEAN") || kind.equals("SEA");
      String text = sea ? fields[1].toUpperCase(Locale.ROOT) : fields[1];
      double capHeight = geographicCapHeight(band, sea);
      double tracking = sea ? capHeight * 0.10 : 0.0;
      double angle = orientations.getOrDefault(labelKey, 0.0);
      double bend = curveBend(labelKey, kind, capHeight);
      destination.add(line(
          labelKey, DEFAULT_VARIANT, sea ? "SERIF" : "ITALIC", text, capHeight, tracking,
          Double.parseDouble(fields[4]), Double.parseDouble(fields[3]), angle, bend));
    }
  }

  private static Set<String> addPoliticalLines(
      Path placementsPath,
      Path actorsPath,
      Path dependenciesPath,
      Map<String, Place> capitalPlacesByRegion,
      Set<String> reviewedKeys,
      Map<String, Double> orientations,
      List<PreparedLine> destination) throws IOException {
    Map<String, Placement> placements = readPlacements(placementsPath);
    LinkedHashMap<String, Actor> actors = readActors(actorsPath);
    Map<String, Dependency> dependencies = readDependencies(dependenciesPath);
    Set<String> capitalPlaceIds = new HashSet<>();
    for (Actor actor : actors.values()) {
      Place capital = capitalPlacesByRegion.get(actor.capitalRegionId());
      if (capital == null) {
        throw new IllegalStateException(
            "No authored capital place in region " + actor.capitalRegionId()
                + " for " + actor.actorId());
      }
      capitalPlaceIds.add(capital.placeId());
      String labelKey = "political." + actor.actorId();
      if (reviewedKeys.contains(labelKey)) {
        continue;
      }
      Placement placement = placements.get(actor.actorId());
      double longitude = placement == null ? capital.longitude() : placement.longitude();
      double latitude = placement == null ? capital.latitude() : placement.latitude();
      ZoomBand band = placement == null ? ZoomBand.NATIONAL : placement.minimumBand();
      double angle = orientations.getOrDefault(
          labelKey, placement == null ? 0.0 : placement.rotationDegrees());
      Dependency dependency = dependencies.get(actor.actorId());
      List<String> textLines = politicalTextLines(actor, dependency, actors);
      double capHeight = politicalCapHeight(band, actor.playable());
      for (int lineIndex = 0; lineIndex < textLines.size(); lineIndex++) {
        boolean dependencyQualifier = dependency != null && lineIndex == textLines.size() - 1;
        double lineCapHeight = dependencyQualifier ? capHeight * 0.60 : capHeight;
        double lineOffset = (textLines.size() - 1) * 0.5 - lineIndex;
        double radians = Math.toRadians(angle);
        double offset = lineOffset * capHeight * 1.55;
        destination.add(line(
            labelKey, DEFAULT_VARIANT, "SERIF",
            textLines.get(lineIndex), lineCapHeight,
            0.0,
            longitude - Math.sin(radians) * offset,
            latitude + Math.cos(radians) * offset,
            angle, 0.0));
      }
    }
    return capitalPlaceIds;
  }

  private static void addPlaceLines(
      List<Place> places,
      Map<String, Double> orientations,
      List<PreparedLine> destination) {
    for (Place place : places) {
      ZoomBand band = MapPresentationPolicy.placeMinimumBand(place.rank());
      double angle = orientations.getOrDefault(place.placeId(), 0.0);
      double ordinaryHeight = placeCapHeight(band, false);
      destination.add(placeLine(
          place.placeId(), DEFAULT_VARIANT, "ITALIC", place.name(), ordinaryHeight, 0.0,
          place.longitude(), place.latitude(), angle, 0.0));
      double capitalHeight = placeCapHeight(band, true);
      String capitalText = place.cityStanding() == CityStanding.MINOR_CAPITAL
          ? place.name()
          : place.name().toUpperCase(Locale.ROOT);
      destination.add(placeLine(
          place.placeId(), CAPITAL_VARIANT, "SERIF", capitalText,
          capitalHeight, 0.0,
          place.longitude(), place.latitude(), angle, 0.0));
    }
  }

  private static PreparedLine placeLine(
      String labelKey,
      String variant,
      String fontFace,
      String text,
      double capHeight,
      double tracking,
      double anchorX,
      double anchorY,
      double angleDegrees,
      double bend) {
    double width = lineWidth(text, capHeight, tracking);
    double radians = Math.toRadians(angleDegrees);
    double centreDistance = width * 0.5 + capHeight * 0.55;
    return line(
        labelKey, variant, fontFace, text, capHeight, tracking,
        anchorX + Math.cos(radians) * centreDistance,
        anchorY + Math.sin(radians) * centreDistance,
        angleDegrees, bend);
  }

  private static PreparedLine line(
      String labelKey,
      String variant,
      String fontFace,
      String text,
      double capHeight,
      double tracking,
      double centreX,
      double centreY,
      double angleDegrees,
      double bend) {
    double width = lineWidth(text, capHeight, tracking);
    double radians = Math.toRadians(angleDegrees);
    double alongX = Math.cos(radians);
    double alongY = Math.sin(radians);
    double normalX = -alongY;
    double normalY = alongX;
    String path = point(centreX - alongX * width * 0.5, centreY - alongY * width * 0.5)
        + ";" + point(centreX + normalX * bend, centreY + normalY * bend)
        + ";" + point(centreX + alongX * width * 0.5, centreY + alongY * width * 0.5);
    return new PreparedLine(
        labelKey, variant, fontFace, text, capHeight, tracking, path);
  }

  private static double lineWidth(String text, double capHeight, double tracking) {
    double ems = 0.0;
    for (int index = 0; index < text.length(); index++) {
      ems += Character.isWhitespace(text.charAt(index)) ? 0.32 : 0.58;
    }
    return Math.max(capHeight * 2.5,
        capHeight * ems + tracking * Math.max(0, text.length() - 1));
  }

  private static String point(double x, double y) {
    return x + "," + y;
  }

  private static double curveBend(String labelKey, String kind, double capHeight) {
    if (kind.equals("ISLAND")) {
      return 0.0;
    }
    double magnitude = kind.equals("SEA") || kind.equals("OCEAN")
        ? capHeight * 0.32 : capHeight * 0.20;
    return (labelKey.hashCode() & 1) == 0 ? magnitude : -magnitude;
  }

  private static double geographicCapHeight(ZoomBand band, boolean sea) {
    double base = switch (band) {
      case NATIONAL -> 0.76;
      case REGIONAL -> 0.50;
      case LOCAL -> 0.30;
      case DETAIL -> 0.20;
      default -> throw new IllegalStateException("Reviewed band reached generated layout: " + band);
    };
    return sea ? base * 1.08 : base;
  }

  private static double politicalCapHeight(ZoomBand band, boolean primary) {
    double base = switch (band) {
      case NATIONAL -> 0.62;
      case REGIONAL -> 0.42;
      case LOCAL -> 0.26;
      case DETAIL -> 0.17;
      default -> 0.62;
    };
    return primary ? base * 1.15 : base;
  }

  private static double placeCapHeight(ZoomBand band, boolean capital) {
    double base = switch (band) {
      case NATIONAL -> 0.50;
      case REGIONAL -> 0.40;
      case LOCAL -> 0.25;
      case DETAIL -> 0.16;
      default -> throw new IllegalStateException("Unexpected place band: " + band);
    };
    return capital ? base * 1.20 : base;
  }

  private static List<String> politicalTextLines(
      Actor actor, Dependency dependency, Map<String, Actor> actors) {
    String name = withoutLeadingArticle(actor.displayName()).toUpperCase(Locale.ROOT);
    List<String> lines = splitNearCentre(name, 19);
    if (dependency == null) {
      return lines;
    }
    Actor suzerain = actors.get(dependency.suzerainActorId());
    lines = new ArrayList<>(lines);
    String suzerainName = withoutLeadingArticle(suzerain.displayName()).toUpperCase(Locale.ROOT);
    if (suzerainName.endsWith(" EMPIRE")) {
      suzerainName = suzerainName.substring(0, suzerainName.length() - " EMPIRE".length());
    }
    lines.add(suzerainName + " VASSAL");
    return lines;
  }

  private static String withoutLeadingArticle(String value) {
    return value.regionMatches(true, 0, "The ", 0, 4) ? value.substring(4) : value;
  }

  private static List<String> splitNearCentre(String text, int maximumLength) {
    if (text.length() <= maximumLength) {
      return List.of(text);
    }
    int centre = text.length() / 2;
    int split = -1;
    for (int distance = 0; distance < centre; distance++) {
      int left = centre - distance;
      int right = centre + distance;
      if (left > 0 && text.charAt(left) == ' ') {
        split = left;
        break;
      }
      if (right < text.length() && text.charAt(right) == ' ') {
        split = right;
        break;
      }
    }
    return split < 0 ? List.of(text) : List.of(text.substring(0, split), text.substring(split + 1));
  }

  private static List<Place> readPlaces(Path source) throws IOException {
    List<Place> places = new ArrayList<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "placeId\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      places.add(new Place(
          fields[0], fields[1], Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
          MapRank.valueOf(fields[5]), fields[6], CityStanding.valueOf(fields[8])));
    }
    return places;
  }

  private static Map<String, Place> capitalPlacesByRegion(List<Place> places) {
    Map<String, Place> selected = new HashMap<>();
    for (Place place : places) {
      Place current = selected.get(place.regionId());
      if (current == null || place.rank().ordinal() < current.rank().ordinal()) {
        selected.put(place.regionId(), place);
      }
    }
    return selected;
  }

  private static Map<String, Placement> readPlacements(Path source) throws IOException {
    Map<String, Placement> placements = new HashMap<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "actorId\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      placements.put(fields[0], new Placement(
          Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
          ZoomBand.valueOf(fields[3]), Double.parseDouble(fields[4])));
    }
    return placements;
  }

  private static LinkedHashMap<String, Actor> readActors(Path source) throws IOException {
    LinkedHashMap<String, Actor> actors = new LinkedHashMap<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "actorId\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      actors.put(fields[0], new Actor(
          fields[0], fields[1], fields[3], Boolean.parseBoolean(fields[4])));
    }
    return actors;
  }

  private static Map<String, Dependency> readDependencies(Path source) throws IOException {
    Map<String, Dependency> dependencies = new HashMap<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "subjectActorId\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      dependencies.put(fields[0], new Dependency(fields[1]));
    }
    return dependencies;
  }

  private static Map<String, Double> readOrientations(Path source) throws IOException {
    Map<String, Double> orientations = new HashMap<>();
    for (String row : Files.readAllLines(source)) {
      if (ignored(row, "labelKey\t")) {
        continue;
      }
      String[] fields = row.split("\\t", -1);
      orientations.put(fields[0], Double.parseDouble(fields[1]));
    }
    return orientations;
  }

  private static boolean ignored(String row, String header) {
    return row.isBlank() || row.startsWith("#") || row.startsWith(header);
  }

  private record PreparedLine(
      String labelKey,
      String variant,
      String fontFace,
      String text,
      double capHeightMapUnits,
      double trackingMapUnits,
      String pathMapCoordinates) {}

  private record Place(
      String placeId,
      String name,
      double latitude,
      double longitude,
      MapRank rank,
      String regionId,
      CityStanding cityStanding) {}

  private record Actor(
      String actorId, String displayName, String capitalRegionId, boolean playable) {}

  private record Placement(
      double latitude,
      double longitude,
      ZoomBand minimumBand,
      double rotationDegrees) {}

  private record Dependency(String suzerainActorId) {}
}
