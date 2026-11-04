package dev.skydock.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BerthOccupantTest {
    private static final Set<BlockPos> CELLS = Set.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10), new BlockPos(10, 65, 10));
    private static AABB standing(double x, double y, double z) { return new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3); }

    @Test void aCreatureInAHullCellIsInTheWay() {
        assertTrue(ShipManager.inCells(CELLS, standing(10.5, 64, 10.5)));
        assertTrue(ShipManager.inCells(CELLS, standing(11.5, 63.5, 10.5)), "head in a deck cell");
        assertTrue(ShipManager.inCells(CELLS, standing(12.2, 64, 10.5)), "a shoulder overlapping the cell");
    }
    @Test void standingOnTopOfOrBesideTheCellsIsNot() {
        assertFalse(ShipManager.inCells(CELLS, standing(11.5, 65, 10.5)), "standing on the deck block");
        assertFalse(ShipManager.inCells(CELLS, standing(10.5, 66, 10.5)), "standing on the top cell");
        assertFalse(ShipManager.inCells(CELLS, standing(12.3, 64, 10.5)), "touching the side face only");
        assertFalse(ShipManager.inCells(CELLS, standing(10.5, 62.2, 10.5)), "under the hull, head touching its floor");
        assertFalse(ShipManager.inCells(Set.of(), standing(10.5, 64, 10.5)));
    }
}
