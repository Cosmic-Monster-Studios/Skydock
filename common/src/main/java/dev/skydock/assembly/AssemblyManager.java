package dev.skydock.assembly;

import net.minecraft.network.chat.Component;
import dev.skydock.block.DockBlockEntity;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.DockFrame;
import dev.skydock.data.MassTable;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Server-authoritative assembly validation and the staged placement transaction. */
public final class AssemblyManager {
    public static final int BLOCKS_PER_STEP = 4;
    public static final int STEP_TICKS = 2;
    /** Prefixes of the error and status keys; assembly failures derive their cancelled_/paused_ status from the error key. */
    private static final String E = "error.skydock.", STATUS = "status.skydock.";
    private static final Map<ServerLevel, Map<BlockPos, DockBlockEntity>> ACTIVE = new IdentityHashMap<>();

    private AssemblyManager() {}

    public record Check(boolean clear, String message) {}

    public static ShipPattern selected(DockBlockEntity dock) {
        if (dock.assemblyJob() != null) {
            AssemblyJob job = dock.assemblyJob();
            ShipPattern catalog = ShipPatterns.get(job.pattern).orElse(null);
            String name = catalog == null ? job.pattern.getPath() : catalog.name();
            String description = catalog == null ? "pattern.skydock.restored.description" : catalog.description();
            return new ShipPattern(job.pattern, name, description, dock.tier(), job.cells.stream()
                    .map(cell -> new ShipPattern.Block(cell.pos(), cell.state(), cell.decoration())).toList());
        }
        return ShipPatterns.get(dock.selectedPattern()).filter(pattern -> pattern.tier() == dock.tier())
                .orElseGet(() -> ShipPatterns.defaultFor(dock.tier()));
    }

    public static Check validate(ServerLevel level, DockBlockEntity dock, ShipPattern pattern, boolean decorations) {
        if (pattern.tier() != dock.tier()) return new Check(false, "error.skydock.assembly_tier");
        if (dock.assemblyJob() == null && ShipManager.berthReserved(level, dock.getBlockPos()))
            return new Check(false, "error.skydock.assembly_reserved");
        List<ShipPattern.Block> cells = pattern.included(decorations);
        if (cells.size() > MassTable.cap(dock.tier())) return new Check(false, "error.skydock.assembly_limit");
        ShipPattern.Stats stats = pattern.stats(decorations);
        long helms = cells.stream().filter(cell -> cell.state().is(SkydockBlocks.HELM.get())).count();
        if (helms != 1) return new Check(false, "error.skydock.assembly_helm");
        if (stats.engines() < 1) return new Check(false, "error.skydock.assembly_engine");
        if (stats.cells() < 1) return new Check(false, "error.skydock.assembly_cells");
        int requiredCells = (stats.structuralBlocks() + MassTable.blocksPerCell() - 1) / MassTable.blocksPerCell();
        if (stats.cells() < requiredCells) return new Check(false, "error.skydock.assembly_more_cells");
        if (stats.mass() > stats.lift()) return new Check(false, "error.skydock.assembly_heavy");
        DockFrame frame = dock.frame();
        AABB footprint = frame.toWorld(pattern.bounds(decorations));
        if (footprint.minY < level.getMinBuildHeight() || footprint.maxY > level.getMaxBuildHeight())
            return new Check(false, "error.skydock.assembly_height");
        if (!level.getWorldBorder().isWithinBounds(footprint)) return new Check(false, "error.skydock.assembly_border");
        int minChunkX = ((int) Math.floor(footprint.minX)) >> 4;
        int maxChunkX = ((int) Math.ceil(footprint.maxX) - 1) >> 4;
        int minChunkZ = ((int) Math.floor(footprint.minZ)) >> 4;
        int maxChunkZ = ((int) Math.ceil(footprint.maxZ) - 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (!level.hasChunk(x, z)) return new Check(false, "error.skydock.assembly_unloaded");
        String occupant = occupant(level, footprint);
        if (occupant != null) return new Check(false, occupant);

        Map<BlockPos, BlockState> allowed = new HashMap<>();
        if (dock.assemblyJob() != null) {
            AssemblyJob job = dock.assemblyJob();
            for (int i = 0; i < job.placed; i++) allowed.put(frame.toWorld(job.cells.get(i).pos()), placed(frame, job.cells.get(i)));
        }
        int minX = (int) Math.floor(footprint.minX), minY = (int) Math.floor(footprint.minY), minZ = (int) Math.floor(footprint.minZ);
        int maxX = (int) Math.ceil(footprint.maxX) - 1, maxY = (int) Math.ceil(footprint.maxY) - 1, maxZ = (int) Math.ceil(footprint.maxZ) - 1;
        for (BlockPos worldPos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) continue;
            BlockState expected = allowed.get(worldPos);
            if (expected == null || !state.equals(expected))
                return new Check(false, "error.skydock.assembly_blocked");
        }
        return new Check(true, "status.skydock.space_clear");
    }

