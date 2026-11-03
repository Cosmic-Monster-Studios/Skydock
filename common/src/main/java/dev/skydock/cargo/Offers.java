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
     * Three offers: one at the player's rank, one a rank above (a preview, capped at the top rank) and one at a random rank up to the player's.
     * Destinations are other terminals in the same dimension; with none, the list is empty.
     */
    public static List<Offer> generate(GlobalPos at, int playerRank, List<GlobalPos> destinations, RandomSource random, LongSupplier ids, List<String> cargoNames) {
        List<GlobalPos> candidates = new ArrayList<>();
        for (GlobalPos d : destinations) if (d.dimension().equals(at.dimension()) && !d.pos().equals(at.pos())) candidates.add(d);
        if (candidates.isEmpty() || cargoNames.isEmpty()) return List.of();
        int top = CargoRanks.ranks().getLast().level();
        int rank = Math.max(1, Math.min(playerRank, top));
        int[] levels = { rank, Math.min(rank + 1, top), 1 + random.nextInt(rank) };
        List<Offer> out = new ArrayList<>();
        for (int level : levels) out.add(make(at, byLevel(level), candidates, random, ids, cargoNames));
        return out;
    }

    private static Rank byLevel(int level) {
        for (Rank r : CargoRanks.ranks()) if (r.level() == level) return r;
        return CargoRanks.ranks().getFirst();
    }

    private static Offer make(GlobalPos at, Rank rank, List<GlobalPos> candidates, RandomSource random, LongSupplier ids, List<String> cargoNames) {
        List<GlobalPos> band = new ArrayList<>();
        for (GlobalPos d : candidates) { double dist = distance(at.pos(), d.pos()); if (dist >= rank.minDistance() && dist <= rank.maxDistance()) band.add(d); }
        GlobalPos destination; Rank effective = rank;
        if (!band.isEmpty()) destination = band.get(random.nextInt(band.size()));
        else {
            destination = candidates.getFirst();
            for (GlobalPos d : candidates) if (distance(at.pos(), d.pos()) > distance(at.pos(), destination.pos())) destination = d;
            effective = CargoRanks.forDistance(distance(at.pos(), destination.pos()));
        }
        double distance = distance(at.pos(), destination.pos());
        int pods = 1 + random.nextInt(Math.min(effective.level(), 4));
        int days = effective.deadlineDays() + (int) (distance / BLOCKS_PER_EXTRA_DAY);
        String cargo = cargoNames.get(random.nextInt(cargoNames.size()));
        return new Offer(ids.getAsLong(), destination, effective.level(), effective.crate(), cargo, pods, days, CargoRanks.reputation(effective, distance));
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
