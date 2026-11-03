package dev.skydock.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A dock's berth in world space. Local cells use the north-facing layout (bow toward local −Z, the controller centred on
 * the front edge one block below the floor); {@code facing} turns that layout about the controller. Every transform is
 * exact integer arithmetic, so a north frame reproduces the original {@code controller.offset(-width / 2, 1, 1)} layout.
 */
public record DockFrame(BlockPos controller, Direction facing, DockTier tier) {
    public DockFrame { if (facing.getAxis().isVertical()) throw new IllegalArgumentException("Dock facing must be horizontal: " + facing); }
    public static Rotation rotation(Direction facing) {
        return switch (facing) { case EAST -> Rotation.CLOCKWISE_90; case SOUTH -> Rotation.CLOCKWISE_180; case WEST -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
    }
    public static Rotation inverse(Rotation rotation) {
        return switch (rotation) { case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90; case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90; default -> rotation; };
    }
    /** The world cell of a local cell, for a berth whose local {@code (0,0,0)} is at {@code origin}. */
    public static BlockPos toWorld(BlockPos origin, Rotation rotation, BlockPos local) { return origin.offset(local.rotate(rotation)); }
    public Rotation rotation() { return rotation(facing); }
    /** Ship yaw of a hull sitting in this berth: north 0, east 90, south 180, west 270, as {@code ShipPose.rotate} turns local −Z. */
    public double yaw() { return switch (facing) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; }; }
    /** The world cell of local {@code (0,0,0)}. */
    public BlockPos origin() { return controller.offset(new BlockPos(-tier.width / 2, 1, 1).rotate(rotation())); }
    public BlockPos toWorld(BlockPos local) { return toWorld(origin(), rotation(), local); }
    public BlockPos toLocal(BlockPos world) { return world.subtract(origin()).rotate(inverse(rotation())); }
    /** A local point, where cell {@code c} spans {@code [c, c + 1)}, to the world point at the same place in the rotated cell. */
    public Vec3 toWorld(Vec3 local) {
        BlockPos o = origin();
        return switch (facing) {
            case EAST -> new Vec3(o.getX() + 1 - local.z, o.getY() + local.y, o.getZ() + local.x);
            case SOUTH -> new Vec3(o.getX() + 1 - local.x, o.getY() + local.y, o.getZ() + 1 - local.z);
            case WEST -> new Vec3(o.getX() + local.z, o.getY() + local.y, o.getZ() + 1 - local.x);
            default -> new Vec3(o.getX() + local.x, o.getY() + local.y, o.getZ() + local.z);
        };
    }
    public AABB toWorld(AABB local) { return new AABB(toWorld(new Vec3(local.minX, local.minY, local.minZ)), toWorld(new Vec3(local.maxX, local.maxY, local.maxZ))); }
    public AABB envelope() { return toWorld(new AABB(0, 0, 0, tier.width, tier.height, tier.length)); }
}
