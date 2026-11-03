package dev.skydock.block;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FuelBurnerTest {
    private static int lasts(FuelBurner burner, double load, int cap) {
        int n = 0; while (n < cap && burner.burn(load, () -> 0)) n++; return n;
    }
    @Test void fullLoadBurnsOneTickPerTick() {
        FuelBurner b = new FuelBurner(); b.remaining = 100;
        assertEquals(100, lasts(b, 1, 5000));
    }
    @Test void tenPercentLoadLastsTenTimesLonger() {
        FuelBurner b = new FuelBurner(); b.remaining = 100;
        assertEquals(1000, lasts(b, .1, 5000));
        assertEquals(0, b.remainingTicks());
    }
    @Test void fractionalDustDoesNotShowAsATick() {
        FuelBurner b = new FuelBurner(); b.remaining = 100;
        while (b.burn(.1, () -> 0)) { }
        assertEquals(0, b.remainingTicks());
        FuelBurner c = new FuelBurner(); c.remaining = 1600;
        while (c.burn(.05, () -> 0)) { }
        assertEquals(0, c.remainingTicks());
        FuelBurner d = new FuelBurner(); d.remaining = .25;
        assertEquals(0, d.remainingTicks());
    }
    @Test void noLoadBurnsNothing() {
        FuelBurner b = new FuelBurner(); b.remaining = 5;
        assertFalse(b.burn(0, () -> { fail("fuel must not be consumed"); return 0; }));
        assertEquals(5, b.remaining, 0);
    }
    @Test void emptyEngineReportsUnpowered() {
        FuelBurner b = new FuelBurner();
        assertFalse(b.burn(1, () -> 0));
        assertEquals(0, b.remainingTicks());
    }
    @Test void refuelsFromNextFuelWhenShort() {
        FuelBurner b = new FuelBurner(); int[] calls = {0};
        assertTrue(b.burn(1, () -> { calls[0]++; return 10; }));
        assertEquals(1, calls[0]); assertEquals(9, b.remaining, 1e-9);
    }
    @Test void legacyBurnTicksKeepTheirFuel() {
        CompoundTag legacy = new CompoundTag(); legacy.putInt("BurnTicks", 40);
        assertEquals(40, FuelBurner.load(legacy).remainingTicks());
        CompoundTag both = new CompoundTag(); both.putInt("BurnTicks", 40); both.putDouble("Fuel", 12.5);
        assertEquals(13, FuelBurner.load(both).remainingTicks());
        CompoundTag out = new CompoundTag(); FuelBurner.load(both).save(out);
        assertEquals(12.5, out.getDouble("Fuel"), 0);
    }
}
