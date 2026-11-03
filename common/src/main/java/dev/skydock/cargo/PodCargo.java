package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;

/** Everything a cargo pod carries: its contract tag, the cargo it holds and where it is going. Stored as the pod item's custom data and as block entity NBT. */
public record PodCargo(PodTag tag, String cargoKey, GlobalPos destination) {
    public CompoundTag save() {
        CompoundTag out = new CompoundTag(); out.put("Pod", tag.save()); out.putString("Cargo", cargoKey); out.put("Destination", Contract.savePos(destination)); return out;
    }
    /** Empty when the data is missing or malformed. */
    public static java.util.Optional<PodCargo> load(CompoundTag in) {
        if (!in.contains("Pod", 10) || !in.contains("Destination")) return java.util.Optional.empty();
        try { return java.util.Optional.of(new PodCargo(PodTag.load(in.getCompound("Pod")), in.getString("Cargo"), Contract.loadPos(in.get("Destination")))); }
        catch (RuntimeException e) { return java.util.Optional.empty(); }
    }
}