    /** Things that can move out of the way on their own, so a running assembly waits for them rather than failing. */
    private static String occupant(ServerLevel level, AABB footprint) {
        if (!level.getEntities((Entity) null, footprint, entity -> !entity.isSpectator()).isEmpty())
            return "error.skydock.assembly_entities";
        for (Ship ship : ShipManager.ships(level)) if (ship.pose.toWorld(ship.hullBounds()).intersects(footprint))
            return "error.skydock.assembly_ship";
        return null;
    }

    public static boolean start(ServerPlayer player, DockBlockEntity dock) {
        ServerLevel level = player.serverLevel();
        if (dock.assemblyJob() != null) { ShipManager.tell(player, "message.skydock.assembly_busy"); return false; }
        ShipPattern pattern = selected(dock);
        Check check = validate(level, dock, pattern, dock.decorations());
        if (!check.clear()) { dock.setStatus(check.message()); ShipManager.tell(player, check.message()); return false; }
        Map<Item, Integer> cost = pattern.cost(dock.decorations());
        Map<Item, Integer> available = AssemblyInventory.available(level, dock.getBlockPos());
        for (Map.Entry<Item, Integer> requirement : cost.entrySet()) if (available.getOrDefault(requirement.getKey(), 0) < requirement.getValue()) {
            dock.setStatus("status.skydock.resources_missing");
            ShipManager.tell(player, dock.status());
            return false;
        }
        List<ItemStack> escrow = AssemblyInventory.extract(level, dock.getBlockPos(), cost);
        if (escrow.isEmpty() && !cost.isEmpty()) {
            dock.setStatus("status.skydock.chest_changed");
            ShipManager.tell(player, dock.status());
            return false;
        }
        dock.beginAssembly(new AssemblyJob(pattern.id(), dock.decorations(), pattern.included(dock.decorations()), escrow));
        register(level, dock);
        ShipManager.tell(player, "message.skydock.assembly_started", Component.translatable(pattern.name()));
        return true;
    }

    /** A job may only continue while its pattern is still loaded; a datapack reload can remove it. */
    public static boolean stillValid(ResourceLocation pattern, Predicate<ResourceLocation> loaded) { return loaded.test(pattern); }

    public static void tick(ServerLevel level, DockBlockEntity dock) {
        AssemblyJob job = dock.assemblyJob();
        if (job == null) { unregister(level, dock); return; }
        // Checked lazily here rather than from the reload listener, whose apply may run off the server thread.
        if (!stillValid(job.pattern, id -> ShipPatterns.get(id).isPresent())) { cancel(level, dock, "status.skydock.cancelled_pattern_removed"); return; }
        register(level, dock);
        if (level.getGameTime() - job.lastStep < STEP_TICKS) return;
        job.lastStep = level.getGameTime();
        ShipPattern pattern = selected(dock);
        DockFrame frame = dock.frame();
        AABB footprint = frame.toWorld(pattern.bounds(job.decorations));
        boolean loaded = chunksLoaded(level, footprint);
        String occupant = loaded ? occupant(level, footprint) : null;
        String paused = !loaded ? "status.skydock.paused_unloaded" : occupant != null ? STATUS + "paused_" + occupant.substring(E.length()) : null;
        if (paused != null) {
            if (!dock.status().equals(paused)) dock.setStatus(paused);
            return;
        }
        if (dock.status().startsWith(STATUS + "paused") || dock.status().startsWith("Assembly paused")) dock.setStatus("status.skydock.in_progress");
        boolean recovered = false;
        // A world chunk may have saved just ahead of the dock journal. Adopt only the contiguous
        // exact states from the already-cleared plan so recovery cannot refund duplicate blocks.
        while (!job.complete()) {
            AssemblyJob.Cell cell = job.cells.get(job.placed);
            if (!level.getBlockState(frame.toWorld(cell.pos())).equals(placed(frame, cell))) break;
            if (!job.spend(Item.byBlock(cell.state().getBlock()))) { fail(level, dock, "error.skydock.assembly_escrow"); return; }
            job.placed++;
            recovered = true;
        }
        if (recovered) dock.markAssemblyProgress();
        Check check = validate(level, dock, pattern, job.decorations);
        if (!check.clear()) { fail(level, dock, check.message()); return; }
        int placedThisStep = 0;
        while (!job.complete() && placedThisStep++ < BLOCKS_PER_STEP) {
            AssemblyJob.Cell cell = job.cells.get(job.placed);
            BlockPos worldPos = frame.toWorld(cell.pos());
            if (!level.getBlockState(worldPos).isAir()) { fail(level, dock, "error.skydock.assembly_obstructed"); return; }
            if (!job.spend(Item.byBlock(cell.state().getBlock()))) { fail(level, dock, "error.skydock.assembly_escrow"); return; }
            if (!level.setBlock(worldPos, placed(frame, cell), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                AssemblyInventory.merge(job.escrow, job.undoLastSpend());
                fail(level, dock, "error.skydock.assembly_place"); return;
            }
            job.placed++;
            level.sendParticles(ParticleTypes.END_ROD, worldPos.getX() + .5, worldPos.getY() + .5, worldPos.getZ() + .5,
                    3, .22, .22, .22, .015);
        }
        dock.markAssemblyProgress();
        if (job.complete()) {
            for (AssemblyJob.Cell cell : job.cells) if (!level.getBlockState(frame.toWorld(cell.pos())).equals(placed(frame, cell))) {
                fail(level, dock, "error.skydock.assembly_mismatch"); return;
            }
            dock.completeAssembly(job.total());
            unregister(level, dock);
        } else {
            AssemblyJob.Cell next = job.cells.get(job.placed);
            BlockPos at = frame.toWorld(next.pos());
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.getX() + .5, at.getY() + .5, at.getZ() + .5,
                    6, .45, .45, .45, .025);
        }
    }

