package dev.skydock.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Optional;

/** Pure navigation maths shared by the berth HUD and the redock command, in the {@link ShipPose} yaw convention (0 north, 90 east). */
public final class Navigation {
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private Navigation() {}
    /** Where a berth lies from the ship: horizontal distance, compass bearing 0-360, berth height minus ship height, and berth yaw minus ship yaw wrapped to -180..180. */
    public record Berth(double distance, double bearing, double dy, double yawError) {}
    public static Berth berth(ShipPose pose, Vec3 berthCenter, double berthYaw) {
        double dx = berthCenter.x - pose.x(), dz = berthCenter.z - pose.z();
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        return new Berth(Math.hypot(dx, dz), bearing < 0 ? bearing + 360 : bearing, berthCenter.y - pose.y(), Mth.wrapDegrees(berthYaw - pose.yaw()));
    }
    /** The redock tolerance: within 4 blocks in 3D, 10 degrees of the berth's yaw, and at most .08 blocks/tick. */
    public static boolean redockReady(Berth berth, double speed) {
        return berth.distance * berth.distance + berth.dy * berth.dy <= 16 && Math.abs(berth.yawError) <= 10 && speed <= .08;
    }
    /** Rounds a yaw to the nearest 15 degrees, in 0-360. */
    public static double snapHeading(double yaw) { return Math.floorMod(Math.round(yaw / 15) * 15, 360); }
    /** HUD text for the berth line: distance, bearing (0-359), signed height difference and signed yaw error, all whole numbers. */
    public static String[] berthText(Berth berth) {
        return new String[]{String.valueOf(Math.round(berth.distance)), String.valueOf(Math.floorMod(Math.round(berth.bearing), 360L)),
                String.format(java.util.Locale.ROOT, "%+d", Math.round(berth.dy)), String.format(java.util.Locale.ROOT, "%+d", Math.round(berth.yawError))};
    }
    /** The target whose centre is horizontally closest to the ship; height is ignored. */
    public static Optional<BlockPos> nearest(ShipPose pose, List<BlockPos> targets) {
        return targets.stream().min(java.util.Comparator.comparingDouble(t -> Math.hypot(t.getX() + .5 - pose.x(), t.getZ() + .5 - pose.z())));
    }
    /** The 8-point compass name of a yaw. */
    public static String compass(double yaw) { return POINTS[(int) Math.floorMod(Math.round(yaw / 45), 8L)]; }
}
