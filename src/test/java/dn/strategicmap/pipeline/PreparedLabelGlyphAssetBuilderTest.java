package dn.strategicmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedLabelGlyphAssetBuilderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void preservesPerGlyphTangentsFromAnAuthoredCurve() throws Exception {
    Path source = temporaryDirectory.resolve("lines.tsv");
    Files.writeString(source,
        "labelKey\tvariant\tfontFace\ttext\tcapHeightMapUnits\ttrackingMapUnits"
            + "\tpathMapCoordinates\n"
            + "water.atlantic\tDEFAULT\tSERIF\tABC\t2.0\t0.2\t0,0;0.3,0;0.6,0.3\n");
    Path output = temporaryDirectory.resolve("glyphs.tsv");

    PreparedLabelGlyphAssetBuilder.main(new String[] {
        source.toString(), "src/main/resources/fonts/EBGaramond-Regular.ttf",
        "src/main/resources/fonts/EBGaramond-Italic.ttf", output.toString()});

    String[] rows = Files.readString(output).split("\\R");
    assertEquals(4, rows.length);
    assertTrue(rows[1].startsWith("water.atlantic\tDEFAULT\tSERIF\t0\t0\tA\t"));
    assertTrue(rows[3].contains("\t45.0\t"));
    double firstX = Double.parseDouble(rows[1].split("\\t")[6]);
    double secondX = Double.parseDouble(rows[2].split("\\t")[6]);
    double thirdX = Double.parseDouble(rows[3].split("\\t")[6]);
    assertTrue(firstX < secondX && secondX < thirdX);
  }

  @Test
  void keepsVariableWidthLettersAndWordSpacesInReadingOrder() throws Exception {
    Path source = temporaryDirectory.resolve("spaced-lines.tsv");
    Files.writeString(source,
        "labelKey\tvariant\tfontFace\ttext\tcapHeightMapUnits\ttrackingMapUnits"
            + "\tpathMapCoordinates\n"
            + "place.buenos-aires\tCAPITAL\tSERIF\tBUENOS AIRES\t0.6\t0.0\t0,0;2,0;4,0\n");
    Path output = temporaryDirectory.resolve("spaced-glyphs.tsv");

    PreparedLabelGlyphAssetBuilder.main(new String[] {
        source.toString(), "src/main/resources/fonts/EBGaramond-Regular.ttf",
        "src/main/resources/fonts/EBGaramond-Italic.ttf", output.toString()});

    List<Double> baselineXs = Files.readAllLines(output).stream()
        .skip(1)
        .map(row -> Double.parseDouble(row.split("\\t")[6]))
        .toList();
    for (int index = 1; index < baselineXs.size(); index++) {
      assertTrue(baselineXs.get(index) > baselineXs.get(index - 1));
    }
  }
}
