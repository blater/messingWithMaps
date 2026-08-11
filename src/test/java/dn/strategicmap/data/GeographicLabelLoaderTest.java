package dn.strategicmap.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dn.strategicmap.feature.GeographicLabelKind;
import dn.strategicmap.feature.MapRank;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GeographicLabelLoaderTest {
  @Test
  void defaultCatalogueContainsTheCuratedMaritimeSet() {
    var labels = GeographicLabelLoader.loadDefault();

    assertTrue(labels.size() >= 70);
    assertTrue(labels.stream().anyMatch(label -> label.labelId().equals("water.baltic")));
    assertTrue(labels.stream().anyMatch(label -> label.labelId().equals("islands.fiji")));
    assertTrue(labels.stream().anyMatch(label -> label.labelId().equals("continent.africa")));
    assertEquals(
        MapRank.GRAND,
        labels.stream()
            .filter(label -> label.labelId().equals("water.north-atlantic"))
            .findFirst()
            .orElseThrow()
            .rank());
  }

  @Test
  void loadsLatitudeBeforeLongitude() {
    String source = "labelId\tname\tkind\tlatitude\tlongitude\trank\tsourceDatasetId\n"
        + "water.test\tTest Sea\tSEA\t12.5\t-34.25\tMAJOR\ttest-source\n";

    var labels = GeographicLabelLoader.load(
        new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "fixture");

    assertEquals(1, labels.size());
    assertEquals(GeographicLabelKind.SEA, labels.getFirst().kind());
    assertEquals(12.5, labels.getFirst().latitudeDegrees());
    assertEquals(-34.25, labels.getFirst().longitudeDegrees());
  }

  @Test
  void reportsTheResourceWhoseParseFailed() {
    String source = "header\nwater.test\tTest Sea\tSEA\tnot-a-number\t0\tMAJOR\ttest\n";

    var exception = assertThrows(
        IllegalStateException.class,
        () -> GeographicLabelLoader.load(
            new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "broken-fixture"));

    assertEquals("Unable to load geographic labels from broken-fixture", exception.getMessage());
  }
}
