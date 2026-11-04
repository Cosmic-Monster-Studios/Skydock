package dev.skydock.fuzz;

import java.util.List;
import java.util.Random;

/**
 * A tiny property runner: each case gets its own {@link Random} from a fixed base seed, and a failure reports the property,
 * the case's seed and whatever the case wrote to its log, so a failing input can be replayed with {@link #replay}.
 */
public final class Fuzz {
    /** Override with {@code -Dskydock.fuzz.seed=...} to explore other inputs; the default keeps runs reproducible. */
    public static final long BASE_SEED = Long.getLong("skydock.fuzz.seed", 20261103L);
    private Fuzz() {}

    public interface Case { void run(Random random, StringBuilder log) throws Exception; }

    public static long seed(String property, int index) { return BASE_SEED * 0x9E3779B97F4A7C15L + property.hashCode() * 1_000_003L + index; }

    public static void check(String property, int cases, Case body) {
        for (int i = 0; i < cases; i++) {
            long seed = seed(property, i); StringBuilder log = new StringBuilder();
            try { body.run(new Random(seed), log); }
            catch (Throwable t) { throw new AssertionError(property + " failed at case " + i + " (seed " + seed + "L)\ninput: " + log, t); }
        }
    }
    /** Runs one case of a property again with the seed a failure printed. */
    public static void replay(long seed, Case body) throws Exception { body.run(new Random(seed), new StringBuilder()); }

    public static <T> T pick(Random r, List<T> from) { return from.get(r.nextInt(from.size())); }
    @SafeVarargs public static <T> T pick(Random r, T... from) { return from[r.nextInt(from.length)]; }

    /** An int biased toward edges: zero, one, small values, the extremes and their neighbours. */
    public static int edgyInt(Random r) {
        return switch (r.nextInt(8)) {
            case 0 -> 0; case 1 -> pick(r, 1, -1, 2, -2);
            case 2 -> pick(r, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1);
            case 3 -> r.nextInt(64) - 32;
            case 4 -> r.nextInt(100_000) - 50_000;
            default -> r.nextInt();
        };
    }
    public static long edgyLong(Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0; case 1 -> pick(r, Long.MAX_VALUE, Long.MIN_VALUE, -1L, 1L);
            case 2 -> r.nextInt(100_000); default -> r.nextLong();
        };
    }
    /** A double biased toward trouble: NaN, the infinities, signed zeros, tiny and huge values. */
    public static double edgyDouble(Random r) {
        return switch (r.nextInt(10)) {
            case 0 -> Double.NaN; case 1 -> pick(r, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            case 2 -> pick(r, 0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE);
            case 3 -> pick(r, Double.MAX_VALUE, -Double.MAX_VALUE, 1e300, -1e300);
            case 4 -> (r.nextDouble() - .5) * 6e7;
            default -> (r.nextDouble() - .5) * 2000;
        };
    }
    /** A finite world-scale double: within the 30,000,000-block world border, sometimes snapped to whole or half blocks. */
    public static double worldDouble(Random r) {
        double v = switch (r.nextInt(4)) { case 0 -> (r.nextDouble() - .5) * 6e7; case 1 -> (r.nextDouble() - .5) * 2e5; default -> (r.nextDouble() - .5) * 2000; };
        return switch (r.nextInt(4)) { case 0 -> Math.floor(v); case 1 -> Math.floor(v) + .5; default -> v; };
    }
}
