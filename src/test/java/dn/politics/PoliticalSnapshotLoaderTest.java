package dn.politics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PoliticalSnapshotLoaderTest {
  @Test
  void loadsLateNineteenthCenturyGermanAndPolishControl() {
    PoliticalSnapshot snapshot = PoliticalSnapshotLoader.loadDefault();

    assertEquals("german-empire", snapshot.controllerId("rus-kaliningrad"));
    assertEquals("german-empire", snapshot.controllerId("fra-alsace-lorraine"));
    assertEquals("german-empire", snapshot.controllerId("pol-west"));
    assertEquals("russian-empire", snapshot.controllerId("pol-east"));
    assertEquals("austria-hungary", snapshot.controllerId("pol-south"));
    PoliticalDependency bulgaria = snapshot.dependencyFor("bulgaria").orElseThrow();
    assertEquals("ottoman-empire", bulgaria.suzerainActorId());
    assertEquals(PoliticalDependencyKind.VASSAL, bulgaria.kind());
    int playableCount = 0;
    for (int index = 0; index < snapshot.actorCount(); index++) {
      if (snapshot.actor(index).playable()) {
        playableCount++;
      }
    }
    assertEquals(10, playableCount);
    assertTrue(snapshot.actor(0).playable());
    assertEquals("color_britain", snapshot.actor(0).mapColourId());
  }

  @Test
  void loadsWithoutMapGeometryOrLibGdx() {
    String actors = "actorId\tdisplayName\tkind\tcapitalRegionId\tplayable\tmapColourId\n"
        + "actor.test\tTest Empire\tEMPIRE\tregion.capital\ttrue\tcolor_test\n";
    String control = "actorId\tregionIds\nactor.test\tregion.capital,region.colony\n";

    PoliticalSnapshot snapshot = PoliticalSnapshotLoader.load(
        input(actors), input(control), "test date");

    assertEquals("test date", snapshot.representedDate());
    assertEquals("region.capital", snapshot.actor(0).capitalRegionId());
    assertTrue(snapshot.actor(0).playable());
    assertEquals("color_test", snapshot.actor(0).mapColourId());
    assertEquals("actor.test", snapshot.controllerId("region.colony"));
    assertTrue(snapshot.dependencyFor("actor.test").isEmpty());
  }

  private static ByteArrayInputStream input(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
