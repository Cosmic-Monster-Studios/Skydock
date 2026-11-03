package dev.skydock.ship;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipSleepTest {
    @Test void parkedMooredShipRests() { assertTrue(ShipSleep.restful(false, false, 0, true, 0, 5000)); }
    @Test void hoveringShipWithLiftRests() { assertTrue(ShipSleep.restful(false, false, 0, false, 6000, 5000)); }
    @Test void sinkingShipNeverRests() { assertFalse(ShipSleep.restful(false, false, 0, false, 4000, 5000)); }
    @Test void movingOrCruisingShipNeverRests() {
        assertFalse(ShipSleep.restful(false, false, 1e-4, true, 0, 5000));
        assertFalse(ShipSleep.restful(false, true, 0, true, 0, 5000));
    }
    @Test void boardingOrCrewKeepsAShipAwake() { assertFalse(ShipSleep.restful(true, false, 0, true, 0, 5000)); }
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
}
