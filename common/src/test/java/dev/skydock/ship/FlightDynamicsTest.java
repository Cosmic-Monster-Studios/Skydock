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
}
