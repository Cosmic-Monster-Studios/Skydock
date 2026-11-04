package dev.skydock.cargo;

import net.minecraft.nbt.CompoundTag;

/** One rung of the cargo rank ladder. A {@code maxDistance} of {@link Integer#MAX_VALUE} is open-ended. */
public record Rank(int level, String key, int threshold, int minDistance, int maxDistance, int deadlineDays, CrateRank crate, int basePerPod) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Level", level); tag.putString("Key", key); tag.putInt("Threshold", threshold); tag.putInt("Min", minDistance);
        tag.putInt("Max", maxDistance); tag.putInt("Days", deadlineDays); tag.putString("Crate", crate.name()); tag.putInt("Base", basePerPod); return tag;
    }
    public static Rank load(CompoundTag tag) {
        return new Rank(tag.getInt("Level"), tag.getString("Key"), tag.getInt("Threshold"), tag.getInt("Min"), tag.getInt("Max"), tag.getInt("Days"), CrateRank.valueOf(tag.getString("Crate")), tag.getInt("Base"));
    }
}
