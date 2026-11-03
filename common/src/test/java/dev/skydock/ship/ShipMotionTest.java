package dev.skydock.ship;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipMotionTest {
    @Test void tenHzPacketsProduceUniformTwentyHzMovement() {
        ShipMotion motion = new ShipMotion();
        double last = 0;
        for (int tick = 0; tick < 100; tick++) {
            if (tick % 2 == 0) motion.accept(tick, new ShipPose(tick * .3, 80, 0, tick * 1.5), new Vec3(.3, 0, 0), 1.5);
            ShipPose pose = motion.advance();
            if (tick > 4) assertEquals(.3, pose.x() - last, 1e-9);
            last = pose.x();
        }
    }
    @Test void bufferedMotionToleratesOneTickOfPacketJitter() {
        ShipMotion motion = new ShipMotion();
        double last = 0;
        for (int tick = 0; tick < 100; tick++) {
            int received = tick % 4 == 3 ? tick - 1 : tick % 4 == 0 ? tick : -1;
            if (received >= 0) motion.accept(received, new ShipPose(received * .25, 80, 0, 0), new Vec3(.25, 0, 0), 0);
            double x = motion.advance().x();
            if (tick > 4) assertEquals(.25, x - last, 1e-9);
            last = x;
        }
    }
    @Test void hullRefreshBeforePhysicsDoesNotDisturbTheMotionTimeline() {
        ShipMotion server = new ShipMotion(), client = new ShipMotion();
        double last = 0;
        for (int tick = 0; tick < 100; tick++) {
            if (tick > 0 && tick % 10 == 0) {
                long poseTick = server.latestTick(tick);
                client.accept(poseTick, server.sample(poseTick), new Vec3(.35, 0, 0), 0);
            }
            ShipPose pose = new ShipPose(tick * .35, 64, 0, 0);
            server.accept(tick, pose, new Vec3(.35, 0, 0), 0);
            if (tick % 2 == 0) client.accept(server.latestTick(tick), pose, new Vec3(.35, 0, 0), 0);
            double x = client.advance().x();
            if (tick > 4) assertEquals(.35, x - last, 1e-9);
            last = x;
        }
    }
    @Test void missingPacketsCannotExtrapolateForever() {
        ShipMotion motion = new ShipMotion();
        motion.accept(100, new ShipPose(10, 80, 20, 40), new Vec3(.3, 0, -.2), 1);
        for (int i = 0; i < 100; i++) motion.advance();
        assertEquals(10.6, motion.advance().x(), 1e-9);
        assertEquals(42, motion.advance().yaw(), 1e-9);
    }
    @Test void wrapAroundTurnsTakeTheShortPath() {
        assertEquals(180, new ShipPose(0, 0, 0, 179).interpolate(new ShipPose(0, 0, 0, -179), .5).yaw(), 1e-9);
    }
    @Test void viewedRayKeepsItsTargetAsTheShipMovesAndTurns() {
        ShipPose viewed = new ShipPose(0, 64, 0, 15), current = new ShipPose(.9, 64, -.7, 20);
        Vec3 eye = new Vec3(.5, 2.62, -7.5), target = new Vec3(-1.5, 1.5, -7.5);
        Vec3 worldEye = viewed.toWorld(eye), worldRay = viewed.toWorld(target).subtract(worldEye);
        Vec3 localEye = viewed.toLocal(worldEye), localRay = ShipPose.rotate(worldRay, -viewed.yaw());
        Vec3 projected = current.toWorld(localEye).add(ShipPose.rotate(localRay, current.yaw()));
        assertEquals(0, projected.distanceTo(current.toWorld(target)), 1e-9);
    }
    @Test void interactionHistoryRejectsStaleAndInvalidTimes() {
        ShipMotion motion = new ShipMotion();
        for (int i = 0; i <= 40; i++) motion.accept(i, new ShipPose(i, 0, 0, 0), new Vec3(1, 0, 0), 0);
        assertFalse(motion.contains(0)); assertFalse(motion.contains(43)); assertFalse(motion.contains(Double.NaN));
        assertTrue(motion.contains(37)); assertTrue(motion.contains(42));
        motion.accept(2, new ShipPose(-100, 0, 0, 0), Vec3.ZERO, 0);
        assertEquals(37, motion.sample(37).x());
    }
    @Test void cruiseConvergesInBothDirectionsWithoutOscillation() {
        for (double target : new double[]{-.3, .18, .35}) {
            double speed = 0, throttle = 0;
            for (int i = 0; i < 200; i++) {
                throttle = FlightDynamics.approach(throttle, FlightDynamics.cruiseThrottle(speed, target), .1);
                speed = speed * .97 + throttle * FlightDynamics.ACCELERATION;
                assertTrue(Math.abs(speed) <= Math.abs(target) + .015);
            }
            assertEquals(target, speed, 1e-6);
        }
    }
    @Test void SteeringRampsAndRemainsUsableForLargeShips() {
        assertEquals(1.8, FlightDynamics.turnRate(4000, 16));
        assertTrue(FlightDynamics.turnRate(300000, 96) >= .45);
        double turn = 0;
        for (int i = 0; i < 10; i++) turn = FlightDynamics.approach(turn, 1.8, .18);
        assertEquals(1.8, turn, 1e-9);
        for (int i = 0; i < 7; i++) turn = FlightDynamics.approach(turn, 0, .28);
        assertEquals(0, turn, 1e-9);
    }
    @Test void steeringReversalCrossesZeroWithoutOvershoot() {
        double turn = 1.8;
        for (int i = 0; i < 20; i++) {
            double previous = turn;
            turn = FlightDynamics.approach(turn, -1.8, .18);
            assertTrue(turn <= previous);
            assertTrue(turn >= -1.8 - 1e-9);
        }
        assertEquals(-1.8, turn, 1e-9);
    }
    @Test void aJumpSnapsInsteadOfInterpolating() {
        ShipMotion motion = new ShipMotion();
        for (int tick = 0; tick <= 20; tick += 2) { motion.accept(tick, new ShipPose(tick * .3, 80, 0, 0), new Vec3(.3, 0, 0), 0); motion.advance(); motion.advance(); }
        motion.accept(22, new ShipPose(500, 90, -300, 45), Vec3.ZERO, 0);
        ShipPose pose = motion.advance();
        assertEquals(500, pose.x(), 1e-9); assertEquals(90, pose.y(), 1e-9); assertEquals(-300, pose.z(), 1e-9); assertEquals(45, pose.yaw(), 1e-9);
    }
}
