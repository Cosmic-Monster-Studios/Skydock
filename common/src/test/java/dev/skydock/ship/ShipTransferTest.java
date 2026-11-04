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
    private static ShipTransfer.Steps recording(List<String> order, boolean journalFails) { return recording(order, journalFails, "", new Ship.Step[1]); }
    /** Records each step; the one named {@code fails} throws, and {@code memory[0]} follows the step the ship holds in memory. */
    private static ShipTransfer.Steps recording(List<String> order, boolean journalFails, String fails, Ship.Step[] memory) {
        return new ShipTransfer.Steps() {
            void step(String name) { order.add(name); if (name.equals(fails)) throw new IllegalStateException(name); }
            public void begin(Ship.Step step) { memory[0] = step; order.add("begin " + step.name().toLowerCase()); }
            public void paste() { step("paste"); }
            public void clear() { step("clear"); }
            public void save(boolean pasted) { step(pasted ? "save pasted" : "save cleared"); }
            public void mark(Ship.Step step) { memory[0] = step; order.add("mark " + step.name().toLowerCase()); }
            public void journal() { order.add("journal"); if (journalFails) throw new java.io.UncheckedIOException(new java.io.IOException("disk full")); }
            public void apply() { order.add("apply"); }
        };
    }
    private static List<String> ran(boolean launching, Ship.Step done) { List<String> order = new ArrayList<>(); ShipTransfer.run(recording(order, false), launching, done); return order; }
    private static final List<String> PASTE = List.of("begin pasting", "paste", "save pasted", "mark pasted"), CLEAR = List.of("begin clearing", "clear", "save cleared", "mark cleared");
    @SafeVarargs private static List<String> then(List<String>... parts) { List<String> all = new ArrayList<>(); for (List<String> part : parts) all.addAll(part); all.add("journal"); all.add("apply"); return all; }
    /** A crash between any two steps must replay safely, so each marker follows its side's save, the journal advances only after both, and memory only after the journal. */
    @Test void aPhaseAdvancesTheJournalOnlyAfterBothWorldsAreSaved() {
        assertEquals(then(PASTE, CLEAR), ran(true, Ship.Step.STARTED));
        assertEquals(then(PASTE, CLEAR), ran(false, Ship.Step.STARTED));
    }
    /** A replay skips what its marker says is done: a launch re-pastes its yard from the live berth until its clear begins; a redock re-pastes its berth only until that paste is saved. */
    @Test void aReplayResumesFromItsMarker() {
        assertEquals(then(PASTE, CLEAR), ran(true, Ship.Step.PASTING));
        assertEquals(then(PASTE, CLEAR), ran(true, Ship.Step.PASTED));
        assertEquals(then(CLEAR), ran(true, Ship.Step.CLEARING), "a berth clear that was cut short is run again from the start");
        assertEquals(then(), ran(true, Ship.Step.CLEARED), "a cleared berth is not cleared again, so nothing put there since is wiped");
        assertEquals(then(PASTE, CLEAR), ran(false, Ship.Step.PASTING), "a berth paste that was cut short is run again from the start");
        assertEquals(then(CLEAR), ran(false, Ship.Step.PASTED));
        assertEquals(then(CLEAR), ran(false, Ship.Step.CLEARING));
        assertEquals(then(), ran(false, Ship.Step.CLEARED));
    }
    /** A step that throws leaves memory in the step it began, which the retry's journal write records: a berth clear cut short reads the yard and clears again. */
    @Test void aStepThatThrowsLeavesTheStepItBegan() {
        for (boolean launching : new boolean[] {true, false})
            for (String fails : List.of("paste", "save pasted", "clear", "save cleared")) {
                Ship.Step[] memory = {Ship.Step.STARTED};
                assertThrows(IllegalStateException.class, () -> ShipTransfer.run(recording(new ArrayList<>(), false, fails, memory), launching, Ship.Step.STARTED));
                assertEquals(fails.contains("paste") ? Ship.Step.PASTING : Ship.Step.CLEARING, memory[0], launching + " " + fails);
            }
        assertEquals(ShipTransfer.Source.YARD, ShipTransfer.source(true, Ship.Step.CLEARING), "a dispenser may have filled a cleared berth cell");
        assertEquals(ShipTransfer.Source.EITHER, ShipTransfer.source(false, Ship.Step.PASTING), "outside hoppers may have drained a pasted berth cell");
    }
    /** A journal write that throws leaves the ship in its old phase in memory, matching the disk, so the pause and retry path replays it. */
    @Test void aFailedJournalWriteLeavesMemoryInTheOldPhase() {
        List<String> order = new ArrayList<>();
        assertThrows(java.io.UncheckedIOException.class, () -> ShipTransfer.run(recording(order, true), true, Ship.Step.STARTED));
        assertEquals(then(PASTE, CLEAR).subList(0, 9), order);
    }
    /** A retry reads the berth whenever it holds the hull (a launch until its clear begins, a redock once its paste is saved), else the shipyard; never a cleared side. */
    @Test void aRetryReadsTheCopyThatHoldsTheHull() {
        var berth = ShipTransfer.Source.BERTH; var yard = ShipTransfer.Source.YARD;
        assertEquals(berth, ShipTransfer.source(true, Ship.Step.STARTED));
        assertEquals(berth, ShipTransfer.source(true, Ship.Step.PASTING), "the yard paste is isolated; the berth stayed live");
        assertEquals(berth, ShipTransfer.source(true, Ship.Step.PASTED));
        assertEquals(yard, ShipTransfer.source(true, Ship.Step.CLEARING), "the berth clear has begun");
        assertEquals(yard, ShipTransfer.source(true, Ship.Step.CLEARED), "the berth is cleared");
        assertEquals(yard, ShipTransfer.source(false, Ship.Step.STARTED));
        assertEquals(ShipTransfer.Source.EITHER, ShipTransfer.source(false, Ship.Step.PASTING), "the berth holds part of the hull, the yard the rest");
        assertEquals(berth, ShipTransfer.source(false, Ship.Step.PASTED), "the yard is untouched but isolated; the berth copy is the live one");
        assertEquals(berth, ShipTransfer.source(false, Ship.Step.CLEARING));
        assertEquals(berth, ShipTransfer.source(false, Ship.Step.CLEARED), "the yard is cleared");
    }
    /** A redock cut short in its paste reads each cell from the berth where the hull block stands there, drained or not, else from the yard. */
    @Test void aCutShortPasteReadsEachCellWhereItStands() {
        BlockPos pasted = new BlockPos(0, 0, 0), unpasted = new BlockPos(1, 0, 0), plain = new BlockPos(2, 0, 0);
        var berth = java.util.Map.of(pasted, items(3)); var yard = java.util.Map.of(pasted, items(64), unpasted, items(64));
        java.util.function.Predicate<BlockPos> stands = p -> p.equals(pasted) || p.equals(plain);
        var live = ShipTransfer.live(ShipTransfer.Source.EITHER, berth::get, stands, yard::get);
        assertEquals(items(3), live.apply(pasted), "the berth copy is exposed, so what left it stays gone");
        assertEquals(items(64), live.apply(unpasted));
        assertNull(live.apply(plain), "a block with no entity reads none, from the berth");
        assertEquals(items(3), ShipTransfer.live(ShipTransfer.Source.BERTH, berth::get, stands, yard::get).apply(pasted));
        assertNull(ShipTransfer.live(ShipTransfer.Source.BERTH, berth::get, stands, yard::get).apply(unpasted));
        assertEquals(items(64), ShipTransfer.live(ShipTransfer.Source.YARD, berth::get, stands, yard::get).apply(pasted));
    }
    /** A record from before step markers, or with one this version does not know, reads as not yet pasted. */
    @Test void anUnknownMarkerReadsAsStarted() {
        assertEquals(Ship.Step.STARTED, Ship.step(""));
        assertEquals(Ship.Step.STARTED, Ship.step("folded"));
        assertEquals(Ship.Step.PASTED, Ship.step("pasted"));
        assertEquals(Ship.Step.CLEARED, Ship.step("cleared"));
        assertEquals(Ship.Step.PASTING, Ship.step("pasting"));
        assertEquals(Ship.Step.CLEARING, Ship.step("clearing"));
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
        assertEquals(java.util.Map.of(drained, items(0), lost, items(5)), ShipTransfer.refreshed(List.of(drained, lost, plain), journal, live::get, false));
    }
    /** A shulker box saves only its id and position once emptied, so a bare live entity wins like any other, unless the record was quarantined. */
    @Test void aBareLiveEntityWinsUnlessTheRecordWasQuarantined() {
        BlockPos drained = new BlockPos(0, 0, 0), fresh = new BlockPos(1, 0, 0);
        var journal = java.util.Map.of(drained, items(64)); var live = java.util.Map.of(drained, entity(), fresh, entity());
        assertEquals(java.util.Map.of(drained, entity(), fresh, entity()), ShipTransfer.refreshed(List.of(drained, fresh), journal, live::get, false),
                "a shulker box a hopper drained during the pause moves empty");
    }
    /** In a released quarantined record, a live block entity that saved only its id and position is a default standing in for data that failed to load; it never replaces a journal copy with content. */
    @Test void aBareLiveEntityKeepsTheJournalCopyOfAQuarantinedRecord() {
        BlockPos healed = new BlockPos(0, 0, 0), fresh = new BlockPos(1, 0, 0), both = new BlockPos(2, 0, 0), emptied = new BlockPos(3, 0, 0);
        var emptyList = items(0); emptyList.remove("Count"); emptyList.put("Items", new net.minecraft.nbt.ListTag());
        var journal = java.util.Map.of(healed, items(64), both, entity(), emptied, items(64));
        var live = java.util.Map.of(healed, entity(), fresh, entity(), both, entity(), emptied, emptyList);
        assertEquals(java.util.Map.of(healed, items(64), fresh, entity(), both, entity(), emptied, emptyList),
                ShipTransfer.refreshed(List.of(healed, fresh, both, emptied), journal, live::get, true), "an emptied container still saves its item list, so it moves empty");
        assertTrue(ShipTransfer.bare(entity()));
        assertTrue(ShipTransfer.bare(new net.minecraft.nbt.CompoundTag()));
        assertFalse(ShipTransfer.bare(emptyList));
    }
    /** A retry that keeps failing is held for an operator on its thirtieth failure in a row, about five minutes, rather than looping forever. */
    @Test void aTransferThatKeepsFailingIsHeld() {
        Ship ship = new Ship(); ship.phase = "redocking";
        for (int i = 1; i < ShipManager.GIVE_UP; i++) assertFalse(ShipManager.failedRetry(ship), "failure " + i);
        assertFalse(ship.transferHeld);
        assertTrue(ShipManager.failedRetry(ship));
        assertTrue(ship.transferHeld); assertFalse(ship.transferQuarantined, "a held retry is not a quarantined record");
        assertEquals(0, ship.transferFailures, "a release starts the count again");
        assertEquals(30, ShipManager.GIVE_UP); assertEquals(5 * 60 * 20, ShipManager.GIVE_UP * ShipManager.RESUME_TICKS);
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
