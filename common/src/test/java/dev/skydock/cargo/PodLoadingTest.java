package dev.skydock.cargo;

import dev.skydock.data.DockTier;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipRepair;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PodLoadingTest {
    private static final BlockPos DECK = new BlockPos(5, 5, 5), ABOVE = DECK.above();
    private static Map<BlockPos, BlockState> hull() { Map<BlockPos, BlockState> m = new HashMap<>(); m.put(DECK, Blocks.OAK_PLANKS.defaultBlockState()); return m; }

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void allowedWhenAttachedInsideAndUnderCap() { assertEquals("", PodLoading.placeable(hull(), DockTier.SCOUT, ABOVE, 1, 10)); }
    @Test void refusedOutsideTheInterior() {
        Map<BlockPos, BlockState> m = hull(); m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        assertEquals("message.skydock.pod_outside", PodLoading.placeable(m, DockTier.SCOUT, new BlockPos(-1, 0, 0), 1, 10));
        assertEquals("message.skydock.pod_outside", PodLoading.placeable(m, DockTier.SCOUT, new BlockPos(0, DockTier.SCOUT.height, 0), 1, 10));
    }
    @Test void refusedWhenFloating() { assertEquals("message.skydock.pod_unattached", PodLoading.placeable(hull(), DockTier.SCOUT, new BlockPos(9, 9, 9), 1, 10)); }
    @Test void refusedWhenOccupied() { assertEquals("message.skydock.pod_occupied", PodLoading.placeable(hull(), DockTier.SCOUT, DECK, 1, 10)); }
    @Test void refusedAtTheCap() { assertEquals("message.skydock.pod_cap", PodLoading.placeable(hull(), DockTier.SCOUT, ABOVE, 10, 10)); }

    /** The pod block is not registered in unit tests, so a barrel stands in for it. */
    private static final BlockState POD = Blocks.BARREL.defaultBlockState();
    private static boolean pod(BlockState s) { return s.is(Blocks.BARREL); }

    @Test void removingAPodDropsItFromTheBlueprintSoTheHammerSkipsIt() {
        Ship ship = new Ship();
        ship.blocks.put(DECK, Blocks.OAK_PLANKS.defaultBlockState()); ship.blueprint.put(DECK, Blocks.OAK_PLANKS.defaultBlockState());
        PodLoading.record(ship, ABOVE, POD);
        assertEquals(POD, ship.blueprint.get(ABOVE), "a pod in an empty cell joins the blueprint");
        PodLoading.forget(ship, ABOVE, PodLoadingTest::pod);
        assertFalse(ship.blocks.containsKey(ABOVE));
        assertTrue(ShipRepair.missing(ship.blueprint, p -> true, p -> false, Vec3.ZERO, 100).stream().noneMatch(ABOVE::equals));
    }
    @Test void aPodInAShatteredCellKeepsTheLostBlockForTheHammer() {
        Ship ship = new Ship(); BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();
        ship.blocks.put(DECK, plank); ship.blueprint.put(DECK, plank);
        ship.blocks.put(ABOVE, Blocks.AIR.defaultBlockState()); ship.blueprint.put(ABOVE, plank); // shattered by an impact
        PodLoading.record(ship, ABOVE, POD);
        assertEquals(POD, ship.blocks.get(ABOVE));
        assertEquals(plank, ship.blueprint.get(ABOVE), "the pod does not overwrite the lost plank");
        PodLoading.forget(ship, ABOVE, PodLoadingTest::pod);
        assertFalse(ship.blocks.containsKey(ABOVE));
        assertEquals(plank, ship.blueprint.get(ABOVE), "taking the pod leaves the plank to hammer back");
        assertTrue(ShipRepair.missing(ship.blueprint, p -> !ship.blocks.containsKey(p), p -> false, Vec3.ZERO, 100).contains(ABOVE));
    }
    @Test void aPodMustStayWithinLiftAndTheCellsStructuralAllowance() {
        int perCell = dev.skydock.data.MassTable.blocksPerCell();
        assertEquals("", PodLoading.liftable(900, 1000, 10, 1, 40, true));
        assertEquals("", PodLoading.liftable(960, 1000, 10, 1, 40, true), "exactly at lift still flies");
        assertEquals("message.skydock.pod_overweight", PodLoading.liftable(961, 1000, 10, 1, 40, true));
        assertEquals("message.skydock.pod_need_cells", PodLoading.liftable(100, 1000, perCell, 1, 40, true));
        assertEquals("", PodLoading.liftable(100, 1000, perCell - 1, 1, 40, true), "the last structural slot is still free");
        assertEquals("", PodLoading.liftable(100, 1000, perCell, 1, 0, false), "a decoration takes no structural slot");
    }
    @Test void onlyTheHolderTakesAnIntactPodFromAHull() {
        java.util.UUID holder = java.util.UUID.randomUUID(), crew = java.util.UUID.randomUUID();
        PodTag intact = new PodTag(4, 0, false), ejected = new PodTag(4, 0, true);
        assertTrue(PodLoading.takeable(holder, holder, intact));
        assertFalse(PodLoading.takeable(holder, crew, intact));
        assertTrue(PodLoading.takeable(holder, crew, ejected), "anyone may take an ejected pod");
        assertTrue(PodLoading.takeable(null, crew, intact), "a pod whose contract is gone is anyone's");
    }
}
