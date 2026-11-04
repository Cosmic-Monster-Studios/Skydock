package dev.skydock.data;

import dev.skydock.fuzz.Fuzz;
import dev.skydock.ship.ShipPose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** DockFrame and BerthClearance for every facing and tier: exact cell maps, no drift between the integer and continuous transforms. */
class DockGeometryFuzzTest {
    static final List<Direction> FACINGS = Direction.Plane.HORIZONTAL.stream().toList();

    static DockFrame frame(Random r) {
        int spread = Fuzz.pick(r, 100, 1_000_000, 29_999_000);
        return new DockFrame(new BlockPos(r.nextInt(2 * spread + 1) - spread, r.nextInt(400) - 64, r.nextInt(2 * spread + 1) - spread), Fuzz.pick(r, FACINGS), Fuzz.pick(r, DockTier.values()));
    }
    static BlockPos localCell(Random r, DockTier tier) { return new BlockPos(r.nextInt(tier.width), r.nextInt(tier.height), r.nextInt(tier.length)); }

    @Test void framesMapCellsOneToOneAndAgreeWithShipRotation() {
        Fuzz.check("DockFrame", 20000, (r, log) -> {
            DockFrame f = frame(r); DockTier t = f.tier(); log.append(f);
            BlockPos local = r.nextInt(5) == 0 ? new BlockPos(r.nextInt(401) - 200, r.nextInt(401) - 200, r.nextInt(401) - 200) : localCell(r, t);
            BlockPos world = f.toWorld(local);
            assertEquals(local, f.toLocal(world), "toLocal(toWorld)");
            assertEquals(world, DockFrame.toWorld(f.origin(), f.rotation(), local), "the static form agrees");
            assertEquals(Direction.NORTH == f.facing() ? Direction.NORTH : f.facing(), Direction.getNearest(Vec3.atLowerCornerOf(f.toWorld(local.north()).subtract(world))), "local -Z is the bow, the facing");
            assertEquals(new BlockPos(t.width / 2, -1, -1), f.toLocal(f.controller()), "controller centred on the front edge, one below the floor");
            if (f.facing() == Direction.NORTH) assertEquals(f.controller().offset(-t.width / 2, 1, 1), f.origin());
            // a point inside a local cell lands inside that cell's world cell
            Vec3 inCell = Vec3.atLowerCornerOf(local).add(r.nextDouble() * .999, r.nextDouble() * .999, r.nextDouble() * .999);
            assertEquals(world, BlockPos.containing(f.toWorld(inCell)), "continuous point " + inCell + " stays in its cell");
            // the berth's yaw turns local vectors as the integer rotation does
            Vec3 v = new Vec3(r.nextInt(41) - 20, r.nextInt(41) - 20, r.nextInt(41) - 20);
            Vec3 turned = ShipPose.rotate(v, f.yaw());
            BlockPos expected = BlockPos.containing(v).rotate(f.rotation());
            assertTrue(turned.distanceTo(Vec3.atLowerCornerOf(expected)) < 1e-9, "yaw " + f.yaw() + " vs rotation " + f.rotation() + ": " + turned + " vs " + expected);
            assertEquals(f.rotation(), DockFrame.inverse(DockFrame.inverse(f.rotation())));
            assertEquals(BlockPos.ZERO.offset(local), local.rotate(f.rotation()).rotate(DockFrame.inverse(f.rotation())));
            // the envelope is exactly the cells
            AABB env = f.envelope();
            assertEquals(t.width * (long) t.height * t.length, Math.round(env.getXsize() * env.getYsize() * env.getZsize()));
            if (local.getX() >= 0 && local.getX() < t.width && local.getY() >= 0 && local.getY() < t.height && local.getZ() >= 0 && local.getZ() < t.length)
                assertTrue(env.contains(Vec3.atCenterOf(world)), "an interior cell is inside the envelope");
            else assertFalse(env.contains(Vec3.atCenterOf(world)), "an exterior cell is outside the envelope");
            AABB localBox = new AABB(local.getX(), local.getY(), local.getZ(), local.getX() + 1 + r.nextInt(3), local.getY() + 1, local.getZ() + 1 + r.nextInt(3));
            AABB worldBox = f.toWorld(localBox);
            assertEquals(localBox.getXsize() * localBox.getZsize(), worldBox.getXsize() * worldBox.getZsize(), 1e-6, "boxes keep their footprint");
            assertTrue(worldBox.contains(Vec3.atCenterOf(world)));
        });
    }

