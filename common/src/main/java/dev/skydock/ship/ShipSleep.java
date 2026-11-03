package dev.skydock.ship;

/** When a parked ship may stop ticking and release its chunks. */
public final class ShipSleep {
    /** Consecutive restful ticks (30 s) before a ship goes dormant. */
    public static final long SLEEP_AFTER = 600;
    /** Matches ShipNetwork's untrack range, so no client is tracking a ship when it sleeps. */
    public static final double WAKE_RANGE = 288;
    /** Squared speed in blocks/tick: the 0.001 blocks/tick speed limit squared. */
    private static final double STILL = 1e-6;
    private ShipSleep() {}

    /** Crewed means a pilot, seated crew, or a boarding player. A ship rests when nothing moves it and it will not sink. */
    public static boolean restful(boolean crewed, boolean cruise, double speedSq, boolean moored, double lift, double mass) {
        return !crewed && !cruise && speedSq < STILL && (moored || lift >= mass);
    }
    public static boolean sleeps(boolean restful, boolean playerNear, long idleTicks) { return restful && !playerNear && idleTicks >= SLEEP_AFTER; }
}
