package dev.skydock.cargo;

/** Crate tiers, lowest to highest. */
public enum CrateRank {
    TIN, COPPER, BRASS, GOLD, AETHER;
    public CrateRank lower() { return this == TIN ? TIN : values()[ordinal() - 1]; }
}
