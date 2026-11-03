package dev.skydock.ship;

import net.minecraft.core.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Broad phase is the rotated occupied hull box; narrow phase samples nearby hull voxel shapes. */
public final class ShipCollision {
    public static final double ATTACHMENT_HEIGHT = 2.5;

    public static List<VoxelShape> shapes(Ship ship, ShipPose pose, AABB worldQuery) {
        ShipPose.Transform transform = pose == ship.pose ? ship.transform() : pose.transform();
        AABB hull = pose == ship.pose ? ship.hullWorldBounds() : transform.toWorld(ship.hullBounds());
        if (!hull.inflate(.05).intersects(worldQuery)) return List.of();
        List<VoxelShape> result = new ArrayList<>();
        AABB local = transform.toLocal(worldQuery).move(ship.center()).inflate(1);
        ShipBlockView view = new ShipBlockView(ship);
        for (BlockPos p : BlockPos.betweenClosed(
                new BlockPos(Math.max(0, Mth.floor(local.minX)), Math.max(0, Mth.floor(local.minY)), Math.max(0, Mth.floor(local.minZ))),
                new BlockPos(Math.min(ship.tier.width - 1, Mth.floor(local.maxX)), Math.min(ship.tier.height - 1, Mth.floor(local.maxY)), Math.min(ship.tier.length - 1, Mth.floor(local.maxZ))))) {
            BlockState state = ship.state(p); if (state.isAir()) continue;
            for (AABB box : state.getCollisionShape(view, p).toAabbs()) {
                AABB rotated = transform.toWorld(box.move(p.getX() - ship.center().x, p.getY(), p.getZ() - ship.center().z));
                if (rotated.intersects(worldQuery)) result.add(Shapes.create(rotated));
            }
        }
        return result;
    }
    public static boolean supported(Ship ship, ShipPose pose, AABB entity) {
        AABB feet = new AABB(entity.minX + .03, entity.minY - .08, entity.minZ + .03, entity.maxX - .03, entity.minY + .001, entity.maxZ - .03);
        return !shapes(ship, pose, feet).isEmpty();
    }
    /** Keeps an airborne entity in the ship frame only while real hull collision remains below its footprint. */
    public static boolean aboveHull(Ship ship, ShipPose pose, AABB entity) {
        AABB column = new AABB(entity.minX + .03, entity.minY - ATTACHMENT_HEIGHT, entity.minZ + .03,
                entity.maxX - .03, entity.minY + .08, entity.maxZ - .03);
        for (VoxelShape shape : shapes(ship, pose, column)) {
            double top = shape.bounds().maxY;
            if (top <= entity.minY + .08 && top >= entity.minY - ATTACHMENT_HEIGHT) return true;
        }
        return false;
    }
    public static Vec3 collide(Entity entity, Vec3 requested) {
        // Ejected pods fall through hulls and land only on terrain.
        if (entity.noPhysics || entity.isSpectator() || requested.lengthSqr() == 0 || entity instanceof dev.skydock.cargo.PodEntity) return requested;
        AABB box = entity.getBoundingBox();
        List<VoxelShape> shapes = new ArrayList<>();
        AABB area = box.expandTowards(requested).inflate(entity.maxUpStep() + .05);
        for (Ship ship : ShipManager.ships(entity.level())) shapes.addAll(shapes(ship, ship.pose, area));
        if (shapes.isEmpty()) return requested;
        for (VoxelShape shape : entity.level().getBlockCollisions(entity, area)) shapes.add(shape);
        Vec3 result = resolve(box, requested, shapes);
        boolean wall = result.x != requested.x || result.z != requested.z;
        if (wall && entity.maxUpStep() > 0 && (entity.onGround() || requested.y < 0 && result.y != requested.y)) {
            Vec3 step = resolve(box, new Vec3(requested.x, entity.maxUpStep(), requested.z), shapes);
            if (step.horizontalDistanceSqr() > result.horizontalDistanceSqr()) {
                Vec3 down = resolve(box.move(step), new Vec3(0, requested.y - step.y, 0), shapes);
                result = step.add(down);
            }
        }
        return result;
    }
    private static Vec3 resolve(AABB box, Vec3 motion, List<VoxelShape> shapes) {
        double y = Shapes.collide(Direction.Axis.Y, box, shapes, motion.y); box = box.move(0, y, 0);
        double x, z;
        if (Math.abs(motion.x) < Math.abs(motion.z)) {
            z = Shapes.collide(Direction.Axis.Z, box, shapes, motion.z); box = box.move(0, 0, z);
            x = Shapes.collide(Direction.Axis.X, box, shapes, motion.x);
        } else {
            x = Shapes.collide(Direction.Axis.X, box, shapes, motion.x); box = box.move(x, 0, 0);
            z = Shapes.collide(Direction.Axis.Z, box, shapes, motion.z);
        }
        return new Vec3(x, y, z);
    }
    public static Vec3 sneak(Entity entity, Vec3 input) {
        Ship support = ShipManager.ships(entity.level()).stream().filter(s -> supported(s, s.pose, entity.getBoundingBox())).findFirst().orElse(null);
        if (support == null) return null;
        double x = input.x, z = input.z;
        while (x != 0 && !supported(support, support.pose, entity.getBoundingBox().move(x, 0, 0))) x = shrink(x);
        while (z != 0 && !supported(support, support.pose, entity.getBoundingBox().move(0, 0, z))) z = shrink(z);
        while (x != 0 && z != 0 && !supported(support, support.pose, entity.getBoundingBox().move(x, 0, z))) { x = shrink(x); z = shrink(z); }
        return new Vec3(x, input.y, z);
    }
    private static double shrink(double v) { return Math.abs(v) < .05 ? 0 : v - Math.copySign(.05, v); }
}
