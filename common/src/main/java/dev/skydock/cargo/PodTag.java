package dev.skydock.cargo;

import net.minecraft.nbt.CompoundTag;

/** What a cargo pod remembers about its contract: which one, which slot, and whether it was thrown out of the ship. */
public record PodTag(long contract, int index, boolean ejected) {
    public CompoundTag save() { CompoundTag tag = new CompoundTag(); tag.putLong("Contract", contract); tag.putInt("Index", index); tag.putBoolean("Ejected", ejected); return tag; }
    public static PodTag load(CompoundTag tag) { return new PodTag(tag.getLong("Contract"), tag.getInt("Index"), tag.getBoolean("Ejected")); }
}
