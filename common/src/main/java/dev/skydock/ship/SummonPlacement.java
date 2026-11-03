package dev.skydock.ship;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a summoned ship may appear, best first. The first spot puts the bow along the player's view (rounded to 15 degrees),
 * the stern {@link #STERN_GAP} blocks ahead of them and the keel at their feet; later spots step out in rings of
 * {@link #RING_STEP} up to {@link #MAX_RING} blocks, trying each ring at heights 0 to {@link #MAX_RISE} before the next.
 */
public final class SummonPlacement {
    public static final double STERN_GAP = 3;
    public static final int RING_STEP = 4, MAX_RING = 24, RISE_STEP = 4, MAX_RISE = 16;
    /** Ring directions as (forward, right) unit steps: ahead first, then the sides, then behind. */
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {-1, 0}};
    private SummonPlacement() {}

    /** A player's yaw (0 south, 90 west) as a ship yaw (0 north, 90 east) facing the same way, rounded to 15 degrees. */
    public static double shipYaw(float playerYaw) { return Navigation.snapHeading(playerYaw + 180); }

    /** {@code hull} is the ship's pivot-relative hull box, as {@link Ship#hullBounds()} gives it. */
    public static List<ShipPose> candidates(Vec3 feet, float playerYaw, AABB hull) {
        double yaw = shipYaw(playerYaw);
        Vec3 forward = ShipPose.rotate(new Vec3(0, 0, -1), yaw), right = ShipPose.rotate(new Vec3(1, 0, 0), yaw);
        Vec3 stern = ShipPose.rotate(new Vec3((hull.minX + hull.maxX) / 2, 0, hull.maxZ), yaw);
        Vec3 base = feet.add(forward.scale(STERN_GAP)).subtract(stern.x, 0, stern.z);
        double keel = Math.floor(feet.y) - hull.minY;
        List<ShipPose> poses = new ArrayList<>();
        for (int ring = 0; ring <= MAX_RING; ring += RING_STEP)
            for (int rise = 0; rise <= MAX_RISE; rise += RISE_STEP)
                for (int[] d : ring == 0 ? new int[][]{{0, 0}} : DIRECTIONS) {
                    Vec3 at = base.add(forward.scale(d[0] * ring)).add(right.scale(d[1] * ring));
                    poses.add(new ShipPose(at.x, keel + rise, at.z, yaw));
                }
        return poses;
    }
}
