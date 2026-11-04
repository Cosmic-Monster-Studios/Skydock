package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipYardItemsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Ship ship() {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.yard = new BlockPos(512, 64, 256);
        ship.blocks.put(new BlockPos(2, 0, 3), Blocks.OAK_PLANKS.defaultBlockState());
        ship.blocks.put(new BlockPos(6, 2, 9), Blocks.JUKEBOX.defaultBlockState());
        ship.initializePivot();
        ship.pose = new ShipPose(100.5, 80, -40.5, 90); ship.velocity = new Vec3(.2, 0, 0);
        return ship;
    }
    private static void near(Vec3 expected, Vec3 actual) { near(expected, actual, ""); }
    private static void near(Vec3 expected, Vec3 actual, String why) { assertTrue(expected.distanceTo(actual) < 1e-9, why + " " + expected + " != " + actual); }

    @Test void theYardBoxIsTheHullInTheShipyardTwoBlocksWider() {
        assertEquals(new AABB(512 + 2 - 2, 64 - 2, 256 + 3 - 2, 512 + 7 + 2, 64 + 3 + 2, 256 + 10 + 2), ShipManager.yardBox(ship()));
    }
    @Test void aYardPointLandsWhereItsCellIsInTheWorld() {
        Ship ship = ship(); BlockPos jukebox = new BlockPos(6, 2, 9);
        near(ship.blockToWorld(Vec3.atCenterOf(jukebox)), ShipManager.yardToWorld(ship, Vec3.atCenterOf(ship.yard.offset(jukebox))));
        near(ship.blockToWorld(new Vec3(6.5, 3.2, 10.6)), ShipManager.yardToWorld(ship, Vec3.atLowerCornerOf(ship.yard).add(6.5, 3.2, 10.6)));
    }
    @Test void motionKeepsItsCourseRelativeToTheShip() {
        near(new Vec3(0, .1, 0), ShipManager.worldMotion(new Vec3(0, .1, 0), 0, Vec3.ZERO));
        near(ShipPose.rotate(new Vec3(0, 0, -.3), 90).add(.2, 0, 0), ShipManager.worldMotion(new Vec3(0, 0, -.3), 90, new Vec3(.2, 0, 0)));
        near(new Vec3(.2, -.04, 0), ShipManager.worldMotion(new Vec3(0, -.04, 0), 90, new Vec3(.2, 0, 0)), "an item at rest aboard moves with the hull");
    }
}