    public static void cancel(ServerLevel level, DockBlockEntity dock, String reason) {
        if (dock.assemblyJob() == null) return;
        rollback(level, dock, reason);
    }

    private static void fail(ServerLevel level, DockBlockEntity dock, String reason) {
        rollback(level, dock, STATUS + "cancelled_" + reason.substring(E.length()));
    }

    private static void rollback(ServerLevel level, DockBlockEntity dock, String reason) {
        AssemblyJob job = dock.assemblyJob();
        if (job == null) return;
        DockFrame frame = dock.frame();
        List<ItemStack> refund = new ArrayList<>();
        List<Map.Entry<BlockPos, Block>> removed = new ArrayList<>();
        for (ItemStack stack : job.escrow) AssemblyInventory.merge(refund, stack);
        for (int i = 0; i < job.placed; i++) {
            AssemblyJob.Cell cell = job.cells.get(i);
            BlockPos worldPos = frame.toWorld(cell.pos());
            if (!level.getBlockState(worldPos).equals(placed(frame, cell))) continue;
            if (level.getBlockEntity(worldPos) instanceof Container container) Containers.dropContents(level, worldPos, container);
            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            removed.add(Map.entry(worldPos.immutable(), cell.state().getBlock()));
            if (i < job.spent.size()) AssemblyInventory.merge(refund, job.spent.get(i));
        }
        // Notify the surrounding world only after every exact-state ownership check is finished.
        for (Map.Entry<BlockPos, Block> entry : removed) level.updateNeighborsAt(entry.getKey(), entry.getValue());
        dock.finishAssembly(reason);
        unregister(level, dock);
        AssemblyInventory.refund(level, dock.getBlockPos(), refund);
    }

    public static boolean protects(ServerLevel level, BlockPos pos) {
        Map<BlockPos, DockBlockEntity> docks = ACTIVE.get(level);
        if (docks == null) return false;
        docks.entrySet().removeIf(entry -> level.getBlockEntity(entry.getKey()) != entry.getValue() || entry.getValue().assemblyJob() == null);
        for (DockBlockEntity dock : docks.values()) {
            AssemblyJob job = dock.assemblyJob();
            BlockPos local = dock.frame().toLocal(pos);
            if (job.cells.stream().anyMatch(cell -> cell.pos().equals(local))) return true;
        }
        return false;
    }

    /** Pattern cells are north-frame; in the world they are turned with the dock. */
    private static BlockState placed(DockFrame frame, AssemblyJob.Cell cell) { return cell.state().rotate(frame.rotation()); }

    private static void register(ServerLevel level, DockBlockEntity dock) {
        ACTIVE.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(dock.getBlockPos().immutable(), dock);
    }

    private static boolean chunksLoaded(ServerLevel level, AABB footprint) {
        int minChunkX = ((int) Math.floor(footprint.minX)) >> 4;
        int maxChunkX = ((int) Math.ceil(footprint.maxX) - 1) >> 4;
        int minChunkZ = ((int) Math.floor(footprint.minZ)) >> 4;
        int maxChunkZ = ((int) Math.ceil(footprint.maxZ) - 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (!level.hasChunk(x, z)) return false;
        return true;
    }

    private static void unregister(ServerLevel level, DockBlockEntity dock) {
        Map<BlockPos, DockBlockEntity> docks = ACTIVE.get(level);
        if (docks != null) {
            docks.remove(dock.getBlockPos());
            if (docks.isEmpty()) ACTIVE.remove(level);
        }
    }

    public static void stop(MinecraftServer server) {
        ACTIVE.keySet().removeIf(level -> level.getServer() == server);
    }
}
