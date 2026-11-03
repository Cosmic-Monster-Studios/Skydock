package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;

/** A contract on a terminal's board, not yet accepted. */
public record Offer(long id, GlobalPos destination, int rankLevel, CrateRank crate, String cargoKey, int pods, int deadlineDays, int reputationPerPod) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putLong("Id", id); tag.put("Destination", Contract.savePos(destination)); tag.putInt("Rank", rankLevel);
        tag.putString("Crate", crate.name()); tag.putString("Cargo", cargoKey); tag.putInt("Pods", pods); tag.putInt("Days", deadlineDays); tag.putInt("Reputation", reputationPerPod); return tag;
    }
    public static Offer load(CompoundTag tag) {
        return new Offer(tag.getLong("Id"), Contract.loadPos(tag.get("Destination")), tag.getInt("Rank"), CrateRank.valueOf(tag.getString("Crate")),
                tag.getString("Cargo"), tag.getInt("Pods"), tag.getInt("Days"), tag.getInt("Reputation"));
    }
}
