package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.data.DockFrame;
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
    public static void flush(MinecraftServer server) {
        ShipSavedData data = ShipSavedData.get(server); data.setDirty();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("skydock_ships.dat");
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), server.registryAccess()));
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
    /** Queues the given chunks of a level a phase edited and waits until region storage has written them. */
    static void saveChunks(ServerLevel level, Set<ChunkPos> chunks) {
        ServerChunkCache cache = level.getChunkSource();
        for (ChunkPos pos : chunks) {
            ChunkAccess chunk = cache.getChunkNow(pos.x, pos.z);
            if (chunk != null) ((ChunkMapAccessor) cache.chunkMap).skydock$save(chunk);
        }
        cache.chunkMap.flushWorker();
    }
    /** One transfer phase: paste the hull where it goes, clear it where it was, then advance the journal. */
    interface Steps { void paste(); void clear(); void save(boolean pasted); void advance(); }
    /**
     * Ordering invariant: the journal never advances past a phase whose world writes are not durable. Each side is saved before the
     * next step, and the journal is written last, so a crash anywhere replays the same phase, and pasting and clearing are idempotent.
     */
    static void run(Steps steps) { steps.paste(); steps.save(true); steps.clear(); steps.save(false); steps.advance(); }
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
                public void advance() {
                    if (launching) {
                        ship.phase = "active"; ship.transferOrigin = null;
                        // The yard now holds the real block entities; keeping the journal copy would bloat every save.
                        ship.blockEntities.clear();
                    } else { ShipSavedData.get(server).ships.remove(ship.id); ShipManager.releaseTickets(server, ship); }
                    flush(server);
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
