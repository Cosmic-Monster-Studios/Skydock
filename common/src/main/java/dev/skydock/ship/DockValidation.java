package dev.skydock.ship;

import dev.skydock.block.*;
import dev.skydock.data.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** {@code error} is a translation key (empty when valid) and {@code args} its format arguments. */
public record DockValidation(Map<BlockPos, BlockState> blocks, double mass, double lift, int cells, int engines, String error, List<Object> args) {
    public DockValidation(Map<BlockPos, BlockState> blocks, double mass, double lift, int cells, int engines, String error) { this(blocks, mass, lift, cells, engines, error, List.of()); }
    public boolean valid() { return error.isEmpty(); }
    public Component summary() {
        return Component.translatable("message.skydock.dock_summary", blocks.size(), String.format(java.util.Locale.ROOT, "%.0f", mass),
                String.format(java.util.Locale.ROOT, "%.0f", lift), cells, engines, valid() ? Component.translatable("message.skydock.ready_to_launch") : Component.translatable(error, args.toArray()));
    }
    /** Reads the berth through the dock's frame; {@code blocks} come back in the north frame, states turned to match. */
    public static DockValidation scan(ServerLevel level, DockBlockEntity dock) {
        DockFrame frame = dock.frame(); DockTier tier = frame.tier(); BlockPos origin = frame.origin();
        Rotation back = DockFrame.inverse(frame.rotation());
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        double mass = 0; int structural = 0, cells = 0, engines = 0, helms = 0; BlockPos helm = null;
        String error = ""; List<Object> args = List.of();
        for (int x = 0; x < tier.width; x += 16) for (int z = 0; z < tier.length; z += 16) {
            if (!level.hasChunkAt(frame.toWorld(new BlockPos(x, 0, z))) || !level.hasChunkAt(frame.toWorld(new BlockPos(Math.min(x + 15, tier.width - 1), 0, Math.min(z + 15, tier.length - 1)))))
                return new DockValidation(blocks, 0, 0, 0, 0, "error.skydock.dock_unloaded");
        }
        if (origin.getY() < level.getMinBuildHeight() || origin.getY() + tier.height > level.getMaxBuildHeight())
            return new DockValidation(blocks, 0, 0, 0, 0, "error.skydock.dock_height");
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(tier.width - 1, tier.height - 1, tier.length - 1))) {
            BlockPos at = frame.toWorld(p); BlockState state = level.getBlockState(at);
            if (state.isAir() || state.is(SkydockBlocks.NOT_SHIP)) continue;
            if (state.is(SkydockBlocks.FORBIDDEN) || state.getDestroySpeed(level, at) < 0) { error = "error.skydock.dock_unmovable"; args = List.of(state.getBlock().getName()); }
            if (!state.getFluidState().isEmpty()) error = "error.skydock.dock_fluid";
            state = state.rotate(back);
            BlockPos local = p.immutable(); blocks.put(local, state);
            boolean decoration = state.is(SkydockBlocks.DECORATIONS);
            if (!decoration) { mass += MassTable.mass(state); structural++; }
            if (state.is(SkydockBlocks.HELM.get())) { helms++; helm = local; }
            if (!decoration && state.is(SkydockBlocks.LIFT_CELLS)) cells++;
            if (state.is(SkydockBlocks.ENGINES)) engines++;
            if (blocks.size() > MassTable.cap(tier)) return new DockValidation(blocks, mass, cells * MassTable.liftPerCell(), cells, engines, "error.skydock.dock_too_many");
        }
        double lift = cells * MassTable.liftPerCell();
        if (blocks.isEmpty()) error = "error.skydock.dock_empty";
        else if (helms != 1) { error = "error.skydock.dock_helms"; args = List.of(helms); }
        else if (error.isEmpty()) {
            Set<BlockPos> reached = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(helm); reached.add(helm);
            while (!queue.isEmpty()) {
                BlockPos p = queue.remove();
                for (Direction face : Direction.values()) {
                    BlockPos q = p.relative(face);
                    if (blocks.containsKey(q) && reached.add(q)) queue.add(q);
                    if (q.getX() < 0 || q.getX() >= tier.width || q.getY() < 0 || q.getY() >= tier.height || q.getZ() < 0 || q.getZ() >= tier.length) {
                        BlockState outside = level.getBlockState(frame.toWorld(q));
                        if (!outside.isAir() && !outside.is(SkydockBlocks.NOT_SHIP)) error = "error.skydock.dock_outside";
                    }
                }
            }
            if (reached.size() != blocks.size()) error = "error.skydock.dock_disconnected";
        }
        if (error.isEmpty() && cells == 0) error = "error.skydock.dock_no_cells";
        if (error.isEmpty() && structural > cells * MassTable.blocksPerCell()) error = "error.skydock.dock_need_cells";
        if (error.isEmpty() && lift < mass) error = "error.skydock.dock_overweight";
        return new DockValidation(blocks, mass, lift, cells, engines, error, args);
    }
}
