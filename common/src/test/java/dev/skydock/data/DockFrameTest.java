package dev.skydock.data;

import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DockFrameTest {
    private static final BlockPos CONTROLLER = new BlockPos(100, 64, 200);
    private static DockFrame frame(Direction facing) { return new DockFrame(CONTROLLER, facing, DockTier.SCOUT); }
    private static void near(Vec3 expected, Vec3 actual) {
        assertTrue(expected.distanceTo(actual) < 1e-9, () -> "expected " + expected + " but was " + actual);
    }

    @Test void northFrameMatchesTheOriginalLayout() {
        var f = frame(Direction.NORTH);
        assertEquals(new BlockPos(84, 65, 201), f.origin());
        assertEquals(new AABB(84, 65, 201, 116, 85, 233), f.envelope());
        assertEquals(Rotation.NONE, f.rotation());
        assertEquals(0, f.yaw());
        for (BlockPos p : BlockPos.betweenClosed(0, 0, 0, 31, 19, 31)) assertEquals(f.origin().offset(p), f.toWorld(p));
    }
    @Test void rotationsAndYawsFollowTheFacing() {
        assertEquals(Rotation.CLOCKWISE_90, frame(Direction.EAST).rotation());
        assertEquals(Rotation.CLOCKWISE_180, frame(Direction.SOUTH).rotation());
        assertEquals(Rotation.COUNTERCLOCKWISE_90, frame(Direction.WEST).rotation());
        assertEquals(90, frame(Direction.EAST).yaw());
        assertEquals(180, frame(Direction.SOUTH).yaw());
        assertEquals(270, frame(Direction.WEST).yaw());
    }
    @Test void toLocalInvertsToWorldForEveryFacing() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var f = frame(facing);
            for (BlockPos p : BlockPos.betweenClosed(-3, -2, -3, 33, 21, 33)) assertEquals(p, f.toLocal(f.toWorld(p)), facing + " " + p);
        }
    }
    @Test void bowPointsTheFacingDirection() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var f = frame(facing); int w = DockTier.SCOUT.width;
            near(Vec3.atLowerCornerOf(facing.getNormal()), ShipPose.rotate(new Vec3(0, 0, -1), f.yaw()));
            assertEquals(facing.getNormal(), f.toWorld(new BlockPos(w / 2, 0, 0)).subtract(f.toWorld(new BlockPos(w / 2, 0, 1))));
            // The row nearest the bow sits right behind the controller, one block up.
            assertEquals(CONTROLLER.relative(facing.getOpposite()).above(), f.toWorld(new BlockPos(w / 2, 0, 0)));
        }
    }
    @Test void envelopeStaysBehindTheController() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var f = frame(facing); AABB box = f.envelope();
            assertFalse(box.contains(Vec3.atCenterOf(CONTROLLER)), facing.toString());
            assertTrue(box.contains(Vec3.atCenterOf(CONTROLLER.relative(facing.getOpposite()).above())), facing.toString());
            assertFalse(box.contains(Vec3.atCenterOf(CONTROLLER.relative(facing).above())), facing.toString());
            assertEquals(32, box.getXsize()); assertEquals(20, box.getYsize()); assertEquals(32, box.getZsize());
            for (BlockPos p : BlockPos.betweenClosed(0, 0, 0, 31, 19, 31)) assertTrue(box.contains(Vec3.atCenterOf(f.toWorld(p))), facing + " " + p);
        }
    }
    @Test void vectorsAndCellsAgree() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var f = frame(facing);
            for (BlockPos p : BlockPos.betweenClosed(0, 0, 0, 31, 3, 31))
                near(Vec3.atCenterOf(f.toWorld(p)), f.toWorld(Vec3.atCenterOf(p)));
            assertEquals(f.envelope(), f.toWorld(new AABB(0, 0, 0, 32, 20, 32)));
        }
    }
    @Test void northLaunchPoseMatchesTheOriginalFormula() {
        Vec3 pivot = new Vec3(13.5, 0, 9);
        BlockPos origin = CONTROLLER.offset(-DockTier.SCOUT.width / 2, 1, 1);   // the 0.1 DockTier.origin
        ShipPose old = new ShipPose(origin.getX() + pivot.x, origin.getY(), origin.getZ() + pivot.z, 0);
        assertEquals(old, Ship.berthPose(frame(Direction.NORTH), BlockPos.ZERO, pivot));
        BlockPos offset = new BlockPos(8, 0, 6);
        BlockPos shifted = origin.offset(offset);
        Vec3 destination = Vec3.atLowerCornerOf(shifted).add(pivot);
        assertEquals(new ShipPose(destination.x, destination.y, destination.z, 0), Ship.berthPose(frame(Direction.NORTH), offset, pivot));
    }
    @Test void flyingHullAppearsWhereTheBerthBlocksWere() {
        Vec3 pivot = new Vec3(13.5, 0, 9);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var f = frame(facing); ShipPose pose = Ship.berthPose(f, BlockPos.ZERO, pivot);
            assertEquals(f.yaw(), pose.yaw());
            for (BlockPos c : BlockPos.betweenClosed(0, 0, 0, 31, 2, 31))
                near(Vec3.atCenterOf(f.toWorld(c)), pose.toWorld(Vec3.atCenterOf(c).subtract(pivot)));
        }
    }
}
