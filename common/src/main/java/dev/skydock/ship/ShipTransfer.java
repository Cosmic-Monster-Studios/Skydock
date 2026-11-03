package dev.skydock.ship;

import dev.skydock.Skydock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;
import dev.skydock.mixin.ChunkMapAccessor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Journal is flushed before either world is edited. Interrupted transfers can be replayed. */
public final class ShipTransfer {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    public static boolean changing;

    public static void snapshot(Ship ship, ServerLevel level, BlockPos origin) {
        ship.blockEntities.clear();
        for (BlockPos p : ship.blocks.keySet()) {
            BlockEntity entity = level.getBlockEntity(origin.offset(p));
            if (entity != null) ship.blockEntities.put(p, entity.saveWithFullMetadata(level.registryAccess()));
        }
    }
    /** Chunks holding any of the cells, which are offsets from origin. Section coordinates, so negative positions floor correctly. */
    static Set<ChunkPos> chunksOf(Collection<BlockPos> local, BlockPos origin) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos p : local)
            chunks.add(new ChunkPos(SectionPos.blockToSectionCoord(origin.getX() + p.getX()), SectionPos.blockToSectionCoord(origin.getZ() + p.getZ())));
        return chunks;
    }
    /**
     * Writes only the Skydock journal, synchronously. Not SavedData.save(File): NeoForge queues that write on a worker
     * thread, and the journal must be on disk before the world is edited. Written beside the target, then moved over it.
     */
    public static void flush(MinecraftServer server) {
        ShipSavedData data = ShipSavedData.get(server); data.setDirty();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("skydock_ships.dat");
        Path temp = file.resolveSibling("skydock_ships.dat.tmp");
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), server.registryAccess()));
        NbtUtils.addCurrentDataVersion(root);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, temp);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException("Could not write the Skydock journal", e); }
        data.setDirty(false);
    }
    /** Writes the journal, then the given chunks of a level a phase edited, and waits for those writes before the next phase begins. */
    public static void flush(MinecraftServer server, ServerLevel level, Set<ChunkPos> chunks) {
        flush(server);
        ServerChunkCache cache = level.getChunkSource();
        for (ChunkPos pos : chunks) {
            ChunkAccess chunk = cache.getChunkNow(pos.x, pos.z);
            if (chunk != null) ((ChunkMapAccessor) cache.chunkMap).skydock$save(chunk);
        }
        cache.chunkMap.flushWorker();
    }
    public static void complete(MinecraftServer server, Ship ship) {
        if (ship.phase.equals("active")) return;
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD);
        ServerLevel destination = server.getLevel(ship.transferDimension);
        if (yard == null || destination == null) throw new IllegalStateException("Transfer dimension missing; journal retained");
        ShipManager.wake(server, ship);
        Set<ChunkPos> yardChunks = chunksOf(ship.blocks.keySet(), ship.yard), berthChunks = chunksOf(ship.blocks.keySet(), ship.transferOrigin);
        changing = true;
        try {
            if (ship.phase.equals("launching")) {
                paste(ship, yard, ship.yard);
                flush(server, yard, yardChunks);
                clear(ship, destination, ship.transferOrigin);
                ship.phase = "active";
                ship.transferOrigin = null;
                // The yard now holds the real block entities; keeping the journal copy would bloat every save.
                ship.blockEntities.clear();
                flush(server, destination, berthChunks);
            } else if (ship.phase.equals("redocking")) {
                paste(ship, destination, ship.transferOrigin);
                flush(server, destination, berthChunks);
                clear(ship, yard, ship.yard);
                ShipSavedData.get(server).ships.remove(ship.id);
                ShipManager.releaseTickets(server, ship);
                flush(server, yard, yardChunks);
            } else throw new IllegalStateException("Unknown transfer phase " + ship.phase);
        } finally { changing = false; ShipManager.fleetChanged(); }
    }
    private static void clear(Ship ship, ServerLevel level, BlockPos origin) {
        for (BlockPos p : ship.blocks.keySet()) {
            BlockPos at = origin.offset(p);
            // Removing the BE before its block avoids Container.onRemove duplicating inventory drops.
            level.removeBlockEntity(at);
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(origin.offset(p), Blocks.AIR);
    }
    private static void paste(Ship ship, ServerLevel level, BlockPos origin) {
        ship.blocks.forEach((p, state) -> {
            BlockPos at = origin.offset(p); level.removeBlockEntity(at);
            level.setBlock(at, state, FLAGS);
        });
        ship.blockEntities.forEach((p, data) -> {
            BlockPos at = origin.offset(p); CompoundTag tag = data.copy();
            tag.putInt("x", at.getX()); tag.putInt("y", at.getY()); tag.putInt("z", at.getZ());
            BlockEntity entity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
            if (entity == null) throw new IllegalStateException("Could not restore block entity at " + at);
            level.setBlockEntity(entity); entity.setChanged();
        });
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(origin.offset(p), ship.state(p).getBlock());
    }
}
