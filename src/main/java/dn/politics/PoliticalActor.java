package dn.politics;

/** Political identity with the capital and starting-presentation facts consumed by composition. */
public record PoliticalActor(
    String actorId,
    String displayName,
    PoliticalActorKind kind,
    String capitalRegionId,
    boolean playable,
    String mapColourId) {}
