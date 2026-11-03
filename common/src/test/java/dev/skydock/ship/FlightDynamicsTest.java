package dev.skydock.ship;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightDynamicsTest {
    @Test void inertiaIsSmoothAndMatchesHeavyShips() {
        assertEquals(.5, FlightDynamics.inertia(0), 1e-9);
        assertEquals(Math.sqrt(1.25), FlightDynamics.inertia(20_000), 1e-9);
        assertEquals(Math.sqrt(4.25), FlightDynamics.inertia(40_000), 1e-9);
        assertEquals(12.298, FlightDynamics.inertia(245_760), .001);
        for (double m = 0; m < 250_000; m += 500) assertTrue(FlightDynamics.inertia(m + 500) > FlightDynamics.inertia(m));
    }
    @Test void engineLoadFollowsThrottleAndSteering() {
        assertEquals(1, FlightDynamics.engineLoad(1, 0), 1e-9);
        assertEquals(.1, FlightDynamics.engineLoad(.1, 0), 1e-9);
        assertEquals(.35, FlightDynamics.engineLoad(0, 1), 1e-9);
        assertEquals(.5, FlightDynamics.engineLoad(-.5, 1), 1e-9);
    }
    @Test void holdClimbPushesTowardTarget() {
        assertTrue(FlightDynamics.holdClimb(100, 99, 0) > 0);
        assertTrue(FlightDynamics.holdClimb(100, 101, 0) < 0);
        assertEquals(0, FlightDynamics.holdClimb(100, 100, 0), 1e-9);
        assertEquals(1, FlightDynamics.holdClimb(200, 100, 0), 1e-9);
        assertEquals(-1, FlightDynamics.holdClimb(0, 100, 0), 1e-9);
    }
    @Test void holdClimbDampsVerticalSpeed() {
        assertTrue(FlightDynamics.holdClimb(100, 100, .1) < 0);
        assertTrue(FlightDynamics.holdClimb(100, 100, -.1) > 0);
    }
    @Test void headingSteerTurnsTheShortWay() {
        assertTrue(FlightDynamics.headingSteer(10, 350, 0) > 0);
        assertTrue(FlightDynamics.headingSteer(350, 10, 0) < 0);
        assertEquals(0, FlightDynamics.headingSteer(90, 90, 0), 1e-9);
        assertEquals(1, FlightDynamics.headingSteer(170, 0, 0), 1e-9);
        assertTrue(FlightDynamics.headingSteer(90, 90, 1) < 0, "damps an ongoing turn");
    }
}
