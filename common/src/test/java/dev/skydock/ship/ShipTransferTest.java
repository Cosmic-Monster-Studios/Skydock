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
    private static ShipTransfer.Steps recording(List<String> order, boolean journalFails) {
        return new ShipTransfer.Steps() {
            public void paste() { order.add("paste"); }
            public void clear() { order.add("clear"); }
            public void save(boolean pasted) { order.add(pasted ? "save pasted" : "save cleared"); }
            public void mark(Ship.Step step) { order.add("mark " + step.name().toLowerCase()); }
            public void journal() { order.add("journal"); if (journalFails) throw new java.io.UncheckedIOException(new java.io.IOException("disk full")); }
            public void apply() { order.add("apply"); }
        };
    }
    private static List<String> ran(boolean launching, Ship.Step done) { List<String> order = new ArrayList<>(); ShipTransfer.run(recording(order, false), launching, done); return order; }
    /** A crash between any two steps must replay safely, so each marker follows its side's save, the journal advances only after both, and memory only after the journal. */
    @Test void aPhaseAdvancesTheJournalOnlyAfterBothWorldsAreSaved() {
        var full = List.of("paste", "save pasted", "mark pasted", "clear", "save cleared", "mark cleared", "journal", "apply");
        assertEquals(full, ran(true, Ship.Step.STARTED));
        assertEquals(full, ran(false, Ship.Step.STARTED));
    }
    /** A replay skips what its marker says is done: a launch re-pastes its yard from the live berth until that is cleared; a redock never re-pastes its berth. */
    @Test void aReplayResumesFromItsMarker() {
        assertEquals(List.of("paste", "save pasted", "mark pasted", "clear", "save cleared", "mark cleared", "journal", "apply"), ran(true, Ship.Step.PASTED));
        assertEquals(List.of("clear", "save cleared", "mark cleared", "journal", "apply"), ran(false, Ship.Step.PASTED));
        assertEquals(List.of("journal", "apply"), ran(true, Ship.Step.CLEARED), "a cleared berth is not cleared again, so nothing put there since is wiped");
        assertEquals(List.of("journal", "apply"), ran(false, Ship.Step.CLEARED));
    }
    /** A journal write that throws leaves the ship in its old phase in memory, matching the disk, so the pause and retry path replays it. */
    @Test void aFailedJournalWriteLeavesMemoryInTheOldPhase() {
        List<String> order = new ArrayList<>();
        assertThrows(java.io.UncheckedIOException.class, () -> ShipTransfer.run(recording(order, true), true, Ship.Step.STARTED));
        assertEquals(List.of("paste", "save pasted", "mark pasted", "clear", "save cleared", "mark cleared", "journal"), order);
    }
    /** A retry reads the berth whenever it holds the hull (a launch before its clear, a redock after its paste), else the shipyard; never a cleared side. */
    @Test void aRetryReadsTheCopyThatHoldsTheHull() {
        assertTrue(ShipTransfer.readsBerth(true, Ship.Step.STARTED));
        assertTrue(ShipTransfer.readsBerth(true, Ship.Step.PASTED), "the yard paste is isolated; the berth stayed live");
        assertFalse(ShipTransfer.readsBerth(true, Ship.Step.CLEARED), "the berth is cleared");
        assertFalse(ShipTransfer.readsBerth(false, Ship.Step.STARTED));
        assertTrue(ShipTransfer.readsBerth(false, Ship.Step.PASTED), "the yard is untouched but isolated; the berth copy is the live one");
        assertTrue(ShipTransfer.readsBerth(false, Ship.Step.CLEARED), "the yard is cleared");
    }
    /** A record from before step markers, or with one this version does not know, reads as not yet pasted. */
    @Test void anUnknownMarkerReadsAsStarted() {
        assertEquals(Ship.Step.STARTED, Ship.step(""));
        assertEquals(Ship.Step.STARTED, Ship.step("folded"));
        assertEquals(Ship.Step.PASTED, Ship.step("pasted"));
        assertEquals(Ship.Step.CLEARED, Ship.step("cleared"));
    }
    /** The envelope box holds exactly the cells inBerth accepts, for every dock facing. */
    @Test void theBerthBoxMatchesTheEnvelope() {
        BlockPos origin = new BlockPos(8, 64, 8); var tier = dev.skydock.data.DockTier.SCOUT;
        for (var rotation : net.minecraft.world.level.block.Rotation.values()) {
            var box = ShipTransfer.berthBox(origin, rotation, tier);
            assertEquals(tier.width * tier.height * tier.length, Math.round(box.getXsize() * box.getYsize() * box.getZsize()));
            for (BlockPos p : List.of(origin, origin.offset(-40, 0, -40), origin.offset(40, 0, 40), origin.offset(-31, 19, 31), origin.offset(31, 19, -31), origin.offset(31, 19, 31), origin.offset(-31, 19, -31)))
                assertEquals(ShipTransfer.inBerth(origin, rotation, tier, p), box.contains(net.minecraft.world.phys.Vec3.atCenterOf(p)), rotation + " " + p);
        }
    }
    private static net.minecraft.nbt.CompoundTag items(int count) { var tag = entity(); tag.putInt("Count", count); return tag; }
    private static net.minecraft.nbt.CompoundTag entity() { var tag = new net.minecraft.nbt.CompoundTag(); tag.putString("id", "minecraft:chest"); tag.putInt("x", 1); tag.putInt("y", 2); tag.putInt("z", 3); return tag; }
    /** A retry copies a cell as it stands now in the copy it reads, and keeps the journal copy only where the hull block is gone: a chest a hopper drained during the pause moves drained. */
    @Test void aRetryCopiesWhatIsThereNow() {
        BlockPos drained = new BlockPos(0, 0, 0), lost = new BlockPos(2, 0, 0), plain = new BlockPos(3, 0, 0);
        var journal = java.util.Map.of(drained, items(64), lost, items(5));
        var live = java.util.Map.of(drained, items(0));
        assertEquals(java.util.Map.of(drained, items(0), lost, items(5)), ShipTransfer.refreshed(List.of(drained, lost, plain), journal, live::get));
    }
    /** A live block entity that saved only its id and position is a default standing in for data that failed to load; it never replaces a journal copy with content. */
    @Test void aBareLiveEntityKeepsTheJournalCopy() {
        BlockPos healed = new BlockPos(0, 0, 0), fresh = new BlockPos(1, 0, 0), both = new BlockPos(2, 0, 0), emptied = new BlockPos(3, 0, 0);
        var emptyList = items(0); emptyList.remove("Count"); emptyList.put("Items", new net.minecraft.nbt.ListTag());
        var journal = java.util.Map.of(healed, items(64), both, entity(), emptied, items(64));
        var live = java.util.Map.of(healed, entity(), fresh, entity(), both, entity(), emptied, emptyList);
        assertEquals(java.util.Map.of(healed, items(64), fresh, entity(), both, entity(), emptied, emptyList),
                ShipTransfer.refreshed(List.of(healed, fresh, both, emptied), journal, live::get), "an emptied container still saves its item list, so it moves empty");
        assertTrue(ShipTransfer.bare(entity()));
        assertTrue(ShipTransfer.bare(new net.minecraft.nbt.CompoundTag()));
        assertFalse(ShipTransfer.bare(emptyList));
    }
    /** Once a redock's journal has dropped the ship, a ticket release that throws is logged, not passed on as a pause. */
    @Test void aFailedTicketReleaseStillFinishesTheRedock() {
        Ship ship = new Ship(); java.util.Map<java.util.UUID, Ship> ships = new java.util.HashMap<>(java.util.Map.of(ship.id, ship));
        assertDoesNotThrow(() -> ShipTransfer.finishRedock(ships, ship, () -> { throw new IllegalStateException("ticket"); }));
        assertTrue(ships.isEmpty());
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
