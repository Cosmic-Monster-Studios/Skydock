package dev.skydock.block;

import net.minecraft.nbt.CompoundTag;
import java.util.function.IntSupplier;

/** Fuel tank measured in full-load ticks; a tick at load L burns L of it. */
public final class FuelBurner {
    private static final double EPS = 1e-9;
    public double remaining;
    /** Burns {@code load} ticks of fuel, pulling a new item from {@code nextFuel} (scaled burn ticks, 0 when none) if short. */
    public boolean burn(double load, IntSupplier nextFuel) {
        if (load <= 0) return false;
        if (remaining < load - EPS) remaining += Math.max(0, nextFuel.getAsInt());
        if (remaining < load - EPS) return false;
        remaining -= load;
        // Repeated fractional subtraction leaves float dust; snap it so an empty tank reads empty.
        if (remaining < EPS) remaining = 0;
        return true;
    }
    public int remainingTicks() { return (int) Math.round(remaining); }
    /** Reads {@code Fuel}, falling back to the integer {@code BurnTicks} written by 0.1 saves. */
    public static FuelBurner load(CompoundTag tag) {
        FuelBurner b = new FuelBurner();
        b.remaining = Math.max(0, tag.contains("Fuel") ? tag.getDouble("Fuel") : tag.getInt("BurnTicks"));
        return b;
    }
    public void save(CompoundTag tag) { tag.putDouble("Fuel", remaining); }
}
