package dev.skydock.cargo;

import dev.skydock.data.DockTier;
import dev.skydock.data.MassTable;
import dev.skydock.fuzz.Fuzz;
import dev.skydock.ship.Ship;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pod placement rules, hull bookkeeping and the drifting pod's flight. */
class PodPhysicsFuzzTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void placementRefusesInRuleOrder() {
        Fuzz.check("PodLoading.placeable", 20000, (r, log) -> {
            DockTier tier = Fuzz.pick(r, DockTier.values()); Map<BlockPos, BlockState> blocks = new HashMap<>();
            for (int i = r.nextInt(40); i > 0; i--) blocks.put(new BlockPos(r.nextInt(tier.width + 4) - 2, r.nextInt(tier.height + 4) - 2, r.nextInt(tier.length + 4) - 2), r.nextInt(5) == 0 ? Blocks.AIR.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
            BlockPos cell = !blocks.isEmpty() && r.nextBoolean() ? Fuzz.pick(r, new ArrayList<>(blocks.keySet())).relative(Fuzz.pick(r, Direction.values())) : new BlockPos(r.nextInt(tier.width + 4) - 2, r.nextInt(tier.height + 4) - 2, r.nextInt(tier.length + 4) - 2);
            int filled = r.nextInt(30), cap = r.nextInt(30);
            String got = PodLoading.placeable(blocks, tier, cell, filled, cap);
            boolean inside = cell.getX() >= 0 && cell.getY() >= 0 && cell.getZ() >= 0 && cell.getX() < tier.width && cell.getY() < tier.height && cell.getZ() < tier.length;
            BlockState there = blocks.get(cell); boolean occupied = there != null && !there.isAir();
            boolean attached = Arrays.stream(Direction.values()).anyMatch(d -> { BlockState n = blocks.get(cell.relative(d)); return n != null && !n.isAir(); });
            String expected = !inside ? "message.skydock.pod_outside" : occupied ? "message.skydock.pod_occupied" : !attached ? "message.skydock.pod_unattached" : filled >= cap ? "message.skydock.pod_cap" : "";
            log.append(tier).append(' ').append(cell).append(' ').append(filled).append('/').append(cap);
            assertEquals(expected, got);
        });
    }

    @Test void liftChecksMatchTheLaunchRules() {
        Fuzz.check("PodLoading.liftable", 20000, (r, log) -> {
            double mass = r.nextDouble() * 1e5, lift = r.nextDouble() * 1e5, pod = Fuzz.pick(r, 0.0, 10.0, r.nextDouble() * 100);
            int structural = r.nextInt(100_000), cells = Fuzz.pick(r, 0, r.nextInt(1000), Integer.MAX_VALUE); boolean structuralPod = r.nextBoolean();
            String got = PodLoading.liftable(mass, lift, structural, cells, pod, structuralPod);
            String expected = mass + pod > lift ? "message.skydock.pod_overweight" : structuralPod && structural + 1L > (long) cells * MassTable.blocksPerCell() ? "message.skydock.pod_need_cells" : "";
            assertEquals(expected, got);
        });
    }

    @Test void recordingAndForgettingPodsKeepsTheBlueprintsHullBlocks() {
        BlockState pod = Blocks.BARREL.defaultBlockState(), plank = Blocks.OAK_PLANKS.defaultBlockState();
        Fuzz.check("PodLoading.record/forget", 5000, (r, log) -> {
            Ship ship = new Ship(); Map<BlockPos, BlockState> plan = new HashMap<>();
            for (int i = r.nextInt(20); i > 0; i--) { BlockPos p = new BlockPos(r.nextInt(6), r.nextInt(3), r.nextInt(6)); ship.blocks.put(p, plank); ship.blueprint.put(p, plank); plan.put(p, plank); }
            for (int i = r.nextInt(10); i > 0; i--) ship.blocks.remove(new BlockPos(r.nextInt(6), r.nextInt(3), r.nextInt(6)));
            for (int i = r.nextInt(20); i > 0; i--) {
                BlockPos p = new BlockPos(r.nextInt(6), r.nextInt(3), r.nextInt(6));
                if (r.nextBoolean()) { if (!ship.blocks.containsKey(p)) { PodLoading.record(ship, p, pod); plan.putIfAbsent(p, pod); } }
                else if (ship.blocks.containsKey(p) && ship.blocks.get(p) == pod) { PodLoading.forget(ship, p, s -> s.is(Blocks.BARREL)); if (plan.get(p) == pod) plan.remove(p); }
                for (var e : ship.blueprint.entrySet()) if (e.getValue() == plank) assertTrue(plan.containsKey(e.getKey()), "hull blocks of the blueprint are never lost");
                assertEquals(plan, ship.blueprint, "the blueprint gains a pod only in an empty cell and forgets only pods");
            }
        });
    }

    @Test void onlyTheHolderTakesAPodUnlessItWasEjectedOrItsContractIsGone() {
        Fuzz.check("PodLoading.takeable", 5000, (r, log) -> {
            UUID holder = r.nextInt(4) == 0 ? null : new UUID(r.nextInt(3), 0), player = new UUID(r.nextInt(3), 0); PodTag tag = new PodTag(r.nextLong(), r.nextInt(4), r.nextBoolean());
            assertEquals(holder == null || holder.equals(player) || tag.ejected(), PodLoading.takeable(holder, player, tag));
        });
    }

    @Test void driftingPodsSettleToTheWindAndASteadySink() {
        Fuzz.check("PodDrift", 5000, (r, log) -> {
            Vec3 v = new Vec3((r.nextDouble() - .5) * Fuzz.pick(r, 1.0, 10.0), (r.nextDouble() - .5) * Fuzz.pick(r, 1.0, 10.0), (r.nextDouble() - .5) * Fuzz.pick(r, 1.0, 10.0));
            CargoSavedData.Wind wind = new CargoSavedData.Wind((r.nextDouble() - .5) * 100, r.nextDouble() * 2, r.nextLong());
            Vec3 target = new Vec3(Math.cos(wind.angle()) * wind.speed() / 20, -.05, Math.sin(wind.angle()) * wind.speed() / 20);
            log.append(v).append(' ').append(wind);
            int age = r.nextInt(PodDrift.TUMBLE + 5);
            for (int tick = 0; tick < 200; tick++, age++) {
                Vec3 next = PodDrift.step(v, age, wind);
                assertTrue(Double.isFinite(next.x) && Double.isFinite(next.y) && Double.isFinite(next.z));
                if (age < PodDrift.TUMBLE) { assertTrue(next.y < v.y || next.y <= 0, "tumbling pods fall"); assertTrue(next.horizontalDistance() <= v.horizontalDistance() + 1e-12, "drag slows them"); }
                else assertTrue(next.distanceTo(target) <= v.distanceTo(target) + 1e-12, "the balloon only ever eases toward the wind");
                v = next;
            }
            assertTrue(v.distanceTo(target) < 1e-3, "settled: " + v + " vs " + target);
        });
    }
}
