package dev.skydock.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Client-only cache of the exact dock plan and inventory counts authored by the server. */
public final class DockView {
    public record Pattern(ResourceLocation id, String name, String description) {}
    public record PreviewBlock(BlockPos pos, BlockState state) {}
    public record Cost(Item item, ResourceLocation id, int required, int available) {
        public boolean missing() { return available < required; }
    }
    public record Stats(int blocks, int structuralBlocks, double mass, double lift, int cells, int engines,
                        double power, double speed, int width, int height, int length) {}
    public record Snapshot(int containerId, BlockPos dock, int revision, String tier, ResourceLocation selected,
                           boolean decorations, boolean active, boolean complete, boolean manualHull,
                           int progress, int total, String status,
                           boolean canAssemble, boolean spaceClear, List<Pattern> patterns,
                           List<PreviewBlock> preview, List<Cost> costs, Stats stats) {}

    private record Key(int containerId, BlockPos dock) {}
    private static final Map<Key, Snapshot> SNAPSHOTS = new HashMap<>();

    private DockView() {}

    public static Snapshot get(int containerId, BlockPos dock) {
        return SNAPSHOTS.get(new Key(containerId, dock));
    }

    public static void remove(int containerId, BlockPos dock) {
        SNAPSHOTS.remove(new Key(containerId, dock));
    }

    /** Forgets every snapshot, so a later world reusing a container id and dock position never shows one from before. */
    public static void clear() { SNAPSHOTS.clear(); }

    public static void receive(CompoundTag data) {
        int container = data.getInt("Container");
        BlockPos dock = BlockPos.of(data.getLong("Dock"));
        Key key = new Key(container, dock);
        Snapshot previous = SNAPSHOTS.get(key);
        String tier = data.getString("Tier");
        ResourceLocation selected = ResourceLocation.tryParse(data.getString("Selected"));
        if (selected == null) selected = ResourceLocation.withDefaultNamespace("air");
        boolean decorations = data.getBoolean("Decorations");
        boolean assemblySpaceClear = data.contains("AssemblySpaceClear")
                ? data.getBoolean("AssemblySpaceClear") : data.getBoolean("SpaceClear");
        boolean sameCatalog = previous != null && previous.tier().equals(tier);
        boolean samePlan = sameCatalog && previous.selected().equals(selected) && previous.decorations() == decorations;

        // Always re-read: a datapack reload can change the list while the screen is open.
        List<Pattern> choices = new ArrayList<>();
        for (Tag value : data.getList("Patterns", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Id"));
            if (id != null) choices.add(new Pattern(id, tag.getString("Name"), tag.getString("Description")));
        }
        List<Pattern> patterns = List.copyOf(choices);

        List<PreviewBlock> preview;
        if (samePlan) preview = previous.preview();
        else {
            List<PreviewBlock> parsed = new ArrayList<>();
            for (Tag value : data.getList("Preview", Tag.TAG_COMPOUND)) {
                CompoundTag tag = (CompoundTag) value;
                BlockState state = Block.stateById(tag.getInt("State"));
                if (!state.isAir()) parsed.add(new PreviewBlock(BlockPos.of(tag.getLong("Pos")), state));
            }
            preview = List.copyOf(parsed);
        }
        List<Cost> costs = new ArrayList<>();
        for (Tag value : data.getList("Costs", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) value;
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Item"));
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            costs.add(new Cost(item, id, tag.getInt("Required"), tag.getInt("Available")));
        }
        Stats parsedStats;
        if (samePlan) parsedStats = previous.stats();
        else {
            CompoundTag stats = data.getCompound("Stats");
            parsedStats = new Stats(stats.getInt("Blocks"), stats.getInt("StructuralBlocks"), stats.getDouble("Mass"),
                    stats.getDouble("Lift"), stats.getInt("Cells"), stats.getInt("Engines"),
                    stats.getDouble("Power"), stats.getDouble("Speed"), stats.getInt("Width"),
                    stats.getInt("Height"), stats.getInt("Length"));
        }
        Snapshot snapshot = new Snapshot(container, dock, data.getInt("Revision"), tier, selected,
                decorations, data.getBoolean("Active"), data.getBoolean("Complete"), data.getBoolean("ManualHull"),
                data.getInt("Progress"), data.getInt("Total"),
                data.getString("Status"), data.getBoolean("CanAssemble"), assemblySpaceClear,
                patterns, preview, List.copyOf(costs), parsedStats);
        SNAPSHOTS.put(key, snapshot);
    }
}
