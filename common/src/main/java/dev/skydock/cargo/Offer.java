package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;

/** A contract on a terminal's board, not yet accepted. */
public record Offer(long id, GlobalPos destination, int rankLevel, CrateRank crate, String cargoKey, int pods, int deadlineDays, int reputationPerPod) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putLong("Id", id); tag.put("Destination", Contract.savePos(destination)); tag.putInt("Rank", rankLevel);
        tag.putString("Crate", crate.name()); tag.putString("Cargo", cargoKey); tag.putInt("Pods", pods); tag.putInt("Days", deadlineDays); tag.putInt("Reputation", reputationPerPod); return tag;
    }
    /** A pod count outside 1..{@link Contract#MAX_PODS} is corruption and is clamped, as {@link Contract#load} does, so no offer makes a contract that settles at once. */
    public static Offer load(CompoundTag tag) {
        int saved = tag.getInt("Pods"), pods = Math.clamp(saved, 1, Contract.MAX_PODS);
        if (pods != saved) dev.skydock.Skydock.LOGGER.warn("Offer {} saved with {} pods; clamped to {}", tag.getLong("Id"), saved, pods);
        return new Offer(tag.getLong("Id"), Contract.loadPos(tag.get("Destination")), tag.getInt("Rank"), CrateRank.valueOf(tag.getString("Crate")),
                tag.getString("Cargo"), pods, tag.getInt("Days"), tag.getInt("Reputation"));
    }
}
