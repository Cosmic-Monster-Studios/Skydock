package dev.skydock.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ShipTransferTest {
    @Test void chunksCoverEveryCellAndNothingElse() {
        var cells = List.of(new BlockPos(0,0,0), new BlockPos(15,3,15), new BlockPos(16,0,0), new BlockPos(0,0,31));
        assertEquals(Set.of(new ChunkPos(-1,-1), new ChunkPos(0,0), new ChunkPos(0,-1), new ChunkPos(-1,1)),
                ShipTransfer.chunksOf(cells, new BlockPos(-8, 64, -8)));
    }
    @Test void rotatedBerthChunksFollowTheWorldCells() {
        var cells = List.of(new BlockPos(0,0,0), new BlockPos(20,0,0), new BlockPos(0,0,20));
        // Clockwise 90: local (x, z) lands at (-z, x) from the origin.
        assertEquals(Set.of(new ChunkPos(0,0), new ChunkPos(0,1), new ChunkPos(-1,0)),
                ShipTransfer.chunksOf(cells, new BlockPos(8, 64, 8), net.minecraft.world.level.block.Rotation.CLOCKWISE_90));
    }
    /** A crash between any two steps must replay safely, so the journal advances only after both worlds' writes are on disk. */
    @Test void aPhaseAdvancesTheJournalOnlyAfterBothWorldsAreSaved() {
        List<String> order = new ArrayList<>();
        ShipTransfer.run(new ShipTransfer.Steps() {
            public void paste() { order.add("paste"); }
            public void clear() { order.add("clear"); }
            public void save(boolean pasted) { order.add(pasted ? "save pasted" : "save cleared"); }
            public void advance() { order.add("journal"); }
        });
        assertEquals(List.of("paste", "save pasted", "clear", "save cleared", "journal"), order);
    }
    /** A paused transfer guards the hull's envelope at its berth, turned with the dock. */
    @Test void theBerthEnvelopeFollowsTheDockRotation() {
        BlockPos origin = new BlockPos(8, 64, 8); var east = net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
        var tier = dev.skydock.data.DockTier.SCOUT;
        assertTrue(ShipTransfer.inBerth(origin, east, tier, origin));
        // Clockwise 90: local (x, z) lands at (-z, x), so the envelope runs toward -X and +Z from the origin.
        assertTrue(ShipTransfer.inBerth(origin, east, tier, origin.offset(-31, 19, 31)));
        assertFalse(ShipTransfer.inBerth(origin, east, tier, origin.offset(1, 0, 0)));
        assertFalse(ShipTransfer.inBerth(origin, east, tier, origin.offset(0, 20, 0)));
        assertFalse(ShipTransfer.inBerth(origin, east, tier, origin.offset(0, -1, 0)));
        assertFalse(ShipTransfer.inBerth(origin, east, tier, origin.offset(-32, 0, 0)));
    }
}
