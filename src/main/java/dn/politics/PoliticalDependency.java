package dn.politics;

/** A subject actor that governs locally under a separate suzerain actor. */
public record PoliticalDependency(
    String subjectActorId,
    String suzerainActorId,
    PoliticalDependencyKind kind) {}
