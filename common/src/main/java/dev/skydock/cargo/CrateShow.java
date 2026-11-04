package dev.skydock.cargo;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import java.util.*;

/**
 * The timeline of a crate's opening show. Each rank plays its phases back to back, then bursts on the tick its duration ends
 * and is {@link Phase#DONE} after that. Ages are ticks since the crate was opened.
 */
public final class CrateShow {
    public enum Phase { LID, RISE, SPIN, BEAM, BURST, DONE }
    private record Step(Phase phase, int end) {}
    private static final Map<CrateRank, List<Step>> STEPS = new EnumMap<>(Map.of(
            CrateRank.TIN, List.of(new Step(Phase.LID, 10)),
            CrateRank.COPPER, List.of(new Step(Phase.LID, 20)),
            CrateRank.BRASS, List.of(new Step(Phase.LID, 10), new Step(Phase.RISE, 40)),
            CrateRank.GOLD, List.of(new Step(Phase.RISE, 20), new Step(Phase.SPIN, 60)),
            CrateRank.AETHER, List.of(new Step(Phase.RISE, 60), new Step(Phase.BEAM, 100))));
    private CrateShow() {}

    public static int duration(CrateRank rank) { return STEPS.get(rank).getLast().end(); }
    public static Phase phase(CrateRank rank, int age) {
        for (Step step : STEPS.get(rank)) if (age < step.end()) return step.phase();
        return age == duration(rank) ? Phase.BURST : Phase.DONE;
    }
    /** The tick the phase starts, or -1 when this rank's show has no such phase. */
    public static int start(CrateRank rank, Phase phase) {
        int from = 0;
        for (Step step : STEPS.get(rank)) { if (step.phase() == phase) return from; from = step.end(); }
        return phase == Phase.BURST ? from : phase == Phase.DONE ? from + 1 : -1;
    }
    /** How far through its current phase the show is, 0 to 1, at a fractional age. */
    public static float progress(CrateRank rank, float age) {
        Phase phase = phase(rank, (int) Math.floor(age));
        int from = start(rank, phase), to = STEPS.get(rank).stream().filter(s -> s.phase() == phase).mapToInt(Step::end).findFirst().orElse(from + 1);
        return Math.clamp((age - from) / (to - from), 0f, 1f);
    }
    /** How high the crate floats, in blocks: Gold and Aether crates ease up during their rise and hang there until the burst. */
    public static float lift(CrateRank rank, float age) {
        float peak = rank == CrateRank.GOLD ? 1 : rank == CrateRank.AETHER ? 1.5f : 0;
        return phase(rank, (int) Math.floor(age)) == Phase.RISE ? peak * ease(progress(rank, age)) : peak;
    }
    /** Smoothstep: slow at both ends. */
    public static float ease(float t) { return t * t * (3 - 2 * t); }

    /**
     * The shop-only pattern a crate unlocks, if any: Gold has a 20% chance, Aether always tries, lower ranks never do. A try picks
     * uniformly among the shop patterns the player does not own; empty when they own them all, which the caller refunds.
     */
    /** Whether a crate of this rank tries for a pattern: Gold one time in five, Aether always. */
    public static boolean triesPrize(CrateRank rank, RandomSource random) { return switch (rank) { case GOLD -> random.nextFloat() < .2f; case AETHER -> true; default -> false; }; }
    public static Optional<ResourceLocation> patternPrize(CrateRank rank, Set<ResourceLocation> owned, List<ResourceLocation> shopPatterns, RandomSource random) {
        if (!triesPrize(rank, random)) return Optional.empty();
        List<ResourceLocation> left = shopPatterns.stream().filter(p -> !owned.contains(p)).toList();
        return left.isEmpty() ? Optional.empty() : Optional.of(left.get(random.nextInt(left.size())));
    }
}
