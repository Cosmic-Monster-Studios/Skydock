package dev.skydock.ship;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SummonPlacementTest {
    private static final AABB HULL = new AABB(-5, 1, -10, 5, 7, 10);
    private static final Vec3 FEET = new Vec3(.5, 64, .5);

    @Test void shipYawFacesTheWayThePlayerLooks() {
        assertEquals(180, SummonPlacement.shipYaw(0));
        assertEquals(270, SummonPlacement.shipYaw(90));
        assertEquals(90, SummonPlacement.shipYaw(-90));
        assertEquals(0, SummonPlacement.shipYaw(180));
        assertEquals(180, SummonPlacement.shipYaw(7));
        assertEquals(195, SummonPlacement.shipYaw(8));
    }
    @Test void firstSpotPutsTheSternThreeBlocksAheadWithTheKeelAtTheFeet() {
        ShipPose first = SummonPlacement.candidates(FEET, 180, HULL).getFirst();
        assertEquals(0, first.yaw());
        AABB world = first.toWorld(HULL);
        assertEquals(64, world.minY, 1e-9);
        assertEquals(FEET.z - SummonPlacement.STERN_GAP, world.maxZ, 1e-9);
        assertEquals(FEET.x, (world.minX + world.maxX) / 2, 1e-9);
    }
    @Test void everyAxisFacingKeepsTheSternClearOfThePlayer() {
        for (float look : new float[]{0, 90, 180, -90}) {
            AABB world = SummonPlacement.candidates(FEET, look, HULL).getFirst().toWorld(HULL);
            assertFalse(world.contains(FEET), "look " + look);
            double gap = Math.max(Math.max(world.minX - FEET.x, FEET.x - world.maxX), Math.max(world.minZ - FEET.z, FEET.z - world.maxZ));
            assertEquals(SummonPlacement.STERN_GAP, gap, 1e-9, "look " + look);
        }
    }
    @Test void spotsStepOutwardRingByRingAndRiseWithinEachRing() {
        List<ShipPose> poses = SummonPlacement.candidates(FEET, 180, HULL);
        int heights = SummonPlacement.MAX_RISE / SummonPlacement.RISE_STEP + 1, rings = SummonPlacement.MAX_RING / SummonPlacement.RING_STEP;
        assertEquals(heights + rings * heights * 8, poses.size());
        ShipPose first = poses.get(0);
        assertEquals(first.y() + SummonPlacement.RISE_STEP, poses.get(1).y(), 1e-9);
        assertEquals(first.x(), poses.get(1).x(), 1e-9);
        ShipPose ahead = poses.get(heights);
        assertEquals(first.z() - SummonPlacement.RING_STEP, ahead.z(), 1e-9);
        assertEquals(first.y(), ahead.y(), 1e-9);
        ShipPose last = poses.getLast();
        assertEquals(first.y() + SummonPlacement.MAX_RISE, last.y(), 1e-9);
        assertEquals(first.z() + SummonPlacement.MAX_RING, last.z(), 1e-9);
        for (ShipPose pose : poses) assertEquals(0, pose.yaw());
    }
}
