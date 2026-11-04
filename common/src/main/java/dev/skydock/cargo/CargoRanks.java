package dev.skydock.cargo;

import java.util.List;

/** The cargo rank ladder and reward maths; data-driven via {@link CargoRankLoader}, defaults otherwise. */
public final class CargoRanks {
    private static final List<Rank> DEFAULTS = List.of(
            new Rank(1, "rank.skydock.deckhand", 0, 300, 800, 2, CrateRank.TIN, 10),
            new Rank(2, "rank.skydock.mate", 100, 600, 1500, 2, CrateRank.COPPER, 25),
            new Rank(3, "rank.skydock.captain", 400, 1200, 3000, 3, CrateRank.BRASS, 60),
            new Rank(4, "rank.skydock.commodore", 1200, 2500, 6000, 4, CrateRank.GOLD, 150),
            new Rank(5, "rank.skydock.admiral", 3000, 5000, Integer.MAX_VALUE, 5, CrateRank.AETHER, 400));
    private static List<Rank> loaded = DEFAULTS;
    private CargoRanks() {}

    public static List<Rank> defaults() { return DEFAULTS; }
    public static List<Rank> ranks() { return loaded; }
    public static void setData(List<Rank> ranks) { loaded = ranks.isEmpty() ? DEFAULTS : List.copyOf(ranks); }

    /** The rank with this level; unknown levels (ranks reloaded from data) fall back to the lowest rank. */
    public static Rank byLevel(int level) { for (Rank r : loaded) if (r.level() == level) return r; return loaded.getFirst(); }
    /** The highest rank whose threshold the reputation reaches. */
    public static Rank forReputation(int reputation) {
        Rank best = loaded.getFirst();
        for (Rank rank : loaded) if (rank.threshold() <= reputation) best = rank;
        return best;
    }
    /** The highest rank whose minimum distance the trip reaches (the lowest rank for short trips). */
    public static Rank forDistance(double distance) {
        Rank best = loaded.getFirst();
        for (Rank rank : loaded) if (rank.minDistance() <= distance) best = rank;
        return best;
    }
    /** Full reputation reward per pod; never below the base. */
    public static int reputation(Rank rank, double distance) {
        return (int) Math.round(rank.basePerPod() * Math.max(1, distance / rank.minDistance()));
    }
}
