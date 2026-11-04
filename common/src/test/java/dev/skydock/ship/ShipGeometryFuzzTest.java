package dev.skydock.ship;

import dev.skydock.data.DockFrame;
import dev.skydock.data.DockTier;
import dev.skydock.fuzz.Fuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Property tests of ship-space maths: poses, summon spots, navigation, motion buffering, flight tuning and repair ordering. */
class ShipGeometryFuzzTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    static ShipPose pose(Random r) { return new ShipPose(Fuzz.worldDouble(r), r.nextDouble() * 700 - 128, Fuzz.worldDouble(r), yaw(r)); }
    static double yaw(Random r) { return switch (r.nextInt(5)) { case 0 -> r.nextInt(24) * 15; case 1 -> r.nextInt(4) * 90; case 2 -> (r.nextDouble() - .5) * 1e5; default -> r.nextDouble() * 720 - 360; }; }
    static Vec3 local(Random r) { return new Vec3((r.nextDouble() - .5) * 200, (r.nextDouble() - .5) * 200, (r.nextDouble() - .5) * 200); }
    static double tol(double... scale) { double m = 1; for (double s : scale) m = Math.max(m, Math.abs(s)); return m * 1e-9; }
    static void near(Vec3 a, Vec3 b, double tol, String what) { assertTrue(a.distanceTo(b) <= tol, what + ": " + a + " vs " + b + " (" + a.distanceTo(b) + ")"); }
    static void near(AABB a, AABB b, double tol, String what) {
        assertTrue(Math.abs(a.minX - b.minX) <= tol && Math.abs(a.minY - b.minY) <= tol && Math.abs(a.minZ - b.minZ) <= tol
                && Math.abs(a.maxX - b.maxX) <= tol && Math.abs(a.maxY - b.maxY) <= tol && Math.abs(a.maxZ - b.maxZ) <= tol, what + ": " + a + " vs " + b);
    }
    static AABB box(Random r) { Vec3 a = local(r), b = local(r); return new AABB(a, b); }

    // ---------------------------------------------------------------- poses

    @Test void posesRoundTripAndTheirTransformsAgree() {
        Fuzz.check("ShipPose round trips", 20000, (r, log) -> {
            ShipPose p = pose(r); Vec3 v = local(r); log.append(p).append(' ').append(v);
            double t = tol(p.x(), p.z(), 200);
            Vec3 world = p.toWorld(v);
            near(v, p.toLocal(world), t * 10, "toLocal(toWorld)");
            near(world, p.transform().toWorld(v), t, "Transform agrees with the pose");
            Vec3 w2 = new Vec3(p.x() + (r.nextDouble() - .5) * 300, p.y() + (r.nextDouble() - .5) * 300, p.z() + (r.nextDouble() - .5) * 300);
            near(w2, p.toWorld(p.toLocal(w2)), t * 10, "toWorld(toLocal)");
            Vec3 rotated = ShipPose.rotate(v, p.yaw());
            assertEquals(v.y, rotated.y, "rotation keeps height");
            assertEquals(v.horizontalDistance(), rotated.horizontalDistance(), 1e-9 * (1 + v.horizontalDistance()), "rotation keeps horizontal length");
            near(v, ShipPose.rotate(rotated, -p.yaw()), 1e-9 * (1 + v.length()), "rotate back");
            if (Math.abs(p.yaw()) < 1e4) near(rotated, ShipPose.rotate(v, p.yaw() + 360), 1e-9 * (1 + v.length()), "rotation is 360-periodic");
            AABB b = box(r);
            near(p.toWorld(b), p.transform().toWorld(b), t * 10, "Transform.toWorld(AABB) matches the corner walk");
            AABB wb = new AABB(w2, w2.add(local(r)));
            near(p.toLocal(wb), p.transform().toLocal(wb), t * 10, "Transform.toLocal(AABB) matches the corner walk");
            AABB enclosing = p.transform().toWorld(b).inflate(t * 10);
            for (int i = 0; i < 8; i++) {
                Vec3 corner = new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
                assertTrue(enclosing.contains(p.toWorld(corner)), "the world box encloses every corner");
            }
        });
    }

    @Test void interpolationTakesTheShortTurnAndHitsBothEnds() {
        Fuzz.check("ShipPose.interpolate", 20000, (r, log) -> {
            ShipPose a = pose(r), b = new ShipPose(a.x() + (r.nextDouble() - .5) * 10, a.y() + (r.nextDouble() - .5) * 10, a.z() + (r.nextDouble() - .5) * 10, yaw(r));
            log.append(a).append(" -> ").append(b);
            ShipPose start = a.interpolate(b, 0), end = a.interpolate(b, 1);
            assertEquals(a, start, "fraction 0 is the start");
            assertEquals(b.x(), end.x(), tol(b.x())); assertEquals(b.y(), end.y(), tol(b.y())); assertEquals(b.z(), end.z(), tol(b.z()));
            double turn = end.yaw() - a.yaw();
            assertTrue(turn >= -180 - 1e-6 && turn <= 180 + 1e-6, "short way round: " + turn);
            assertEquals(0, Mth.wrapDegrees(end.yaw() - b.yaw()), 1e-6 * (1 + Math.abs(a.yaw()) + Math.abs(b.yaw())), "fraction 1 faces the end yaw");
            double f = r.nextDouble(); ShipPose mid = a.interpolate(b, f);
            assertTrue(mid.x() >= Math.min(a.x(), b.x()) - tol(a.x()) && mid.x() <= Math.max(a.x(), b.x()) + tol(a.x()), "x between ends");
            assertTrue(Math.abs(mid.yaw() - a.yaw()) <= 180 * f + 1e-6, "yaw moves at most f of a half turn");
        });
    }

    // ---------------------------------------------------------------- docking geometry

    @Test void aHullInItsBerthLandsEveryCellOnTheBerthsCell() {
        Fuzz.check("berth pose", 4000, (r, log) -> {
            DockTier dockTier = Fuzz.pick(r, DockTier.values()); Direction facing = Fuzz.pick(r, Direction.Plane.HORIZONTAL.stream().toList());
            DockFrame frame = new DockFrame(new BlockPos(r.nextInt(2_000_001) - 1_000_000, r.nextInt(300) - 60, r.nextInt(2_000_001) - 1_000_000), facing, dockTier);
            Ship ship = new Ship(); ship.tier = Fuzz.pick(r, Arrays.stream(DockTier.values()).filter(t -> t.ordinal() <= dockTier.ordinal()).toList());
            for (int i = 1 + r.nextInt(30); i > 0; i--) ship.blocks.put(new BlockPos(r.nextInt(ship.tier.width), r.nextInt(ship.tier.height), r.nextInt(ship.tier.length)), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
            if (r.nextBoolean()) ship.initializePivot();
            BlockPos offset = new BlockPos(r.nextInt(dockTier.width - ship.tier.width + 1), r.nextInt(4), r.nextInt(dockTier.length - ship.tier.length + 1));
            ship.pose = Ship.berthPose(frame, offset, ship.center()); log.append(frame).append(" offset ").append(offset).append(" pivot ").append(ship.center());
            assertEquals(frame.yaw(), ship.pose.yaw());
            for (BlockPos cell : ship.blocks.keySet()) {
                Vec3 world = ship.blockToWorld(Vec3.atCenterOf(cell));
                near(world, Vec3.atCenterOf(frame.toWorld(cell.offset(offset))), 1e-6, "cell " + cell);
                near(Vec3.atCenterOf(cell), ship.worldToBlock(world), 1e-6, "and back");
            }
        });
    }

    @Test void summonSpotsFaceTheViewAndPutTheSternAheadOfThePlayer() {
        Fuzz.check("SummonPlacement", 5000, (r, log) -> {
            Vec3 feet = new Vec3(Fuzz.worldDouble(r), r.nextDouble() * 400 - 64, Fuzz.worldDouble(r));
            float playerYaw = switch (r.nextInt(4)) { case 0 -> r.nextInt(24) * 15f; case 1 -> (float) ((r.nextDouble() - .5) * 1e5); default -> (float) (r.nextDouble() * 720 - 360); };
            AABB hull = new AABB(-r.nextInt(48) - r.nextDouble(), r.nextInt(5) - r.nextDouble(), -r.nextInt(48) - r.nextDouble(), r.nextInt(48) + r.nextDouble(), 5 + r.nextInt(40), r.nextInt(48) + r.nextDouble());
            log.append("feet=").append(feet).append(" yaw=").append(playerYaw).append(" hull=").append(hull);
            List<ShipPose> spots = SummonPlacement.candidates(feet, playerYaw, hull);
            int rings = SummonPlacement.MAX_RING / SummonPlacement.RING_STEP, rises = SummonPlacement.MAX_RISE / SummonPlacement.RISE_STEP + 1;
            assertEquals((1 + rings * 8) * rises, spots.size());
            double yaw = spots.getFirst().yaw();
            assertEquals(0, yaw % 15, "snapped to 15 degrees"); assertTrue(yaw >= 0 && yaw < 360);
            Vec3 forward = ShipPose.rotate(new Vec3(0, 0, -1), yaw);
            double y0 = Math.toRadians(playerYaw); Vec3 look = new Vec3(-Math.sin(y0), 0, Math.cos(y0)); // the player's view direction (Mth's sine table is too coarse here)
            assertTrue(forward.dot(look) >= Math.cos(Math.toRadians(7.5)) - 1e-9, "bow along the view: forward " + forward + " look " + look);
            ShipPose first = spots.getFirst();
            Vec3 stern = first.toWorld(new Vec3((hull.minX + hull.maxX) / 2, 0, hull.maxZ));
            Vec3 expected = feet.add(forward.scale(SummonPlacement.STERN_GAP));
            assertEquals(expected.x, stern.x, tol(feet.x, 100) * 100); assertEquals(expected.z, stern.z, tol(feet.z, 100) * 100);
            assertEquals(Math.floor(feet.y), first.y() + hull.minY, 1e-9, "keel at the feet");
            for (ShipPose s : spots) {
                assertEquals(yaw, s.yaw()); double rise = s.y() - first.y();
                assertTrue(rise >= -1e-9 && rise <= SummonPlacement.MAX_RISE + 1e-9);
                assertTrue(Math.hypot(s.x() - first.x(), s.z() - first.z()) <= SummonPlacement.MAX_RING * Math.sqrt(2) + 1e-6);
            }
            assertEquals(spots.size(), new HashSet<>(spots).size(), "no spot is tried twice");
        });
    }

    // ---------------------------------------------------------------- navigation

    @Test void navigationBearingsHeadingsAndCompassAreConsistent() {
        Fuzz.check("Navigation", 20000, (r, log) -> {
            ShipPose p = pose(r); Vec3 berth = new Vec3(p.x() + (r.nextDouble() - .5) * Fuzz.pick(r, 1e-3, 10.0, 1e4), p.y() + (r.nextDouble() - .5) * 100, p.z() + (r.nextDouble() - .5) * Fuzz.pick(r, 1e-3, 10.0, 1e4));
            double berthYaw = yaw(r); log.append(p).append(" berth ").append(berth).append(" yaw ").append(berthYaw);
            Navigation.Berth b = Navigation.berth(p, berth, berthYaw);
            assertTrue(b.distance() >= 0); assertTrue(b.bearing() >= 0 && b.bearing() <= 360, "bearing " + b.bearing());
            assertTrue(b.yawError() >= -180 && b.yawError() < 180, "yaw error " + b.yawError());
            if (b.distance() > 1e-3) {
                Vec3 towards = ShipPose.rotate(new Vec3(0, 0, -b.distance()), b.bearing());
                assertEquals(berth.x - p.x(), towards.x, 1e-6 * (1 + b.distance())); assertEquals(berth.z - p.z(), towards.z, 1e-6 * (1 + b.distance()));
            }
            String[] text = Navigation.berthText(b); int bearing = Integer.parseInt(text[1]);
            assertTrue(bearing >= 0 && bearing < 360, "HUD bearing " + bearing);
            double y = yaw(r), snapped = Navigation.snapHeading(y);
            assertTrue(snapped >= 0 && snapped < 360 && snapped % 15 == 0, "snap " + y + " -> " + snapped);
            assertTrue(Math.abs(Mth.wrapDegrees(snapped - y)) <= 7.5 + 1e-6, "snap to the nearest step");
            assertEquals(Navigation.compass(y), Navigation.compass(y + 360 * (r.nextInt(5) - 2)), "compass is 360-periodic");
            String point = Navigation.compass(y); int index = List.of("N", "NE", "E", "SE", "S", "SW", "W", "NW").indexOf(point);
            assertTrue(Math.abs(Mth.wrapDegrees(index * 45 - y)) <= 22.5 + 1e-6, "compass point " + point + " for " + y);
            List<BlockPos> targets = new ArrayList<>(); for (int i = r.nextInt(6); i > 0; i--) targets.add(BlockPos.containing(p.x() + (r.nextDouble() - .5) * 500, r.nextInt(300), p.z() + (r.nextDouble() - .5) * 500));
            Optional<BlockPos> nearest = Navigation.nearest(p, targets);
            assertEquals(targets.isEmpty(), nearest.isEmpty());
            nearest.ifPresent(n -> { for (BlockPos t : targets) assertTrue(Math.hypot(n.getX() + .5 - p.x(), n.getZ() + .5 - p.z()) <= Math.hypot(t.getX() + .5 - p.x(), t.getZ() + .5 - p.z())); });
            boolean ready = Navigation.redockReady(b, Math.abs(r.nextGaussian() * .1));
            if (ready) { assertTrue(Math.abs(b.yawError()) <= 10); assertTrue(b.distance() <= 4 && Math.abs(b.dy()) <= 4); }
        });
    }

    // ---------------------------------------------------------------- motion buffer

    @Test void motionBufferNeverStreaksOrRunsAway() {
        Fuzz.check("ShipMotion", 5000, (r, log) -> {
            ShipMotion motion = new ShipMotion(); long tick = r.nextInt(1_000_000); ShipPose at = pose(r);
            Map<Long, ShipPose> sent = new HashMap<>(); long newest = Long.MIN_VALUE;
            for (int step = 0, n = 1 + r.nextInt(60); step < n; step++) {
                int op = r.nextInt(10);
                if (op < 6) {
                    tick += switch (r.nextInt(8)) { case 0 -> 0; case 1 -> -r.nextInt(5); case 2 -> 2 + r.nextInt(30); default -> 1; };
                    boolean jump = r.nextInt(15) == 0;
                    at = jump ? new ShipPose(at.x() + 40 + r.nextInt(500), at.y(), at.z(), at.yaw()) : new ShipPose(at.x() + r.nextGaussian() * .3, at.y() + r.nextGaussian() * .1, at.z() + r.nextGaussian() * .3, at.yaw() + r.nextGaussian() * 2);
                    Vec3 v = new Vec3(r.nextGaussian() * .3, r.nextGaussian() * .1, r.nextGaussian() * .3);
                    log.append("accept ").append(tick).append(jump ? " jump" : "").append("; ");
                    boolean taken = newest == Long.MIN_VALUE || tick > newest;
                    motion.accept(tick, at, v, r.nextGaussian());
                    if (taken) {
                        if (jump) { assertEquals(at, motion.advance(), "a jump snaps to its target instead of streaking"); }
                        sent.put(tick, at); newest = tick;
                    }
                    assertEquals(newest, motion.latestTick(-1));
                } else if (newest != Long.MIN_VALUE) {
                    double before = motion.time(); ShipPose p = motion.advance();
                    log.append("advance ").append(motion.time()).append("; ");
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()) && Double.isFinite(p.yaw()), "finite pose");
                    assertTrue(motion.time() <= newest + 2, "never extrapolates past two ticks");
                    assertTrue(motion.time() >= before || before > newest + 2, "time only runs forward");
                    ShipPose exact = sent.get((long) motion.time());
                    if (exact != null && motion.time() == Math.rint(motion.time()) && motion.contains(motion.time())) {
                        assertEquals(exact.x(), p.x(), tol(exact.x())); assertEquals(exact.y(), p.y(), tol(exact.y())); assertEquals(exact.z(), p.z(), tol(exact.z()));
                        assertEquals(0, Mth.wrapDegrees(exact.yaw() - p.yaw()), 1e-6, "a sample's own tick shows it");
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- flight tuning

    @Test void flightTuningStaysInItsRanges() {
        Fuzz.check("FlightDynamics", 30000, (r, log) -> {
            double a = (r.nextDouble() - .5) * Fuzz.pick(r, 2.0, 20.0, 1e6), b = (r.nextDouble() - .5) * Fuzz.pick(r, 2.0, 20.0, 1e6), step = r.nextDouble() * Fuzz.pick(r, .01, 1.0, 100.0);
            log.append(a).append(' ').append(b).append(' ').append(step);
            double next = FlightDynamics.approach(a, b, step);
            assertTrue(Math.abs(next - a) <= step + 1e-12 * Math.abs(a), "moves at most a step");
            assertTrue(Math.abs(b - next) <= Math.abs(b - a) + 1e-9, "never overshoots");
            double load = FlightDynamics.engineLoad(a, b); assertTrue(load >= 0 && load <= 1);
            double eff = FlightDynamics.effectiveLoad(a, b, r.nextGaussian(), r.nextBoolean()); assertTrue(eff >= load && eff <= 1);
            double mass = Math.abs(a) * 100, length = Math.abs(b);
            double turn = FlightDynamics.turnRate(mass, length); assertTrue(turn >= .45 && turn <= 1.8, "turn rate " + turn);
            assertTrue(FlightDynamics.turnRate(mass * 2 + 1, length) <= turn, "heavier hulls turn no faster");
            assertTrue(FlightDynamics.inertia(mass) >= .5 && Double.isFinite(FlightDynamics.inertia(mass)));
            double climb = FlightDynamics.holdClimb(a, b, r.nextGaussian()); assertTrue(climb >= -1 && climb <= 1);
            double y = (r.nextDouble() - .5) * 1e4, target = FlightDynamics.moveHoldTarget(a, y, Math.clamp(r.nextGaussian(), -1, 1));
            assertTrue(target >= y - 8 && target <= y + 8, "hold target within reach");
            double steer = FlightDynamics.headingSteer(a, b, r.nextGaussian()); assertTrue(steer >= -1 && steer <= 1);
            double cruise = FlightDynamics.cruiseThrottle(r.nextGaussian(), r.nextGaussian()); assertTrue(cruise >= -1 && cruise <= 1);
        });
    }

    // ---------------------------------------------------------------- repair

    @Test void repairListsTheNearestMissingCellsAndSkipsUnaffordableKinds() {
        Fuzz.check("ShipRepair", 5000, (r, log) -> {
            Map<BlockPos, Integer> blueprint = new LinkedHashMap<>();
            for (int i = r.nextInt(80); i > 0; i--) blueprint.put(new BlockPos(r.nextInt(20), r.nextInt(10), r.nextInt(20)), r.nextInt(5));
            Set<BlockPos> empty = new HashSet<>(), blocked = new HashSet<>();
            for (BlockPos p : blueprint.keySet()) { if (r.nextInt(3) != 0) empty.add(p); if (r.nextInt(6) == 0) blocked.add(p); }
            Vec3 near = new Vec3(r.nextDouble() * 30 - 5, r.nextDouble() * 15, r.nextDouble() * 30 - 5);
            int limit = Fuzz.pick(r, -1, 0, 1, 16, Integer.MAX_VALUE, r.nextInt(50));
            List<BlockPos> missing = ShipRepair.missing(blueprint, empty::contains, blocked::contains, near, limit);
            long eligible = blueprint.keySet().stream().filter(p -> empty.contains(p) && !blocked.contains(p)).count();
            assertEquals(Math.min(eligible, Math.max(0, limit)), missing.size());
            assertEquals(missing.size(), new HashSet<>(missing).size());
            for (int i = 0; i < missing.size(); i++) {
                BlockPos p = missing.get(i); assertTrue(blueprint.containsKey(p) && empty.contains(p) && !blocked.contains(p));
                if (i > 0) assertTrue(Vec3.atCenterOf(missing.get(i - 1)).distanceToSqr(near) <= Vec3.atCenterOf(p).distanceToSqr(near), "nearest first");
            }
            if (limit < eligible && !missing.isEmpty()) {
                double cut = Vec3.atCenterOf(missing.getLast()).distanceToSqr(near);
                for (BlockPos p : blueprint.keySet()) if (empty.contains(p) && !blocked.contains(p)) assertTrue(missing.contains(p) || Vec3.atCenterOf(p).distanceToSqr(near) >= cut);
            }
            Set<Integer> affordable = new HashSet<>(); for (int k = 0; k < 5; k++) if (r.nextBoolean()) affordable.add(k);
            List<BlockPos> tried = new ArrayList<>(); Set<Integer> failedKinds = new HashSet<>();
            int perUse = Fuzz.pick(r, 0, 1, ShipRepair.PER_USE, 1000);
            int restored = ShipRepair.restore(missing, blueprint::get, (p, k) -> { assertFalse(failedKinds.contains(k), "a failed kind is not tried again"); tried.add(p); if (affordable.contains(k)) return true; failedKinds.add(k); return false; }, perUse);
            assertTrue(restored <= perUse);
            long expect = 0; Set<Integer> fails = new HashSet<>(); for (BlockPos p : missing) { if (expect >= perUse) break; int k = blueprint.get(p); if (fails.contains(k)) continue; if (affordable.contains(k)) expect++; else fails.add(k); }
            assertEquals(expect, restored);
            for (int i = 1; i < tried.size(); i++) assertTrue(missing.indexOf(tried.get(i - 1)) < missing.indexOf(tried.get(i)), "walks nearest first");
        });
    }
}
