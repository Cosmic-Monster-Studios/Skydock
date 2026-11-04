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
        data.removeTerminal(terminal);
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
}
