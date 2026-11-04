package dev.skydock.ship;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipSleepTest {
    @Test void parkedMooredShipRests() { assertTrue(ShipSleep.restful(false, false, 0, true, 0, 5000, false)); }
    @Test void hoveringShipWithLiftRests() { assertTrue(ShipSleep.restful(false, false, 0, false, 6000, 5000, false)); }
    @Test void sinkingShipNeverRests() { assertFalse(ShipSleep.restful(false, false, 0, false, 4000, 5000, false)); }
    @Test void sunkShipLyingOnTerrainRests() { assertTrue(ShipSleep.restful(false, false, 0, false, 4000, 5000, true)); }
    @Test void groundedShipStillNeedsNoCrewNoCruiseAndNoMotion() {
        assertFalse(ShipSleep.restful(true, false, 0, false, 0, 5000, true), "crew aboard a wreck keep it awake");
        assertFalse(ShipSleep.restful(false, true, 0, false, 0, 5000, true));
        assertFalse(ShipSleep.restful(false, false, 1e-4, false, 0, 5000, true), "still sliding");
    }
    @Test void movingOrCruisingShipNeverRests() {
        assertFalse(ShipSleep.restful(false, false, 1e-4, true, 0, 5000, false));
        assertFalse(ShipSleep.restful(false, true, 0, true, 0, 5000, false));
    }
    @Test void boardingOrCrewKeepsAShipAwake() { assertFalse(ShipSleep.restful(true, false, 0, true, 0, 5000, false)); }
    @Test void sleepsOnlyAfterThirtyIdleSecondsWithNobodyNear() {
        assertFalse(ShipSleep.sleeps(true, false, 599));
        assertTrue(ShipSleep.sleeps(true, false, 600));
        assertFalse(ShipSleep.sleeps(true, true, 10_000));
    }
    @Test void idleTimeOnlyBuildsWhileRestfulAndNobodyIsNear() {
        assertEquals(20, ShipSleep.nextIdle(true, false, 0));
        assertEquals(0, ShipSleep.nextIdle(true, true, 580), "a nearby player resets the count");
        assertEquals(0, ShipSleep.nextIdle(false, false, 580));
        long idle = 0;
        for (int i = 0; i < 100; i++) idle = ShipSleep.nextIdle(true, true, idle);
        idle = ShipSleep.nextIdle(true, false, idle);
        assertFalse(ShipSleep.sleeps(true, false, idle), "leaving range must not put the ship to sleep at once");
    }
    @Test void unattendedTimeBuildsOnlyWithNobodyAboardOrWithinRange() {
        assertEquals(20, ShipSleep.nextUnattended(false, 0));
        assertEquals(600, ShipSleep.nextUnattended(false, 580));
        assertEquals(0, ShipSleep.nextUnattended(true, 580), "a player aboard or within 64 blocks resets the count");
    }
    @Test void cruiseLapsesAfterThirtyUnattendedSeconds() {
        long ticks = 0; int checks = 0;
        while (!ShipSleep.cruiseLapses(false, false, ticks)) { ticks = ShipSleep.nextUnattended(false, ticks); checks++; }
        assertEquals(30, checks, "thirty once-a-second checks");
        assertEquals(ShipSleep.CRUISE_LAPSE, ticks);
        assertFalse(ShipSleep.cruiseLapses(false, false, 580));
        assertTrue(ShipSleep.cruiseLapses(true, true, 600), "the unattended count alone is enough");
    }
    @Test void cruiseLapsesAtOnceWhenThePilotLeavesAnEmptyShip() {
        assertTrue(ShipSleep.cruiseLapses(true, false, 0));
        assertFalse(ShipSleep.cruiseLapses(true, true, 0), "other crew aboard keep it cruising");
        assertFalse(ShipSleep.cruiseLapses(false, false, 0), "a pilot still aboard keeps it cruising");
    }
    @Test void aWokenShipStirsForASecondAndUntilItsEntitiesLoad() {
        assertTrue(ShipSleep.stirring(1000, 1000, true), "the tick it woke");
        assertTrue(ShipSleep.stirring(1004, 1000, true), "four ticks later, a held use");
        assertTrue(ShipSleep.stirring(1019, 1000, true));
        assertFalse(ShipSleep.stirring(1020, 1000, true), "twenty ticks on, with entities loaded");
        assertTrue(ShipSleep.stirring(5000, 1000, false), "entities still unloaded");
        assertFalse(ShipSleep.stirring(0, Long.MIN_VALUE, true), "awake since it loaded");
        assertFalse(ShipSleep.stirring(Long.MAX_VALUE, Long.MIN_VALUE, true));
        assertTrue(ShipSleep.stirring(0, Long.MIN_VALUE, false));
    }
}
