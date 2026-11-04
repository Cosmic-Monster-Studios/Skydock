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
    public final Set<UUID> strangersPaid = new LinkedHashSet<>();
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
    /** True once every pod index is either delivered or lost. */
    public boolean settled() { for (int i = 0; i < pods; i++) if (!delivered.contains(i) && !lost.contains(i)) return false; return true; }

    static CompoundTag savePos(GlobalPos pos) { return (CompoundTag) GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow(); }
    static GlobalPos loadPos(Tag tag) { return GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow(); }
    static int[] ints(Set<Integer> set) { return set.stream().mapToInt(Integer::intValue).toArray(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putLong("Id", id); tag.put("Origin", savePos(origin)); tag.put("Destination", savePos(destination));
        tag.putInt("Rank", rankLevel); tag.putString("Crate", crate.name()); tag.putString("Cargo", cargoKey); tag.putInt("Pods", pods); tag.putLong("Deadline", deadline);
        tag.putInt("Reputation", reputationPerPod);
        tag.putIntArray("Delivered", ints(delivered)); tag.putIntArray("Lost", ints(lost)); tag.putBoolean("AnyReduced", anyReduced);
        ListTag paid = new ListTag(); strangersPaid.forEach(u -> paid.add(NbtUtils.createUUID(u))); tag.put("Strangers", paid); return tag;
    }
    public static Contract load(CompoundTag tag) {
        GlobalPos origin = loadPos(tag.get("Origin")), destination = loadPos(tag.get("Destination")); int rank = tag.getInt("Rank");
        // Saves from before the rate was stored pay the rank's rate, as they did then.
        int rate = tag.contains("Reputation", Tag.TAG_INT) ? tag.getInt("Reputation") : rankRate(rank, origin, destination);
        Contract c = new Contract(tag.getLong("Id"), origin, destination, rank, CrateRank.valueOf(tag.getString("Crate")), tag.getString("Cargo"), tag.getInt("Pods"), tag.getLong("Deadline"), rate);
        for (int i : tag.getIntArray("Delivered")) c.delivered.add(i);
        for (int i : tag.getIntArray("Lost")) c.lost.add(i);
        c.anyReduced = tag.getBoolean("AnyReduced");
        for (Tag t : tag.getList("Strangers", Tag.TAG_INT_ARRAY)) c.strangersPaid.add(NbtUtils.loadUUID(t));
        return c;
    }
}
