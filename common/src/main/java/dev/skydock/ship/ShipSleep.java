package dev.skydock.ship;

/** When a parked ship may stop ticking and release its chunks. */
public final class ShipSleep {
    /** Consecutive restful ticks (30 s) before a ship goes dormant. */
    public static final long SLEEP_AFTER = 600;
    /** Matches ShipNetwork's untrack range, so no client is tracking a ship when it sleeps. */
    public static final double WAKE_RANGE = 288;
    /** A ship with no player aboard or within this many blocks of its hull is unattended. */
    public static final double ATTEND_RANGE = 64;
    /** Consecutive unattended ticks (30 s) before cruise disengages. */
    public static final long CRUISE_LAPSE = 600;
    /** Squared speed in blocks/tick: the 0.001 blocks/tick speed limit squared. */
    private static final double STILL = 1e-6;
    private ShipSleep() {}

    /** Crewed means a pilot, seated crew, or a boarding player. A ship rests when nothing moves it and it will not sink. */
    public static boolean restful(boolean crewed, boolean cruise, double speedSq, boolean moored, double lift, double mass) {
        return !crewed && !cruise && speedSq < STILL && (moored || lift >= mass);
    }
    /** The unattended count after one once-a-second check: any player aboard or within {@link #ATTEND_RANGE} resets it. */
    public static long nextUnattended(boolean attended, long unattendedTicks) { return attended ? 0 : unattendedTicks + 20; }
    /** Cruise disengages, leaving the ship to coast to a stop, when its pilot left the game with no other crew aboard, or after {@link #CRUISE_LAPSE} unattended ticks. */
    public static boolean cruiseLapses(boolean pilotLeft, boolean crewAboard, long unattendedTicks) { return pilotLeft && !crewAboard || unattendedTicks >= CRUISE_LAPSE; }
    /** The idle count after one once-a-second check: only restful time with nobody near counts, so it must be unbroken for {@link #SLEEP_AFTER}. */
    public static long nextIdle(boolean restful, boolean playerNear, long idleTicks) { return restful && !playerNear ? idleTicks + 20 : 0; }
    public static boolean sleeps(boolean restful, boolean playerNear, long idleTicks) { return restful && !playerNear && idleTicks >= SLEEP_AFTER; }
}
