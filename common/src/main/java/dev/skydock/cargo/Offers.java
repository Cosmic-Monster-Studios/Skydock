package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.RandomSource;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Offer generation for a terminal's contract board. Pure: no world access. */
public final class Offers {
    public static final int BOARD_SIZE = 3;
    public static final long DAY_TICKS = 24000;
    private static final int BLOCKS_PER_EXTRA_DAY = 2000;
    private Offers() {}

    /** Horizontal distance between two positions in blocks. */
    public static double distance(BlockPos a, BlockPos b) { double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return Math.sqrt(dx * dx + dz * dz); }

    /**
     * Up to three offers: one at the player's rank, one a rank above (a preview; at the top rank it is the top rank itself) and one at a random rank up to the player's.
     * Destinations are other terminals in the same dimension at least the lowest rank's minimum distance away. A rank whose band is empty falls back on
     * {@code wider} (asked at most once): the nearest site beyond the band, else the farthest below it, paid at the rank its distance earns.
     * A slot with no destination at all is left out.
     */
    public static List<Offer> generate(GlobalPos at, int playerRank, List<GlobalPos> destinations, Supplier<List<GlobalPos>> wider, RandomSource random, LongSupplier ids, List<String> cargoNames) {
        if (cargoNames.isEmpty()) return List.of();
        List<GlobalPos> candidates = eligible(at, destinations);
        List<GlobalPos> farther = new ArrayList<>(); boolean[] searched = {false};
        Supplier<List<GlobalPos>> far = () -> {
            if (!searched[0]) { searched[0] = true; farther.addAll(candidates); for (GlobalPos d : eligible(at, wider.get())) if (!farther.contains(d)) farther.add(d); }
            return farther;
        };
        int top = CargoRanks.ranks().getLast().level();
        int rank = Math.max(1, Math.min(playerRank, top));
        int[] levels = { rank, Math.min(rank + 1, top), 1 + random.nextInt(rank) };
        List<Offer> out = new ArrayList<>();
        for (int level : levels) { Offer o = make(at, CargoRanks.byLevel(level), candidates, far, random, ids, cargoNames); if (o != null) out.add(o); }
        return out;
    }

    /** Same-dimension destinations no nearer than the lowest rank's minimum distance (so never the terminal's own outpost). */
    private static List<GlobalPos> eligible(GlobalPos at, List<GlobalPos> destinations) {
        int min = CargoRanks.ranks().getFirst().minDistance();
        List<GlobalPos> out = new ArrayList<>();
        for (GlobalPos d : destinations) if (d.dimension().equals(at.dimension()) && distance(at.pos(), d.pos()) >= min && !out.contains(d)) out.add(d);
        return out;
    }

    /** For a rank whose band is empty: the nearest destination beyond its maximum, else the farthest below its minimum; null with none. */
    static GlobalPos fallback(BlockPos from, Rank rank, List<GlobalPos> destinations) {
        GlobalPos beyond = null, below = null;
        for (GlobalPos d : destinations) {
            double dist = distance(from, d.pos());
            if (dist > rank.maxDistance()) { if (beyond == null || dist < distance(from, beyond.pos())) beyond = d; }
            else if (below == null || dist > distance(from, below.pos())) below = d;
        }
        return beyond != null ? beyond : below;
    }

    private static Offer make(GlobalPos at, Rank rank, List<GlobalPos> candidates, Supplier<List<GlobalPos>> far, RandomSource random, LongSupplier ids, List<String> cargoNames) {
        List<GlobalPos> band = new ArrayList<>();
        for (GlobalPos d : candidates) { double dist = distance(at.pos(), d.pos()); if (dist >= rank.minDistance() && dist <= rank.maxDistance()) band.add(d); }
        // A fallback destination keeps the slot's rank (so it can be accepted) but pays, rewards and allows time for the distance it covers.
        GlobalPos destination; Rank pay = rank;
        if (!band.isEmpty()) destination = band.get(random.nextInt(band.size()));
        else {
            destination = fallback(at.pos(), rank, far.get());
            if (destination == null) return null;
            pay = CargoRanks.forDistance(distance(at.pos(), destination.pos()));
        }
        double distance = distance(at.pos(), destination.pos());
        int pods = 1 + random.nextInt(Math.min(rank.level(), 4));
        int days = pay.deadlineDays() + (int) (distance / BLOCKS_PER_EXTRA_DAY);
        String cargo = cargoNames.get(random.nextInt(cargoNames.size()));
        return new Offer(ids.getAsLong(), destination, rank.level(), pay.crate(), cargo, pods, days, CargoRanks.reputation(pay, distance));
    }

    /**
     * Replaces the board at {@code at} when it is empty, short, or a full day old; returns whether it did.
     * Mutates the profile in place, so the caller must mark the saved data dirty.
     */
    public static boolean refreshIfDue(CargoProfile p, GlobalPos at, long gameTime, Supplier<List<Offer>> make) {
        List<Offer> current = p.offers.get(at);
        Long last = p.offersRefreshed.get(at);
        boolean due = current == null || current.size() < BOARD_SIZE || last == null || gameTime - last >= DAY_TICKS;
        if (!due) return false;
        p.offers.put(at, new ArrayList<>(make.get()));
        p.offersRefreshed.put(at, gameTime);
        return true;
    }
}
