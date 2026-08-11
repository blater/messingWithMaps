package dn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GrandTheatreReferenceTargetBuilderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void derivesTheCompleteCurrentGrandAndTheatreDenominator() throws Exception {
    Path reference = temporaryDirectory.resolve("reference.png");
    ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", reference.toFile());
    Path catalogue = temporaryDirectory.resolve("catalogue.tsv");
    Path targets = temporaryDirectory.resolve("targets.tsv");

    GrandTheatreReferenceTargetBuilder.build(
        reference,
        Path.of("src/mapTools/resources/labels/philip-1905-calibration.tsv"),
        Path.of("src/main/resources/maps/geographic-labels.tsv"),
        Path.of("src/main/resources/presentation/political-labels-1895.tsv"),
        Path.of("src/main/resources/politics/actors-1895.tsv"),
        Path.of("src/mapTools/resources/labels/display-label-extraction-targets.tsv"),
        catalogue,
        targets);

    List<String> catalogueLines = Files.readAllLines(catalogue);
    List<String> catalogueRows = catalogueLines.subList(1, catalogueLines.size());
    assertEquals(41, catalogueRows.size());
    assertEquals(19, catalogueRows.stream()
        .filter(row -> row.contains("\tWORLD\t") || row.contains("\tGRAND\t")).count());
    assertEquals(22, catalogueRows.stream().filter(row -> row.contains("\tTHEATRE\t")).count());
    assertEquals(56, Files.readAllLines(targets).size() - 1);
    String targetRows = Files.readString(targets);
    assertTrue(targetRows.contains("political.qing-empire\tCHINESE\t0\t"));
    assertTrue(targetRows.contains("political.qing-empire\tEMPIRE\t1\t"));
  }
}
