package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.data.DockFrame;
import dev.skydock.data.DockTier;
import dev.skydock.mixin.ChunkMapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Journal is flushed before either world is edited, and advanced only once both are saved. Interrupted transfers can be replayed. */
public final class ShipTransfer {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    public static boolean changing;
    /** Writes a compressed NBT file durably before returning. The loader entry point replaces it; this default is the plain fallback. */
    @FunctionalInterface public interface JournalWriter { void write(CompoundTag tag, Path file) throws IOException; }
    public static JournalWriter journalWriter = ShipTransfer::writeDurably;

    /** Copies block entities keyed by local cell; a berth's cells are turned by {@code rotation}, the shipyard's are not. NBT is never rotated. */
    public static void snapshot(Ship ship, ServerLevel level, BlockPos origin, Rotation rotation) {
        ship.blockEntities.clear();
        for (BlockPos p : ship.blocks.keySet()) {
            BlockEntity entity = level.getBlockEntity(DockFrame.toWorld(origin, rotation, p));
            if (entity != null) ship.blockEntities.put(p, entity.saveWithFullMetadata(level.registryAccess()));
        }
    }
    /** Chunks holding any of the cells, which are offsets from origin. Section coordinates, so negative positions floor correctly. */
    static Set<ChunkPos> chunksOf(Collection<BlockPos> local, BlockPos origin) { return chunksOf(local, origin, Rotation.NONE); }
    /** The same for a berth turned by {@code rotation}: each cell is mapped to its world position first. */
    static Set<ChunkPos> chunksOf(Collection<BlockPos> local, BlockPos origin, Rotation rotation) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos p : local) {
            BlockPos at = DockFrame.toWorld(origin, rotation, p);
            chunks.add(new ChunkPos(SectionPos.blockToSectionCoord(at.getX()), SectionPos.blockToSectionCoord(at.getZ())));
        }
        return chunks;
    }
    /**
     * Writes only the Skydock journal, and returns once it is durably on disk. It does not go through SavedData.save(File):
     * NeoForge queues that on a worker thread, where an older autosave snapshot could land after this write.
     */
    public static void flush(MinecraftServer server) { flush(server, null, null); }
    /** The same, but with ship {@code id}'s record replaced by {@code record}, or dropped when that is null; memory is not touched. */
    static void flush(MinecraftServer server, UUID id, CompoundTag record) {
        ShipSavedData data = ShipSavedData.get(server); data.setDirty();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("skydock_ships.dat");
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), server.registryAccess(), id, record));
        NbtUtils.addCurrentDataVersion(root);
        try { Files.createDirectories(file.getParent()); journalWriter.write(root, file); }
        catch (IOException e) { throw new UncheckedIOException("Could not write the Skydock journal", e); }
        data.setDirty(false);
    }
    /** Fallback writer: temp file beside the target, fsynced, then atomically moved over it. */
    private static void writeDurably(CompoundTag tag, Path file) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temp);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    /**
     * Queues the given chunks of a level a phase edited and waits until region storage has written them. A chunk that unloaded since the
     * edit is loaded again (its unload queued a write, which the wait covers). A chunk that cannot be loaded or serialized throws, so the
     * journal does not advance past a phase that is not on disk.
     */
    static void saveChunks(ServerLevel level, Set<ChunkPos> chunks) {
        ServerChunkCache cache = level.getChunkSource();
        for (ChunkPos pos : chunks) {
            ChunkAccess chunk = cache.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            if (chunk == null) throw new IllegalStateException("Could not load chunk " + pos + " in " + level.dimension().location() + " to save it");
            // A full chunk that was unsaved and did not save hit an exception, which vanilla logs and swallows after marking it saved.
            boolean unsaved = chunk.isUnsaved();
            if (!((ChunkMapAccessor) cache.chunkMap).skydock$save(chunk) && unsaved) {
                chunk.setUnsaved(true);
                throw new IllegalStateException("Could not save chunk " + pos + " in " + level.dimension().location());
            }
        }
        cache.chunkMap.flushWorker();
    }
    /** Whether world {@code pos} lies in the {@code tier} envelope of a berth whose local {@code (0,0,0)} is at {@code origin}, turned by {@code rotation}. */
    static boolean inBerth(BlockPos origin, Rotation rotation, DockTier tier, BlockPos pos) {
        BlockPos local = pos.subtract(origin).rotate(DockFrame.inverse(rotation));
        return local.getX() >= 0 && local.getX() < tier.width && local.getY() >= 0 && local.getY() < tier.height && local.getZ() >= 0 && local.getZ() < tier.length;
    }
    /** One transfer phase: paste the hull where it goes, clear it where it was, write the next phase to the journal, then apply it in memory. */
    interface Steps { void paste(); void clear(); void save(boolean pasted); void journal(); void apply(); }
    /**
     * Ordering invariant: the journal never advances past a phase whose world writes are not durable. Each side is saved before the
     * next step, and the journal is written before memory changes, so a crash anywhere replays the same phase, and a journal write
     * that throws leaves the ship in its old phase, where the pause and retry path picks it up. Pasting and clearing are idempotent.
     */
    static void run(Steps steps) { steps.paste(); steps.save(true); steps.clear(); steps.save(false); steps.journal(); steps.apply(); }
    public static void complete(MinecraftServer server, Ship ship) {
        if (ship.phase.equals("active")) return;
        boolean launching = ship.phase.equals("launching");
        if (!launching && !ship.phase.equals("redocking")) throw new IllegalStateException("Unknown transfer phase " + ship.phase);
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD);
        ServerLevel destination = server.getLevel(ship.transferDimension);
        if (yard == null || destination == null) throw new IllegalStateException("Transfer dimension missing; journal retained");
        ShipManager.wake(server, ship);
        Rotation berth = DockFrame.rotation(ship.transferFacing);
        Set<ChunkPos> yardChunks = chunksOf(ship.blocks.keySet(), ship.yard), berthChunks = chunksOf(ship.blocks.keySet(), ship.transferOrigin, berth);
        BlockPos berthOrigin = ship.transferOrigin;
        changing = true;
        try {
            // Launching pastes into the yard and clears the berth; redocking does the reverse.
            run(new Steps() {
                public void paste() { if (launching) ShipTransfer.paste(ship, yard, ship.yard, Rotation.NONE); else ShipTransfer.paste(ship, destination, berthOrigin, berth); }
                public void clear() { if (launching) ShipTransfer.clear(ship, destination, berthOrigin, berth); else ShipTransfer.clear(ship, yard, ship.yard, Rotation.NONE); }
                public void save(boolean pasted) { if (pasted == launching) saveChunks(yard, yardChunks); else saveChunks(destination, berthChunks); }
                // The journal gets the next phase computed from the ship as it stands; memory changes only once that write is on disk.
                public void journal() { flush(server, ship.id, launching ? ship.saveLaunched(server.registryAccess()) : null); }
                public void apply() {
                    if (launching) ship.finishLaunch();
                    else { ShipSavedData.get(server).ships.remove(ship.id); ShipManager.releaseTickets(server, ship); }
                }
            });
        } finally { changing = false; ShipManager.fleetChanged(); }
    }
    private static void clear(Ship ship, ServerLevel level, BlockPos origin, Rotation rotation) {
        for (BlockPos p : ship.blocks.keySet()) {
            BlockPos at = DockFrame.toWorld(origin, rotation, p);
            // Removing the BE before its block avoids Container.onRemove duplicating inventory drops.
            level.removeBlockEntity(at);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(DockFrame.toWorld(origin, rotation, p), Blocks.AIR);
    }
    /** Pastes only the stored hull states into the ship's yard region, for a yard whose chunks were deleted; returns the non-air blocks placed. */
    static int pasteStates(Ship ship, ServerLevel yard) {
        int placed = 0;
        for (var entry : ship.blocks.entrySet()) { yard.setBlock(ship.yard.offset(entry.getKey()), entry.getValue(), FLAGS); if (!entry.getValue().isAir()) placed++; }
        for (BlockPos p : ship.blocks.keySet()) yard.updateNeighborsAt(ship.yard.offset(p), ship.state(p).getBlock());
        return placed;
    }
    /** Hull states are stored north-frame and turned by {@code rotation} on the way into a berth. */
    private static void paste(Ship ship, ServerLevel level, BlockPos origin, Rotation rotation) {
        ship.blocks.forEach((p, state) -> {
            BlockPos at = DockFrame.toWorld(origin, rotation, p); level.removeBlockEntity(at);
            level.setBlock(at, state.rotate(rotation), FLAGS);
        });
        ship.blockEntities.forEach((p, data) -> {
            BlockPos at = DockFrame.toWorld(origin, rotation, p); CompoundTag tag = data.copy();
            tag.putInt("x", at.getX()); tag.putInt("y", at.getY()); tag.putInt("z", at.getZ());
            BlockEntity entity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
            if (entity == null) throw new IllegalStateException("Could not restore block entity at " + at);
            level.setBlockEntity(entity); entity.setChanged();
        });
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(DockFrame.toWorld(origin, rotation, p), ship.state(p).getBlock());
    }
}
