package dev.skydock.ship;

import dev.skydock.data.CollisionRules;
import dev.skydock.data.DockTier;
import dev.skydock.fuzz.Fuzz;
import dev.skydock.fuzz.NbtFuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Properties that fail on the current code. Each is disabled with the bug it shows; remove the annotation once it is fixed.
 */
class ShipBugsFuzzTest {
    static HolderLookup.Provider registries;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
        ShipPersistenceFuzzTest.boot();
    }

    static ShipSavedData load(CompoundTag tag) throws Throwable {
        Method load = ShipSavedData.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        try { return (ShipSavedData) load.invoke(null, tag, registries); } catch (InvocationTargetException e) { throw e.getCause(); }
    }

    /**
     * Vanilla's DimensionDataStorage logs a throwing loader and hands back a fresh, empty ShipSavedData, which the next ship
     * change saves over skydock_ships.dat: one unreadable ship costs every ship in the world, and their hulls stay stranded in
     * the shipyard. CargoSavedData already loads each entry on its own; ShipSavedData.load does not.
     *
     * Minimal reproducer: a valid save of two ships where the second has {@code Tier: "GALLEON"} (or no {@code Id}, or
     * {@code Dimension: "Bad Dim"}) throws IllegalArgumentException / NullPointerException / ResourceLocationException.
     */
    @Disabled("bug: one unreadable ship makes ShipSavedData.load throw, so the world loads with no ships and saves over them")
    @Test void oneUnreadableShipCostsOnlyThatShip() {
        Fuzz.check("ShipSavedData isolates a damaged ship", 500, (r, log) -> {
            List<Ship> ships = new ArrayList<>(); for (int i = 2 + r.nextInt(3); i > 0; i--) ships.add(ShipPersistenceFuzzTest.ship(r, true));
            ListTag list = new ListTag(); for (Ship s : ships) list.add(s.save(registries, true));
            int victim = r.nextInt(ships.size());
            CompoundTag bad = list.getCompound(victim);
            switch (r.nextInt(4)) {
                case 0 -> { bad.putString("Tier", "GALLEON"); log.append("ship ").append(victim).append(" Tier=GALLEON"); }
                case 1 -> { bad.remove("Id"); log.append("ship ").append(victim).append(" without Id"); }
                case 2 -> { bad.putString("Dimension", "Bad Dim"); log.append("ship ").append(victim).append(" Dimension=Bad Dim"); }
                default -> list.set(victim, NbtFuzz.corrupt(r, bad, .2, log));
            }
            CompoundTag saved = new CompoundTag(); saved.putInt("NextRegion", 7); saved.put("Ships", list);
            ShipSavedData data = assertDoesNotThrow(() -> load(saved), "ShipSavedData.load threw for one damaged ship");
            for (int i = 0; i < ships.size(); i++) if (i != victim) assertTrue(data.ships.containsKey(ships.get(i).id), "undamaged ship " + i + " survives");
            assertEquals(7, data.nextRegion);
        });
    }

    /** The minimal case on its own, for a quick check of a fix. */
    @Disabled("bug: one unreadable ship makes ShipSavedData.load throw, so the world loads with no ships and saves over them")
    @Test void anUnknownTierDropsTheWholeShipSave() throws Throwable {
        Random r = new Random(1);
        Ship good = ShipPersistenceFuzzTest.ship(r, true), other = ShipPersistenceFuzzTest.ship(r, true);
        CompoundTag bad = other.save(registries, true); bad.putString("Tier", "GALLEON");
        ListTag list = new ListTag(); list.add(good.save(registries, true)); list.add(bad);
        CompoundTag saved = new CompoundTag(); saved.put("Ships", list);
        assertTrue(load(saved).ships.containsKey(good.id));
    }

    /**
     * ShipDamage.budget adds 1 to {@code (int) Math.floor(...)}, which saturates at Integer.MAX_VALUE for a fast, heavy hull with
     * a high {@code blocks_per_speed}; the sum wraps to Integer.MIN_VALUE and the budget comes out negative, so damageHull breaks
     * nothing for the hardest impacts. Every input is one the mass rules loader accepts (blocks_per_speed up to 1e6, block masses up
     * to 1e9). Minimal reproducer: budget(rules with blocks_per_speed 1e6, excess speed 20, mass 1e10) == Integer.MIN_VALUE.
     */
    @Disabled("bug: ShipDamage.budget wraps to a negative budget for fast heavy hulls, so the hardest impacts break nothing")
    @Test void impactBudgetsNeverGoNegativeForAcceptedRules() {
        Fuzz.check("ShipDamage.budget overflow", 5000, (r, log) -> {
            CollisionRules rules = new CollisionRules(3, 1.5, Fuzz.pick(r, 1.0, 1e3, 1e6, r.nextDouble() * 1e6), 1 + r.nextInt(4096), 1, true);
            double speed = r.nextDouble() * 40, mass = Fuzz.pick(r, 1e4, 1e8, 1e10, r.nextDouble() * DockTier.DREADNOUGHT.defaultCap * 1e9);
            log.append("blocks_per_speed=").append(rules.blocksPerSpeed()).append(" max_blocks=").append(rules.maxBlocks()).append(" speed=").append(speed).append(" mass=").append(mass);
            int budget = ShipDamage.budget(rules, speed, mass);
            assertTrue(budget >= 0 && budget <= rules.maxBlocks(), "budget " + budget);
        });
    }
}
