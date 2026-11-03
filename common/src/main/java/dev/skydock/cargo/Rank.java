package dev.skydock.cargo;

/** One rung of the cargo rank ladder. A {@code maxDistance} of {@link Integer#MAX_VALUE} is open-ended. */
public record Rank(int level, String key, int threshold, int minDistance, int maxDistance, int deadlineDays, CrateRank crate, int basePerPod) {}
