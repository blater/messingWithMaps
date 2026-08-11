package dn.politics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads the initial flat political presentation snapshot without map dependencies. */
public final class PoliticalSnapshotLoader {
  private static final String ACTORS_RESOURCE = "/politics/actors-1895.tsv";
  private static final String CONTROL_RESOURCE = "/politics/control-1895.tsv";
  private static final String DEPENDENCIES_RESOURCE = "/politics/dependencies-1895.tsv";

  private PoliticalSnapshotLoader() {}

  public static PoliticalSnapshot loadDefault() {
    InputStream actors = PoliticalSnapshotLoader.class.getResourceAsStream(ACTORS_RESOURCE);
    InputStream control = PoliticalSnapshotLoader.class.getResourceAsStream(CONTROL_RESOURCE);
    InputStream dependencies =
        PoliticalSnapshotLoader.class.getResourceAsStream(DEPENDENCIES_RESOURCE);
    if (actors == null || control == null || dependencies == null) {
      throw new IllegalStateException("Political snapshot assets not found");
    }
    return load(actors, control, dependencies, "circa 1895");
  }

  public static PoliticalSnapshot load(
      InputStream actorsInput, InputStream controlInput, String representedDate) {
    try {
      return new PoliticalSnapshot(
          representedDate,
          readActors(actorsInput),
          readControl(controlInput));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load political snapshot for " + representedDate, exception);
    }
  }

  public static PoliticalSnapshot load(
      InputStream actorsInput,
      InputStream controlInput,
      InputStream dependenciesInput,
      String representedDate) {
    try {
      return new PoliticalSnapshot(
          representedDate,
          readActors(actorsInput),
          readControl(controlInput),
          readDependencies(dependenciesInput));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Unable to load political snapshot for " + representedDate, exception);
    }
  }

  private static List<PoliticalActor> readActors(InputStream input) throws IOException {
    try (BufferedReader reader = reader(input)) {
      List<PoliticalActor> actors = new ArrayList<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        actors.add(new PoliticalActor(
            fields[0],
            fields[1],
            PoliticalActorKind.valueOf(fields[2]),
            fields[3],
            Boolean.parseBoolean(fields[4]),
            fields[5]));
      }
      return actors;
    }
  }

  private static Map<String, String> readControl(InputStream input) throws IOException {
    try (BufferedReader reader = reader(input)) {
      Map<String, String> controllers = new HashMap<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        for (String regionId : fields[1].split(",")) {
          controllers.put(regionId, fields[0]);
        }
      }
      return controllers;
    }
  }

  private static Map<String, PoliticalDependency> readDependencies(InputStream input)
      throws IOException {
    try (BufferedReader reader = reader(input)) {
      Map<String, PoliticalDependency> dependencies = new HashMap<>();
      String line = reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] fields = line.split("\\t", -1);
        PoliticalDependency dependency = new PoliticalDependency(
            fields[0], fields[1], PoliticalDependencyKind.valueOf(fields[2]));
        dependencies.put(dependency.subjectActorId(), dependency);
      }
      return dependencies;
    }
  }

  private static BufferedReader reader(InputStream input) {
    return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
  }
}
