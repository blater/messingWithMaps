package dn.strategicmap.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceDisplayLabelExtractorTest {
  @TempDir Path temporaryDirectory;

  @Test
  void extractsARegularBaselineDespiteGridRouteAndOneMissingGlyph() throws Exception {
    BufferedImage reference = new BufferedImage(760, 280, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = reference.createGraphics();
    graphics.setColor(new Color(244, 239, 218));
    graphics.fillRect(0, 0, reference.getWidth(), reference.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(2));
    graphics.drawLine(330, 0, 330, reference.getHeight());
    graphics.setFont(new Font(Font.SERIF, Font.PLAIN, 54));
    char[] visibleGlyphs = {'O', 'C', 'A', 'N'};
    int[] sourceIndexes = {0, 1, 3, 4};
    for (int index = 0; index < sourceIndexes.length; index++) {
      graphics.drawString(Character.toString(visibleGlyphs[index]),
          90 + sourceIndexes[index] * 118, 150);
    }
    graphics.setColor(new Color(178, 62, 50));
    graphics.setStroke(new BasicStroke(3));
    graphics.drawLine(20, 190, 720, 95);
    graphics.dispose();
    Path image = temporaryDirectory.resolve("reference.png");
    ImageIO.write(reference, "png", image.toFile());

    Path calibration = temporaryDirectory.resolve("calibration.tsv");
    Files.writeString(calibration,
        "axis\timagePixel\tmapCoordinate\nX\t330\t0\nY\t240\t0\n");
    Path targets = temporaryDirectory.resolve("targets.tsv");
    Files.writeString(targets,
        "labelKey\tlineText\tlineOrder\tcropX\tcropY\tcropWidth\tcropHeight"
            + "\tminimumCapHeight\tmaximumCapHeight\tmaximumMissingGlyphs\n"
            + "water.test\tOCEAN\t0\t0\t0\t760\t280\t30\t75\t2\n");
    Path output = temporaryDirectory.resolve("output");

    assertTimeout(Duration.ofSeconds(2),
        () -> ReferenceDisplayLabelExtractor.extract(image, calibration, targets, output));

    String[] fields = Files.readAllLines(output.resolve("display-label-candidates.tsv"))
        .get(1).split("\\t", -1);
    assertEquals("water.test", fields[0]);
    assertEquals("OCEAN", fields[1]);
    assertTrue(Integer.parseInt(fields[4]) >= 3);
    assertTrue(Integer.parseInt(fields[6]) <= 2);
    assertTrue(Double.parseDouble(fields[7]) >= 30.0);
    assertTrue(Files.isRegularFile(output.resolve("water-test-ocean.png")));
  }
}
