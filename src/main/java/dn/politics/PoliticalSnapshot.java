package dn.politics;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only dated political facts independent of map geometry and libGDX. */
public final class PoliticalSnapshot {
  private final String representedDate;
  private final List<PoliticalActor> actors;
  private final Map<String, String> controllerByRegionId;
  private final Map<String, PoliticalDependency> dependencyBySubjectActorId;

  public PoliticalSnapshot(
      String representedDate,
      List<PoliticalActor> actors,
      Map<String, String> controllerByRegionId) {
    this(representedDate, actors, controllerByRegionId, Map.of());
  }

  public PoliticalSnapshot(
      String representedDate,
      List<PoliticalActor> actors,
      Map<String, String> controllerByRegionId,
      Map<String, PoliticalDependency> dependencyBySubjectActorId) {
    this.representedDate = representedDate;
    this.actors = actors;
    this.controllerByRegionId = controllerByRegionId;
    this.dependencyBySubjectActorId = dependencyBySubjectActorId;
  }

  public String representedDate() {
    return representedDate;
  }

  public int actorCount() {
    return actors.size();
  }

  public PoliticalActor actor(int index) {
    return actors.get(index);
  }

  public String controllerId(String regionId) {
    return controllerByRegionId.get(regionId);
  }

  public Optional<PoliticalDependency> dependencyFor(String subjectActorId) {
    return Optional.ofNullable(dependencyBySubjectActorId.get(subjectActorId));
  }
}
