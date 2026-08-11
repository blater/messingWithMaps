package dn.strategicmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceLabelLayoutBuilderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void convertsAnObservedCurvedPathAndTypeMeasurementsIntoMapSpace() throws Exception {
    Path calibration = temporaryDirectory.resolve("calibration.tsv");
    Files.writeString(calibration, "axis\timagePixel\tmapCoordinate\n"
        + "X\t0\t0\nX\t100\t20\nY\t0\t10\nY\t100\t-10\n");
    Path observations = temporaryDirectory.resolve("observations.tsv");
    Files.writeString(observations,
        "labelKey\ttypographyRole\tcapHeightPixels\ttrackingPixels\timagePath\n"
            + "water.atlantic\tGREAT_OCEAN\t10\t5\t20,30;50,50;80,70\n");
    Path output = temporaryDirectory.resolve("prepared.tsv");

    ReferenceLabelLayoutBuilder.main(new String[] {
        calibration.toString(), observations.toString(), output.toString()});

    assertEquals(
        "labelKey\ttypographyRole\tcapHeightMapUnits\ttrackingMapUnits\tpathMapCoordinates\n"
            + "water.atlantic\tGREAT_OCEAN\t2.0\t1.0\t4.0,4.0;10.0,0.0;16.0,-4.0\n",
        Files.readString(output));
  }

  @Test
  void preservesTheReferenceMapsGreenwichAndEquatorControlPoint() throws Exception {
    Path observations = temporaryDirectory.resolve("observations.tsv");
    Files.writeString(observations,
        "labelKey\ttypographyRole\tcapHeightPixels\ttrackingPixels\timagePath\n"
            + "control\tTEST\t1\t1\t314,3200;4453,3200\n");
    Path output = temporaryDirectory.resolve("prepared.tsv");

    ReferenceLabelLayoutBuilder.main(new String[] {
        "src/mapTools/resources/labels/philip-1905-calibration.tsv", observations.toString(),
        output.toString()});

    assertEquals(
        "control\tTEST\t0.043478260869565216\t0.0436046511627907\t0.0,0.0;180.0,0.0",
        Files.readAllLines(output).get(1));
  }
}