    enum Kind { E, C, S, F }

    @Test void berthClearanceScansEveryCellOnceAndReportsWhatItFound() {
        Fuzz.check("BerthClearance", 600, (r, log) -> {
            DockFrame f = new DockFrame(new BlockPos(r.nextInt(2001) - 1000, r.nextInt(400) - 80, r.nextInt(2001) - 1000), Fuzz.pick(r, FACINGS), Fuzz.pick(r, DockTier.SCOUT, DockTier.BRIG, DockTier.SCOUT));
            int minY = Fuzz.pick(r, -64, 0, f.origin().getY(), f.origin().getY() + 1), maxY = Fuzz.pick(r, 320, 256, f.origin().getY() + f.tier().height, f.origin().getY() + f.tier().height - 1);
            double solidRate = Fuzz.pick(r, 0.0, 0.0, 1e-5, 1e-3), fluidRate = Fuzz.pick(r, 0.0, 0.0, 1e-5), clearRate = Fuzz.pick(r, 0.0, .01, .2);
            long salt = r.nextLong();
            Map<BlockPos, Integer> visits = new HashMap<>();
            java.util.function.Function<BlockPos, BerthClearance.Cell> world = p -> {
                visits.merge(p.immutable(), 1, Integer::sum);
                double u = new Random(p.asLong() ^ salt).nextDouble();
                return u < solidRate ? BerthClearance.Cell.SOLID : u < solidRate + fluidRate ? BerthClearance.Cell.FLUID : u < solidRate + fluidRate + clearRate ? BerthClearance.Cell.CLEARABLE : BerthClearance.Cell.EMPTY;
            };
            AABB env = f.envelope();
            List<AABB> berths = new ArrayList<>(), hulls = new ArrayList<>();
            if (r.nextInt(3) == 0) { DockFrame other = new DockFrame(f.controller().offset(r.nextInt(161) - 80, r.nextInt(41) - 20, r.nextInt(161) - 80), Fuzz.pick(r, FACINGS), Fuzz.pick(r, DockTier.values())); berths.add(other.envelope()); }
            if (r.nextInt(4) == 0) berths.add(env.move(env.getXsize(), 0, 0)); // touching face only
            if (r.nextInt(4) == 0) hulls.add(new AABB(Vec3.atCenterOf(f.toWorld(localCell(r, f.tier()))), Vec3.atCenterOf(f.toWorld(localCell(r, f.tier())))).inflate(.3));
            log.append(f).append(" minY=").append(minY).append(" maxY=").append(maxY).append(" berths=").append(berths).append(" hulls=").append(hulls);
            BerthClearance result = BerthClearance.check(f, minY, maxY, world, berths, hulls);
            boolean heightOk = f.origin().getY() >= minY && f.origin().getY() + f.tier().height <= maxY;
            AABB shrunk = env.deflate(1e-4);
            boolean dockOverlap = berths.stream().anyMatch(b -> b.intersects(shrunk)), hullOverlap = hulls.stream().anyMatch(h -> h.intersects(shrunk));
            if (!heightOk) { assertEquals("error.skydock.berth_height", result.error()); assertTrue(visits.isEmpty()); return; }
            if (dockOverlap) { assertEquals("error.skydock.berth_overlaps_dock", result.error()); return; }
            if (hullOverlap) { assertEquals("error.skydock.berth_overlaps_ship", result.error()); return; }
            assertEquals(f.tier().width * f.tier().height * f.tier().length, visits.size(), "every berth cell is looked at");
            visits.forEach((p, n) -> { assertEquals(1, n, "once each"); assertTrue(env.contains(Vec3.atCenterOf(p)), "only berth cells: " + p); });
            int solid = 0, fluid = 0; List<BlockPos> clearable = new ArrayList<>();
            for (BlockPos p : visits.keySet()) switch (world.apply(p)) { case SOLID -> solid++; case FLUID -> fluid++; case CLEARABLE -> clearable.add(p); default -> {} }
            if (solid + fluid > 0) {
                assertEquals(fluid > 0 ? "error.skydock.berth_fluid" : "error.skydock.berth_not_clear", result.error());
                assertEquals(solid + fluid, result.args().get(0)); assertTrue(result.clearable().isEmpty());
            } else {
                assertTrue(result.clear()); assertEquals(new HashSet<>(clearable), new HashSet<>(result.clearable())); assertEquals(clearable.size(), result.clearable().size());
            }
        });
    }
}
