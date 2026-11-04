package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TerminalRegistryTest {
    private static GlobalPos at(int x, int z) { return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, z)); }
    private static CargoSavedData withSite(GlobalPos site) { CargoSavedData data = new CargoSavedData(); data.terminals.put(site, new CargoSavedData.Terminal(site, site.pos(), false)); return data; }

    @Test void generatedTerminalReplacesThePredictedSiteNearIt() {
        GlobalPos site = at(1000, 1000), terminal = at(1030, 1040);
        CargoSavedData data = withSite(site);
        data.registerTerminal(terminal, new BlockPos(1035, 70, 1040));
        assertFalse(data.terminals.containsKey(site));
        assertEquals(new CargoSavedData.Terminal(terminal, new BlockPos(1035, 70, 1040), true), data.terminals.get(terminal));
        assertTrue(data.isDirty());
    }
    @Test void removedTerminalLeavesTheRegistry() {
        GlobalPos terminal = at(0, 0); CargoSavedData data = new CargoSavedData();
        data.registerTerminal(terminal, new BlockPos(5, 64, 0)); data.setDirty(false);
        data.removeTerminal(terminal, true);
        assertTrue(data.terminals.isEmpty()); assertTrue(data.isDirty());
    }
    @Test void farSiteIsKept() {
        GlobalPos site = at(1000, 1000), otherDimension = GlobalPos.of(Level.NETHER, new BlockPos(1010, 64, 1000)), terminal = at(1065, 1000);
        CargoSavedData data = withSite(site); data.terminals.put(otherDimension, new CargoSavedData.Terminal(otherDimension, otherDimension.pos(), false));
        data.registerTerminal(terminal, terminal.pos());
        assertTrue(data.terminals.containsKey(site)); assertTrue(data.terminals.containsKey(otherDimension)); assertEquals(3, data.terminals.size());
    }
    @Test void predictedSiteNearAGeneratedTerminalIsShadowed() {
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(at(292, -598), new BlockPos(292, 71, -591));
        GlobalPos site = at(296, -600), otherDimension = GlobalPos.of(Level.NETHER, new BlockPos(296, 64, -600));
        data.terminals.put(at(5000, 0), new CargoSavedData.Terminal(at(5000, 0), new BlockPos(5000, 64, 0), false));
        assertTrue(TerminalSites.shadowed(data.terminals.values(), site));
        assertTrue(TerminalSites.shadowed(data.terminals.values(), at(292 + 64, -598)));
        assertFalse(TerminalSites.shadowed(data.terminals.values(), at(292 + 65, -598)));
        assertFalse(TerminalSites.shadowed(data.terminals.values(), otherDimension));
        assertFalse(TerminalSites.shadowed(data.terminals.values(), at(5010, 0)), "only generated terminals shadow a site");
    }
    @Test void padOffsetTurnsWithTheBlock() {
        BlockPos pos = new BlockPos(10, 70, 10), offset = new BlockPos(0, 0, -7);
        assertEquals(new BlockPos(10, 70, 3), CargoTerminalBlockEntity.pad(pos, Direction.NORTH, offset));
        assertEquals(new BlockPos(17, 70, 10), CargoTerminalBlockEntity.pad(pos, Direction.EAST, offset));
        assertEquals(new BlockPos(10, 70, 17), CargoTerminalBlockEntity.pad(pos, Direction.SOUTH, offset));
        assertEquals(new BlockPos(5, 70, 10), CargoTerminalBlockEntity.pad(pos, Direction.WEST, null));
    }
    @Test void outpostTemplatePointsTheTerminalAtThePadCentre() throws Exception {
        CompoundTag template = NbtIo.readCompressed(Path.of("src/main/resources/data/skydock/structure/cargo_outpost/outpost.nbt"), NbtAccounter.unlimitedHeap());
        ListTag palette = template.getList("palette", Tag.TAG_COMPOUND), blocks = template.getList("blocks", Tag.TAG_COMPOUND);
        CompoundTag terminal = blocks.stream().map(CompoundTag.class::cast).filter(b -> palette.getCompound(b.getInt("state")).getString("Name").equals("skydock:cargo_terminal")).findFirst().orElseThrow();
        assertEquals("north", palette.getCompound(terminal.getInt("state")).getCompound("Properties").getString("facing"));
        assertEquals("skydock:cargo_terminal", terminal.getCompound("nbt").getString("id"));
        ListTag p = terminal.getList("pos", Tag.TAG_INT); int[] offset = terminal.getCompound("nbt").getIntArray("PadOffset");
        BlockPos pad = new BlockPos(p.getInt(0) + offset[0], p.getInt(1) + offset[1] - 1, p.getInt(2) + offset[2]);
        java.util.function.Function<BlockPos, String> nameAt = at -> blocks.stream().map(CompoundTag.class::cast).filter(b -> { ListTag q = b.getList("pos", Tag.TAG_INT); return q.getInt(0) == at.getX() && q.getInt(1) == at.getY() && q.getInt(2) == at.getZ(); })
                .map(b -> palette.getCompound(b.getInt("state")).getString("Name")).findFirst().orElse("structure_void");
        assertEquals("minecraft:smooth_stone", nameAt.apply(pad));
        assertEquals("minecraft:yellow_concrete", nameAt.apply(new BlockPos(p.getInt(0), p.getInt(1) - 1, p.getInt(2) - 1)), "the terminal stands at the pad's painted edge");
        assertEquals(3955, template.getInt("DataVersion"));
    }
    @Test void onlyAGeneratedTerminalRegistersItself() {
        BlockPos offset = new BlockPos(0, 0, -7);
        assertFalse(CargoTerminalBlockEntity.needsTick(false, false, null), "a hand-placed terminal waits for the command");
        assertTrue(CargoTerminalBlockEntity.needsTick(false, false, offset));
        assertTrue(CargoTerminalBlockEntity.needsTick(true, false, offset));
        assertTrue(CargoTerminalBlockEntity.needsTick(false, true, offset), "every load checks the registry again, though the villagers came once");
        assertFalse(CargoTerminalBlockEntity.needsTick(true, true, offset));
    }
    /** A crash can keep the terminal's chunk but lose the cargo file it registered in; its next load puts it back. */
    @Test void aTerminalMissingFromTheRegistryRegistersAgain() {
        GlobalPos terminal = at(400, 400); BlockPos pad = new BlockPos(400, 64, 393);
        CargoSavedData data = new CargoSavedData();
        assertTrue(data.ensureTerminal(terminal, pad));
        assertEquals(new CargoSavedData.Terminal(terminal, pad, true), data.terminals.get(terminal)); assertTrue(data.isDirty());
        data.setDirty(false);
        assertFalse(data.ensureTerminal(terminal, pad), "a registered terminal is left alone");
        assertFalse(data.isDirty());
    }
    @Test void aTerminalStandingWhereOneWasRetiredIsLiveAgain() {
        GlobalPos terminal = at(-50, 70); CargoSavedData data = new CargoSavedData();
        data.registerTerminal(terminal, terminal.pos()); data.removeTerminal(terminal, true);
        // The cargo file saw the terminal destroyed, but the chunk that still holds it did not.
        assertTrue(data.ensureTerminal(terminal, terminal.pos()));
        assertTrue(data.retired.isEmpty()); assertTrue(data.terminals.get(terminal).generated());
    }
    @Test void aPredictedSiteAtTheTerminalBecomesTheTerminal() {
        GlobalPos terminal = at(900, 900); CargoSavedData data = withSite(terminal);
        assertTrue(data.ensureTerminal(terminal, terminal.pos()));
        assertTrue(data.terminals.get(terminal).generated());
    }
    @Test void aBrokenTerminalDropsNothing() throws Exception {
        com.google.gson.JsonObject table = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(Path.of("src/main/resources/data/skydock/loot_table/blocks/cargo_terminal.json"))).getAsJsonObject();
        assertTrue(table.getAsJsonArray("pools").isEmpty());
    }
    @Test void aDestroyedGeneratedTerminalIsRetiredAndNeverPredictedAgain() {
        GlobalPos terminal = at(-1020, -950), site = at(-1016, -952), predictedNear = at(-1000, -960), far = at(-2000, -950);
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(terminal, terminal.pos());
        data.terminals.put(predictedNear, new CargoSavedData.Terminal(predictedNear, predictedNear.pos(), false));
        data.terminals.put(far, new CargoSavedData.Terminal(far, far.pos(), false));
        data.removeTerminal(terminal, true);
        assertEquals(java.util.Set.of(terminal), data.retired);
        assertEquals(java.util.Set.of(far), data.terminals.keySet(), "a predicted site at the destroyed outpost goes with it");
        assertTrue(TerminalSites.retired(data.retired, site));
        assertFalse(TerminalSites.retired(data.retired, at(-1020 + 65, -950)));
        assertFalse(TerminalSites.retired(data.retired, GlobalPos.of(Level.NETHER, site.pos())));
        assertEquals(data.retired, CargoSavedData.load(data.save(new CompoundTag(), null), null).retired, "retired positions are saved");
        CargoProfile p = new CargoProfile(); Contract c = new Contract(1, far, terminal, 1, CrateRank.TIN, "cargo.skydock.grain", 1, 0); p.contracts.put(1L, c);
        assertEquals(java.util.List.of(c), CargoService.failGone(data, p), "a contract bound for it fails");
    }
    @Test void aRemovedPredictedSiteIsNotRetired() {
        GlobalPos site = at(0, 0); CargoSavedData data = withSite(site);
        data.removeTerminal(site, true);
        assertTrue(data.retired.isEmpty());
    }
    @Test void removingATerminalDropsEveryBoardShownAtIt() {
        GlobalPos terminal = at(0, 0), other = at(900, 0); CargoSavedData data = new CargoSavedData();
        data.registerTerminal(terminal, terminal.pos()); data.registerTerminal(other, other.pos());
        for (int i = 0; i < 2; i++) { CargoProfile p = data.profile(java.util.UUID.randomUUID()); p.offers.put(terminal, java.util.List.of()); p.offersRefreshed.put(terminal, 5L); p.offers.put(other, java.util.List.of()); }
        data.removeTerminal(terminal, true);
        for (CargoProfile p : data.profiles.values()) { assertEquals(java.util.Set.of(other), p.offers.keySet()); assertTrue(p.offersRefreshed.isEmpty()); }
    }
    @Test void terminalListShowsTheNearestInTheCallersDimensionFirst() {
        java.util.List<CargoSavedData.Terminal> all = new java.util.ArrayList<>();
        for (int i = 30; i > 0; i--) all.add(new CargoSavedData.Terminal(at(i * 100, 0), BlockPos.ZERO, true));
        GlobalPos nether = GlobalPos.of(Level.NETHER, new BlockPos(0, 64, 0)); all.add(new CargoSavedData.Terminal(nether, BlockPos.ZERO, true));
        java.util.List<CargoSavedData.Terminal> got = CargoSavedData.nearest(all, at(0, 0), 20);
        assertEquals(20, got.size());
        assertEquals(at(100, 0), got.getFirst().pos()); assertEquals(at(2000, 0), got.getLast().pos());
        assertEquals(nether, CargoSavedData.nearest(all, GlobalPos.of(Level.NETHER, new BlockPos(5000, 64, 0)), 20).getFirst().pos());
    }
    @Test void aBrokenHandPlacedTerminalIsNotRetired() {
        GlobalPos terminal = at(-1020, -950), outpost = at(-1000, -960);
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(terminal, terminal.pos());
        data.removeTerminal(terminal, false);
        assertTrue(data.retired.isEmpty(), "a command-placed terminal has no outpost to retire");
        assertTrue(data.terminals.isEmpty());
        assertFalse(TerminalSites.retired(data.retired, outpost), "a real outpost near it can still be predicted");
    }
    @Test void aLevelWithoutOutpostsForgetsItsPredictions() {
        GlobalPos site = at(1000, 1000), generated = at(-500, 0), nether = GlobalPos.of(Level.NETHER, new BlockPos(1000, 64, 1000));
        CargoSavedData data = withSite(site); data.registerTerminal(generated, generated.pos());
        data.terminals.put(nether, new CargoSavedData.Terminal(nether, nether.pos(), false)); data.setDirty(false);
        assertTrue(TerminalSites.dropPredicted(data, Level.OVERWORLD));
        assertEquals(java.util.Set.of(generated, nether), data.terminals.keySet(), "only predictions in that dimension go");
        assertTrue(data.isDirty());
        data.setDirty(false);
        assertFalse(TerminalSites.dropPredicted(data, Level.OVERWORLD)); assertFalse(data.isDirty());
    }
}
