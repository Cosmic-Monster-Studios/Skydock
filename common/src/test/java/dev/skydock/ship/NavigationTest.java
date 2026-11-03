package dev.skydock.ship;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationTest {
    private static final ShipPose ORIGIN = new ShipPose(0, 64, 0, 0);

    @Test void berthDueEastHasBearingNinety() {
        Navigation.Berth berth = Navigation.berth(ORIGIN, new Vec3(10, 60, 0), 0);
        assertEquals(10, berth.distance(), 1e-9);
        assertEquals(90, berth.bearing(), 1e-9);
        assertEquals(-4, berth.dy(), 1e-9);
    }
    @Test void berthDueNorthAndSouthUseTheShipPoseConvention() {
        assertEquals(0, Navigation.berth(ORIGIN, new Vec3(0, 64, -5), 0).bearing(), 1e-9);
        assertEquals(180, Navigation.berth(ORIGIN, new Vec3(0, 64, 5), 0).bearing(), 1e-9);
        assertEquals(270, Navigation.berth(ORIGIN, new Vec3(-5, 64, 0), 0).bearing(), 1e-9);
    }
    @Test void yawErrorWraps() {
        assertEquals(15, Navigation.berth(new ShipPose(0, 0, 0, 350), Vec3.ZERO, 5).yawError(), 1e-9);
        assertEquals(-15, Navigation.berth(new ShipPose(0, 0, 0, 5), Vec3.ZERO, 350).yawError(), 1e-9);
    }
    @Test void redockReadyMatchesTheOldRule() {
        assertTrue(Navigation.redockReady(new Navigation.Berth(3.9, 0, 0, 9), .07));
        assertFalse(Navigation.redockReady(new Navigation.Berth(4.1, 0, 0, 9), .07));
        assertFalse(Navigation.redockReady(new Navigation.Berth(3.9, 0, 0, 10.5), .07));
        assertFalse(Navigation.redockReady(new Navigation.Berth(3.9, 0, 0, -10.5), .07));
        assertFalse(Navigation.redockReady(new Navigation.Berth(3.9, 0, 0, 9), .09));
        assertFalse(Navigation.redockReady(new Navigation.Berth(3, 0, 3, 0), 0), "height counts toward the 4 block limit");
    }
    @Test void snapHeadingRoundsToFifteen() {
        assertEquals(0, Navigation.snapHeading(7), 1e-9);
        assertEquals(15, Navigation.snapHeading(8), 1e-9);
        assertEquals(0, Navigation.snapHeading(359), 1e-9);
        assertEquals(345, Navigation.snapHeading(-10), 1e-9);
        assertEquals(90, Navigation.snapHeading(450), 1e-9);
    }
    @Test void compassHasEightPoints() {
        assertEquals("N", Navigation.compass(0));
        assertEquals("NE", Navigation.compass(45));
        assertEquals("E", Navigation.compass(90));
        assertEquals("S", Navigation.compass(180));
        assertEquals("W", Navigation.compass(270));
        assertEquals("N", Navigation.compass(359));
        assertEquals("NW", Navigation.compass(-45));
    }
}
