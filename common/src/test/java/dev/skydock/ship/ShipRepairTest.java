package dev.skydock.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ShipRepairTest {
    private static Map<BlockPos, Object> row(int n) {
        Map<BlockPos, Object> map = new LinkedHashMap<>();
        for (int x = 0; x < n; x++) map.put(new BlockPos(x, 0, 0), "planks");
        return map;
    }

    @Test void restoresNearestMissingCellsFirst() {
        List<BlockPos> picked = ShipRepair.missing(row(10), p -> true, p -> false, new Vec3(8.5, .5, .5), 3);
        assertEquals(new BlockPos(8, 0, 0), picked.get(0));
        assertEquals(Set.of(new BlockPos(7, 0, 0), new BlockPos(9, 0, 0)), Set.copyOf(picked.subList(1, 3)));
    }

    @Test void tiesBreakByYThenXThenZ() {
        Map<BlockPos, Object> map = new LinkedHashMap<>();
        for (BlockPos p : List.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(-1, 0, 0), new BlockPos(0, -1, 0), new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)))
            map.put(p, "x");
        List<BlockPos> picked = ShipRepair.missing(map, p -> true, p -> false, new Vec3(.5, .5, .5), 6);
        assertEquals(List.of(new BlockPos(0, -1, 0), new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1), new BlockPos(0, 0, 1), new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)), picked);
    }

    @Test void stopsAtTheLimit() {
        assertEquals(ShipRepair.PER_USE, ShipRepair.missing(row(40), p -> true, p -> false, Vec3.ZERO, ShipRepair.PER_USE).size());
        assertEquals(16, ShipRepair.PER_USE);
        assertTrue(ShipRepair.missing(row(5), p -> true, p -> false, Vec3.ZERO, 0).isEmpty());
    }

    @Test void presentCellsAreNotRestored() {
        List<BlockPos> picked = ShipRepair.missing(row(6), p -> p.getX() % 2 == 0, p -> false, Vec3.ZERO, 16);
        assertEquals(Set.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(4, 0, 0)), Set.copyOf(picked));
    }

    @Test void cellsOverlappingEntitiesAreSkipped() {
        List<BlockPos> picked = ShipRepair.missing(row(4), p -> true, p -> p.getX() == 1, Vec3.ZERO, 16);
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)), picked);
    }
}
