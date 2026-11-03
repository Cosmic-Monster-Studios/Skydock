package dev.skydock.cargo;

import dev.skydock.ship.Ship;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PodDriftTest {
    private static final CargoSavedData.Wind CALM = new CargoSavedData.Wind(0, 0, 0);

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Vec3 run(Vec3 velocity, int from, int to, CargoSavedData.Wind wind) { for (int age = from; age < to; age++) velocity = PodDrift.step(velocity, age, wind); return velocity; }

    @Test void tumblesThenOpensTheBalloon() {
        Vec3 v = new Vec3(0, .4, 0); boolean fast = false;
        for (int age = 0; age < 20; age++) { v = PodDrift.step(v, age, CALM); if (v.y < -.05) fast = true; }
        assertTrue(fast, "the tumble should fall faster than a block a second");
        v = run(v, 20, 35, CALM);
        assertEquals(-.05, v.y, .005, "within 15 ticks of opening the fall is capped");
    }

    @Test void landsOnlyOnTheGroundAfterTheTumble() {
        assertTrue(PodEntity.landsOn(true, 0, PodDrift.TUMBLE));
        assertTrue(PodEntity.landsOn(true, -.05, 100));
        assertFalse(PodEntity.landsOn(true, 0, PodDrift.TUMBLE - 1), "no landing mid-tumble");
        assertFalse(PodEntity.landsOn(true, .1, 100), "rising off a ledge is not landing");
    }

    @Test void wallContactIsNotLanding() { assertFalse(PodEntity.landsOn(false, -.05, 100)); }

    @Test void driftsWithTheWind() {
        CargoSavedData.Wind wind = new CargoSavedData.Wind(Math.PI / 3, 1.5, 0);
        Vec3 v = run(new Vec3(.3, .4, -.2), 0, 200, wind);
        assertEquals(Math.cos(Math.PI / 3) * 1.5 / 20, v.x, .01);
        assertEquals(Math.sin(Math.PI / 3) * 1.5 / 20, v.z, .01);
        assertEquals(-.05, v.y, .005);
    }

    @Test void calmWindMeansStraightDown() {
        Vec3 v = run(new Vec3(.3, .4, .2), 0, 200, CALM);
        assertEquals(0, v.horizontalDistance(), .001);
        assertEquals(-.05, v.y, .005);
        Vec3 still = run(new Vec3(0, .4, 0), 0, 200, CALM);
        assertEquals(0, still.x); assertEquals(0, still.z);
    }

    @Test void windChangesEveryFiveMinutes() {
        RandomSource random = RandomSource.create(7);
        CargoSavedData.Wind first = CargoSavedData.nextWind(CALM, 1, random);
        assertNotSame(CALM, first);
        assertSame(first, CargoSavedData.nextWind(first, 1 + 5_999, random));
        CargoSavedData.Wind next = CargoSavedData.nextWind(first, 1 + 6_000, random);
        assertEquals(6_001, next.changedAt());
        for (int i = 0; i < 100; i++) {
            CargoSavedData.Wind w = CargoSavedData.nextWind(next, next.changedAt() + 6_000, random);
            assertTrue(w.speed() >= 0 && w.speed() <= 2, "speed " + w.speed());
            assertTrue(w.angle() >= 0 && w.angle() < Math.PI * 2, "angle " + w.angle());
            next = w;
        }
    }

    /** A same-tick redock copies {@code ship.blocks} (ShipTransfer's snapshot source), so the ejected cell must be gone from it. */
    @Test void ejectedPodIsGoneFromTheHullAndBlueprint() {
        Ship ship = new Ship(); BlockPos deck = new BlockPos(2, 1, 2), pod = deck.above();
        ship.blocks.put(deck, Blocks.OAK_PLANKS.defaultBlockState()); ship.blueprint.put(deck, Blocks.OAK_PLANKS.defaultBlockState());
        ship.blocks.put(pod, Blocks.BARREL.defaultBlockState()); ship.blueprint.put(pod, Blocks.BARREL.defaultBlockState());
        PodLoading.forget(ship, pod);
        assertFalse(ship.blocks.containsKey(pod));
        assertFalse(ship.blueprint.containsKey(pod));
        assertTrue(ship.blocks.keySet().stream().noneMatch(pod::equals));
        assertTrue(ship.blocks.containsKey(deck) && ship.blueprint.containsKey(deck));
    }
}
