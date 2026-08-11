package dn.strategicmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceLabelReviewSheetBuilderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void drawsEveryObservedPathOverALinkToTheLocalReferenceImage() throws Exception {
    Path observations = temporaryDirectory.resolve("observations.tsv");
    Files.writeString(observations,
        "labelKey\ttypographyRole\tcapHeightPixels\ttrackingPixels\timagePath\n"
            + "water.atlantic\tGREAT_OCEAN\t10\t5\t20,30;50,50;80,70\n");
    Path output = temporaryDirectory.resolve("review.svg");
    Path image = temporaryDirectory.resolve("reference.png");
    ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", image.toFile());

    ReferenceLabelReviewSheetBuilder.main(new String[] {
        image.toString(), observations.toString(), output.toString()});

    String svg = Files.readString(output);
    assertTrue(svg.contains("viewBox=\"0 0 120 80\""));
    assertTrue(svg.contains("width=\"120\" height=\"80\""));
    assertTrue(svg.contains("<polyline points=\"20.0,30.0 50.0,50.0 80.0,70.0\"/>"));
    assertTrue(svg.contains(">water.atlantic</text>"));
  }
}
