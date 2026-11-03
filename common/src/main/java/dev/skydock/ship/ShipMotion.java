package dev.skydock.ship;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;

/** A short server-timestamped buffer, rather than repeatedly chasing the newest packet. */
public final class ShipMotion {
    private record Sample(long tick, ShipPose pose, Vec3 velocity, double yawVelocity) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double cursor;
    public double time() { return cursor; }
    public long latestTick(long fallback) { return samples.isEmpty() ? fallback : samples.getLast().tick; }
    public boolean contains(double time) { return !samples.isEmpty() && time >= samples.getFirst().tick && time <= samples.getLast().tick + 2; }
    /** A move this far between samples is a jump (a summon), not flight; interpolating it would streak the hull across the map. */
    static final double JUMP = 32;
    public void accept(long tick, ShipPose pose, Vec3 velocity, double yawVelocity) {
        if (!samples.isEmpty() && tick <= samples.getLast().tick) return;
        if (!samples.isEmpty()) {
            ShipPose last = samples.getLast().pose;
            if (Math.abs(pose.x() - last.x()) + Math.abs(pose.y() - last.y()) + Math.abs(pose.z() - last.z()) > JUMP) samples.clear();
        }
        if (samples.isEmpty() || cursor < tick - 12 || cursor > tick + 3) cursor = tick - 3;
        samples.addLast(new Sample(tick, pose, velocity, yawVelocity));
        while (samples.size() > 20) samples.removeFirst();
    }
    public ShipPose advance() {
        if (samples.isEmpty()) throw new IllegalStateException("No ship motion samples");
        cursor = Math.min(cursor + 1, samples.getLast().tick + 2);
        return sample(cursor);
    }
    public ShipPose sample(double time) {
        Sample before = samples.getFirst();
        if (time <= before.tick) return before.pose;
        for (Sample after : samples) {
            if (after.tick >= time) return before.pose.interpolate(after.pose, (time - before.tick) / (after.tick - before.tick));
            before = after;
        }
        double extra = Math.clamp(time - before.tick, 0, 2);
        return new ShipPose(before.pose.x() + before.velocity.x * extra, before.pose.y() + before.velocity.y * extra,
                before.pose.z() + before.velocity.z * extra, before.pose.yaw() + before.yawVelocity * extra);
    }
}
