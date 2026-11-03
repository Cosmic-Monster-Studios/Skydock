package dev.skydock.cargo;

import net.minecraft.world.phys.Vec3;

/** Per-tick flight of an ejected pod: a short tumble, then a balloon that caps the fall and drifts with the world wind. */
public final class PodDrift {
    public static final int TUMBLE = 20;
    private static final double GRAVITY = .08, DRAG = .98, SINK = -.05, EASE = .1, BRAKE = .3;
    private PodDrift() {}

    /** The next tick's velocity. The wind blows along {@code angle} measured from +X towards +Z, at {@code speed} blocks per second. */
    public static Vec3 step(Vec3 velocity, int age, CargoSavedData.Wind wind) {
        if (age < TUMBLE) return velocity.add(0, -GRAVITY, 0).scale(DRAG);
        double wx = Math.cos(wind.angle()) * wind.speed() / 20, wz = Math.sin(wind.angle()) * wind.speed() / 20;
        return new Vec3(ease(velocity.x, wx), velocity.y + (SINK - velocity.y) * BRAKE, ease(velocity.z, wz));
    }
    private static double ease(double from, double to) { return from + (to - from) * EASE; }
}
