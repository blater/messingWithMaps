package dn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategicLabelLineAssetBuilderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void retainsReviewedGeometryAndAddsLowerBandAndCapitalVariants() throws Exception {
    Path reviewed = write("reviewed.tsv",
        "labelKey\tfontFace\ttext\tcapHeightMapUnits\ttrackingMapUnits"
            + "\tpathMapCoordinates\n"
            + "water.reviewed\tSERIF\tREVIEWED\t1.0\t0.2\t1,2;3,4\n");
    Path geographic = write("geographic.tsv",
        "labelId\tname\tkind\tlatitude\tlongitude\trank\tsourceDatasetId\n"
            + "water.reviewed\tReviewed\tSEA\t0\t0\tGRAND\ttest\n"
            + "water.local\tLocal Sea\tSEA\t10\t20\tDETAIL\ttest\n");
    Path places = write("places.tsv",
        "placeId\tname\tlatitude\tlongitude\tkinds\trank\tregionId\tsourceDatasetId"
            + "\tcityStanding\n"
            + "place.capital\tCapital\t30\t40\tCITY\tGLOBAL\tregion.one\ttest"
            + "\tMAJOR_CAPITAL\n"
            + "place.minor\tMinor Capital\t32\t42\tCITY\tGLOBAL\tregion.two\ttest"
            + "\tMINOR_CAPITAL\n");
    Path placements = write("placements.tsv",
        "actorId\tlatitude\tlongitude\tminimumBand\trotationDegrees\n"
            + "actor.one\t31\t41\tNATIONAL\t7\n");
    Path actors = write("actors.tsv",
        "actorId\tdisplayName\tkind\tcapitalRegionId\tplayable\n"
            + "actor.one\tThe Test State\tCOUNTRY\tregion.one\ttrue\n");
    Path dependencies = write("dependencies.tsv",
        "subjectActorId\tsuzerainActorId\tkind\n");
    Path orientations = write("orientations.tsv",
        "labelKey\trotationDegrees\nwater.local\t35\n");
    Path output = temporaryDirectory.resolve("lines.tsv");

    StrategicLabelLineAssetBuilder.build(
        reviewed, geographic, places, placements, actors, dependencies, orientations, output);

    List<String> rows = Files.readAllLines(output);
    assertTrue(rows.contains(
        "water.reviewed\tDEFAULT\tSERIF\tREVIEWED\t1.0\t0.2\t1,2;3,4"));
    assertTrue(rows.stream().anyMatch(row -> row.startsWith(
        "water.local\tDEFAULT\tSERIF\tLOCAL SEA\t")));
    assertTrue(rows.stream().anyMatch(row -> row.startsWith(
        "political.actor.one\tDEFAULT\tSERIF\tTEST STATE\t")));
    assertEquals(2, rows.stream().filter(row -> row.startsWith("place.capital\t")).count());
    assertTrue(rows.stream().anyMatch(row -> row.startsWith(
        "place.capital\tCAPITAL\tSERIF\tCAPITAL\t")));
    assertTrue(rows.stream().anyMatch(row -> row.startsWith(
        "place.minor\tCAPITAL\tSERIF\tMinor Capital\t")));
  }

  private Path write(String name, String content) throws Exception {
    Path path = temporaryDirectory.resolve(name);
    Files.writeString(path, content);
    return path;
  }
}
