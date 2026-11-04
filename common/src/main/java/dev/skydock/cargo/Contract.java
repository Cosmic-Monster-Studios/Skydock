package dev.skydock.cargo;

import com.mojang.serialization.Codec;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.*;
import java.util.*;

/**
 * An accepted contract: the holder's running ledger of which pods arrived, were lost, or are still out. {@code rankLevel} is the rank it was
 * accepted at; {@code reputationPerPod} and {@code crate} are what its offer showed, which a fallback destination may set from a higher rank.
 */
public final class Contract {
    public final long id; public final GlobalPos origin, destination; public final int rankLevel; public final CrateRank crate;
    public final String cargoKey; public final int pods, reputationPerPod; public final long deadline;
    public final Set<Integer> delivered = new TreeSet<>(), lost = new TreeSet<>();
    public boolean anyReduced;

    public Contract(long id, GlobalPos origin, GlobalPos destination, int rankLevel, CrateRank crate, String cargoKey, int pods, long deadline, int reputationPerPod) {
        this.id = id; this.origin = origin; this.destination = destination; this.rankLevel = rankLevel; this.crate = crate;
        this.cargoKey = cargoKey; this.pods = pods; this.deadline = deadline; this.reputationPerPod = reputationPerPod;
    }
    /** A contract paying its rank's full rate for the distance. */
    public Contract(long id, GlobalPos origin, GlobalPos destination, int rankLevel, CrateRank crate, String cargoKey, int pods, long deadline) {
        this(id, origin, destination, rankLevel, crate, cargoKey, pods, deadline, rankRate(rankLevel, origin, destination));
    }
    private static int rankRate(int rankLevel, GlobalPos origin, GlobalPos destination) { return CargoRanks.reputation(CargoRanks.byLevel(rankLevel), Offers.distance(origin.pos(), destination.pos())); }
    /** The most pods a loaded contract may hold; offers make at most four, so a larger saved count is corruption. */
    public static final int MAX_PODS = 64;
    /**
     * True once every pod index is either delivered or lost. Constant time: both sets only ever hold distinct in-range indices
     * ({@link Delivery} checks before adding, {@link #load} drops the rest), so their sizes together reach {@code pods} exactly then.
     */
    public boolean settled() { return delivered.size() + lost.size() >= pods; }

    static CompoundTag savePos(GlobalPos pos) { return (CompoundTag) GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow(); }
    static GlobalPos loadPos(Tag tag) { return GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow(); }
    static int[] ints(Set<Integer> set) { return set.stream().mapToInt(Integer::intValue).toArray(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putLong("Id", id); tag.put("Origin", savePos(origin)); tag.put("Destination", savePos(destination));
        tag.putInt("Rank", rankLevel); tag.putString("Crate", crate.name()); tag.putString("Cargo", cargoKey); tag.putInt("Pods", pods); tag.putLong("Deadline", deadline);
        tag.putInt("Reputation", reputationPerPod);
        tag.putIntArray("Delivered", ints(delivered)); tag.putIntArray("Lost", ints(lost)); tag.putBoolean("AnyReduced", anyReduced);
        return tag;
    }
    public static Contract load(CompoundTag tag) {
        GlobalPos origin = loadPos(tag.get("Origin")), destination = loadPos(tag.get("Destination")); int rank = tag.getInt("Rank");
        // Saves from before the rate was stored pay the rank's rate, as they did then.
        int rate = tag.contains("Reputation", Tag.TAG_INT) ? tag.getInt("Reputation") : rankRate(rank, origin, destination);
        int saved = tag.getInt("Pods"), pods = Math.clamp(saved, 1, MAX_PODS);
        if (pods != saved) dev.skydock.Skydock.LOGGER.warn("Contract {} saved with {} pods; clamped to {}", tag.getLong("Id"), saved, pods);
        Contract c = new Contract(tag.getLong("Id"), origin, destination, rank, CrateRank.valueOf(tag.getString("Crate")), tag.getString("Cargo"), pods, tag.getLong("Deadline"), rate);
        // Out-of-range or doubly-recorded indices would break settled()'s count, so only in-range ones load, and delivered wins over lost.
        for (int i : tag.getIntArray("Delivered")) if (i >= 0 && i < pods) c.delivered.add(i);
        for (int i : tag.getIntArray("Lost")) if (i >= 0 && i < pods && !c.delivered.contains(i)) c.lost.add(i);
        c.anyReduced = tag.getBoolean("AnyReduced");
        return c;
    }
}
