package dev.skydock.ship;

public final class FlightDynamics {
    // Baseline (unpowered) and best-tier speed limits, 20% above the original .35 / .52 envelope.
    public static final double MAX_SPEED = .42;
    public static final double MAX_TIER_SPEED = .624;
    public static final double ACCELERATION = .012;
    private FlightDynamics() {}
    public static double approach(double current, double target, double step) {
        return current + Math.clamp(target - current, -step, step);
    }
    /** Acceleration divisor: smooth in mass, .5 for an empty hull and ~mass/20000 for heavy ones. */
    public static double inertia(double mass) { return Math.sqrt(.25 + Math.pow(mass / 20000, 2)); }
    /** Share of full engine output in use: throttle either way, or a fraction of it to hold a turn. */
    public static double engineLoad(double appliedThrottle, double steering) {
        return Math.clamp(Math.max(Math.abs(appliedThrottle), .35 * Math.abs(steering)), 0, 1);
    }
    public static double turnRate(double mass, double hullLength) {
        return Math.clamp(1.8 / Math.max(1, Math.sqrt(mass / 20000) * Math.max(1, hullLength / 48)), .45, 1.8);
    }
    /** Climb input that holds an altitude: proportional to the error, damped by vertical speed. */
    public static double holdClimb(double targetY, double y, double vy) { return Math.clamp(.6 * (targetY - y) - 4 * vy, -1, 1); }
    /** Steering input that turns the short way to a heading, damped by the current turn rate. */
    public static double headingSteer(double targetYaw, double yaw, double yawVelocity) {
        return Math.clamp(net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw) / 15 - yawVelocity / 2, -1, 1);
    }
    public static double cruiseThrottle(double speed, double target) {
        return Math.clamp((.03 * target + .2 * (target - speed)) / ACCELERATION, -1, 1);
    }
}
