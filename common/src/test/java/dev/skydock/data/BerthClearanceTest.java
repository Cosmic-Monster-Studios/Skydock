package dev.skydock.data;

import dev.skydock.data.BerthClearance.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BerthClearanceTest {
    private static final BlockPos CONTROLLER = new BlockPos(100, 64, 200);
    private static DockFrame frame(Direction facing) { return new DockFrame(CONTROLLER, facing, DockTier.SCOUT); }
    private static BerthClearance check(DockFrame frame, Map<BlockPos, Cell> cells) {
        return BerthClearance.check(frame, -64, 320, p -> cells.getOrDefault(p, Cell.EMPTY), List.of(), List.of());
    }

    @Test void emptyBerthIsClear() {
        var result = check(frame(Direction.NORTH), Map.of());
        assertTrue(result.clear());
        assertTrue(result.clearable().isEmpty());
    }
    @Test void vegetationIsClearAndListedForClearing() {
        var f = frame(Direction.NORTH);
        BlockPos grass = f.toWorld(new BlockPos(3, 0, 4)), flower = f.toWorld(new BlockPos(10, 0, 20));
        var result = check(f, Map.of(grass, Cell.CLEARABLE, flower, Cell.CLEARABLE));
        assertTrue(result.clear());
        assertEquals(Set.of(grass, flower), Set.copyOf(result.clearable()));
    }
    @Test void oneSolidBlockRefusesWithCountAndPosition() {
        var f = frame(Direction.NORTH);
        BlockPos stone = f.toWorld(new BlockPos(5, 2, 7));
        var result = check(f, Map.of(stone, Cell.SOLID, f.toWorld(new BlockPos(1, 0, 1)), Cell.CLEARABLE));
        assertEquals("error.skydock.berth_not_clear", result.error());
        assertEquals(List.of(1, stone.toShortString()), result.args());
        assertTrue(result.clearable().isEmpty());
    }
    @Test void fluidRefusesWithItsOwnMessage() {
        var f = frame(Direction.NORTH);
        var result = check(f, Map.of(f.toWorld(new BlockPos(0, 0, 0)), Cell.FLUID, f.toWorld(new BlockPos(1, 0, 0)), Cell.SOLID));
        assertEquals("error.skydock.berth_fluid", result.error());
        assertEquals(2, result.args().getFirst());
    }
    @Test void blocksOutsideTheBerthDoNotCount() {
        var f = frame(Direction.NORTH);
        var result = check(f, Map.of(CONTROLLER, Cell.SOLID, f.toWorld(new BlockPos(-1, 0, 0)), Cell.SOLID, f.toWorld(new BlockPos(0, 20, 0)), Cell.SOLID));
        assertTrue(result.clear());
    }
    @Test void everyFacingChecksItsOwnRotatedBerth() {
        AABB north = frame(Direction.NORTH).envelope();
        for (Direction facing : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            var f = frame(facing);
            BlockPos only = null;
            for (BlockPos local : BlockPos.betweenClosed(0, 0, 0, 31, 0, 31)) {
                BlockPos at = f.toWorld(local);
                if (!north.contains(at.getCenter())) { only = at; break; }
            }
            assertNotNull(only, facing.toString());
            assertFalse(check(f, Map.of(only, Cell.SOLID)).clear(), facing.toString());
            assertTrue(check(frame(Direction.NORTH), Map.of(only, Cell.SOLID)).clear(), facing.toString());
        }
    }
    @Test void overlappingAnotherBerthIsRefused() {
        var f = frame(Direction.NORTH);
        AABB other = frame(Direction.NORTH).envelope().move(10, 0, 10);
        assertEquals("error.skydock.berth_overlaps_dock", BerthClearance.check(f, -64, 320, p -> Cell.EMPTY, List.of(other), List.of()).error());
        AABB touching = f.envelope().move(32, 0, 0);
        assertTrue(BerthClearance.check(f, -64, 320, p -> Cell.EMPTY, List.of(touching), List.of()).clear());
    }
    @Test void overlappingAShipIsRefused() {
        var f = frame(Direction.NORTH);
        AABB hull = new AABB(90, 70, 210, 95, 75, 215);
        assertEquals("error.skydock.berth_overlaps_ship", BerthClearance.check(f, -64, 320, p -> Cell.EMPTY, List.of(), List.of(hull)).error());
    }
    @Test void berthAboveTheBuildLimitIsRefused() {
        assertEquals("error.skydock.berth_height", BerthClearance.check(frame(Direction.NORTH), -64, 80, p -> Cell.EMPTY, List.of(), List.of()).error());
        assertEquals("error.skydock.berth_height", BerthClearance.check(frame(Direction.NORTH), 70, 320, p -> Cell.EMPTY, List.of(), List.of()).error());
    }
}
