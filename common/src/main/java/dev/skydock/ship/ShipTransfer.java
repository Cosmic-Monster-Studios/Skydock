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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Journal is flushed before either world is edited, marked after each side is saved, and advanced only once both are. Interrupted transfers can be replayed. */
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
    /** The block box of that envelope, the same cells {@link #inBerth} accepts. */
    static AABB berthBox(BlockPos origin, Rotation rotation, DockTier tier) {
        return AABB.encapsulatingFullBlocks(origin, DockFrame.toWorld(origin, rotation, new BlockPos(tier.width - 1, tier.height - 1, tier.length - 1)));
    }
    /** One transfer phase: paste the hull where it goes, clear it where it was, note each step in memory as it begins and in the journal once it is saved, write the next phase to the journal, then apply it in memory. */
    interface Steps { void begin(Ship.Step step); void paste(); void clear(); void save(boolean pasted); void mark(Ship.Step step); void journal(); void apply(); }
    /** Which copy of the hull a retry reads: the berth, the shipyard, or each cell from the berth where the hull block stands there and from the shipyard elsewhere. */
    enum Source { BERTH, YARD, EITHER }
    /**
     * Which copy of the hull a retry reads, chosen from the transfer's step, never from what a cell happens to hold, except in a redock
     * cut short in its paste. The berth is the live copy whenever it holds the hull: a launch until its berth clear begins, a redock once
     * its paste there is saved. Otherwise the shipyard copy is: a launch's once the berth clear has begun, a redock's until it has begun
     * pasting. The other copy is either being cleared (a dispenser may have put a block into a cleared berth cell; it is not hull) or an
     * isolated paste that live hoppers, menus or the outside half of a double chest may have drained the berth copy past, so reading it
     * would duplicate items. A redock cut short in its paste has part of the hull in the exposed berth and the rest only in the yard, so
     * each cell is read from the berth where the hull block stands there, else from the yard. A cleared side is never read.
     */
    static Source source(boolean launching, Ship.Step done) {
        if (launching) return done.compareTo(Ship.Step.CLEARING) < 0 ? Source.BERTH : Source.YARD;
        return done == Ship.Step.STARTED ? Source.YARD : done == Ship.Step.PASTING ? Source.EITHER : Source.BERTH;
    }
    /** Whether a phase still has to paste: not once its destination is the copy {@link #source} reads whole, since pasting that over itself changes nothing but its live states. */
    static boolean pastes(boolean launching, Ship.Step done) { return done.compareTo(launching ? Ship.Step.CLEARING : Ship.Step.PASTED) < 0; }
    /**
     * Ordering invariant: the journal never advances past a step whose world writes are not durable. Each step is noted in memory as
     * it begins, so a retry after a pause knows it was cut short and its journal write records that. Each side is saved before its done
     * marker is written and before the next step, and the journal is written before memory changes, so a crash anywhere replays the
     * phase from its last marker, and a journal write that throws leaves the ship in its old phase, where the pause and retry path picks
     * it up. A replay skips the steps its marker says are done, and runs again from the start a step that was begun but not done.
     */
    static void run(Steps steps, boolean launching, Ship.Step done) {
        if (pastes(launching, done)) { steps.begin(Ship.Step.PASTING); steps.paste(); steps.save(true); steps.mark(Ship.Step.PASTED); }
        if (done != Ship.Step.CLEARED) { steps.begin(Ship.Step.CLEARING); steps.clear(); steps.save(false); steps.mark(Ship.Step.CLEARED); }
        steps.journal(); steps.apply();
    }
    /** Whether saved block-entity data is only its id and position, as a block entity whose stored data failed to load saves, and as a vanilla shulker box saves once emptied. */
    static boolean bare(CompoundTag data) {
        for (String key : data.getAllKeys()) if (!key.equals("id") && !key.equals("x") && !key.equals("y") && !key.equals("z")) return false;
        return true;
    }
    /**
     * Re-reads the block entities a retried transfer will paste, cell by cell, from the copy {@link #source} picks: between a pause and
     * its retry that copy stays live, and pasting the snapshot taken before the pause would hand back what already left it. A cell whose
     * hull block no longer stands there keeps the journal copy. In a record that was quarantined mid-transfer ({@code quarantined}), so
     * does one whose live block entity is bare (see {@link #bare}) while the journal copy has content: that is typically a default
     * standing in for data that failed to load. In every other retry the live entity wins, since an emptied shulker box is bare too. Run
     * it before the journal write that precedes a retry, so a crash after the clear cannot replay the stale copy.
     */
    static Map<BlockPos, CompoundTag> refreshed(Collection<BlockPos> cells, Map<BlockPos, CompoundTag> journal, Function<BlockPos, CompoundTag> live, boolean quarantined) {
        Map<BlockPos, CompoundTag> fresh = new HashMap<>();
        for (BlockPos p : cells) {
            CompoundTag data = live.apply(p), kept = journal.get(p);
            if (data == null || quarantined && bare(data) && kept != null && !bare(kept)) data = kept;
            if (data != null) fresh.put(p, data);
        }
        return fresh;
    }
    /** How {@link #refreshed} reads a cell from {@code source}: {@code berthStands} says whether the hull block stands in the berth cell, which {@link Source#EITHER} reads then. */
    static Function<BlockPos, CompoundTag> live(Source source, Function<BlockPos, CompoundTag> berth, Predicate<BlockPos> berthStands, Function<BlockPos, CompoundTag> yard) {
        return switch (source) {
            case BERTH -> berth;
            case YARD -> yard;
            case EITHER -> p -> berthStands.test(p) ? berth.apply(p) : yard.apply(p);
        };
    }
    /** Applies {@link #refreshed} to a ship with an unfinished transfer, reading the copy of the hull its step names from the live world. */
    public static void resnapshot(MinecraftServer server, Ship ship) {
        boolean launching = ship.phase.equals("launching");
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD), berth = ship.transferDimension == null ? null : server.getLevel(ship.transferDimension);
        if (yard == null || berth == null || ship.transferOrigin == null || !launching && !ship.phase.equals("redocking")) return;
        Rotation rotation = DockFrame.rotation(ship.transferFacing);
        Function<BlockPos, BlockPos> inBerth = p -> DockFrame.toWorld(ship.transferOrigin, rotation, p);
        Function<BlockPos, CompoundTag> live = live(source(launching, ship.transferStep), p -> standing(ship, berth, inBerth.apply(p), p),
                p -> stands(ship, berth, inBerth.apply(p), p), p -> standing(ship, yard, ship.yard.offset(p), p));
        Map<BlockPos, CompoundTag> fresh = refreshed(ship.blocks.keySet(), ship.blockEntities, live, ship.transferQuarantined);
        ship.blockEntities.clear(); ship.blockEntities.putAll(fresh);
    }
    /** Whether hull cell {@code p}'s block stands at {@code at} (its properties may have changed, as a furnace's do). */
    private static boolean stands(Ship ship, ServerLevel level, BlockPos at, BlockPos p) {
        BlockState expected = ship.state(p);
        return !expected.isAir() && level.getBlockState(at).is(expected.getBlock());
    }
    /** The block entity at {@code at} if hull cell {@code p}'s block still stands there (its properties may have changed, as a furnace's do), else null. */
    private static CompoundTag standing(Ship ship, ServerLevel level, BlockPos at, BlockPos p) {
        if (!stands(ship, level, at, p)) return null;
        BlockEntity entity = level.getBlockEntity(at);
        return entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
    }
    /**
     * Ends a redock in memory once its journal has dropped the ship. Nothing is pending after that, so a ticket release that throws is
     * logged rather than passed on, where it would report a finished redock as paused.
     */
    static void finishRedock(Map<UUID, Ship> ships, Ship ship, Runnable releaseTickets) {
        ships.remove(ship.id);
        try { releaseTickets.run(); }
        catch (RuntimeException e) { Skydock.LOGGER.error("Ship {} redocked, but its chunk tickets could not be released", ship.id, e); }
    }
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
                // In memory only: the next journal write, a retry's or an autosave's, records that this step was begun.
                public void begin(Ship.Step step) { ship.transferStep = step; }
                public void paste() { if (launching) ShipTransfer.paste(ship, yard, ship.yard, Rotation.NONE); else ShipTransfer.paste(ship, destination, berthOrigin, berth); }
                public void clear() { if (launching) ShipTransfer.clear(ship, destination, berthOrigin, berth); else ShipTransfer.clear(ship, yard, ship.yard, Rotation.NONE); }
                public void save(boolean pasted) { if (pasted == launching) saveChunks(yard, yardChunks); else saveChunks(destination, berthChunks); }
                // Memory takes the marker first: the step it names is already on disk, so a retry after a failed write still reads the right side.
                public void mark(Ship.Step step) { ship.transferStep = step; flush(server); }
                // The journal gets the next phase computed from the ship as it stands; memory changes only once that write is on disk.
                public void journal() { flush(server, ship.id, launching ? ship.saveLaunched(server.registryAccess()) : null); }
                public void apply() {
                    if (launching) ship.finishLaunch();
                    else finishRedock(ShipSavedData.get(server).ships, ship, () -> ShipManager.releaseTickets(server, ship));
                }
            }, launching, ship.transferStep);
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
    /**
     * Hull states are stored north-frame and turned by {@code rotation} on the way into a berth. Each cell gets its block and its block
     * entity together, the entity built from the state that was placed, and a cell whose entity cannot be set goes back as it was if it
     * already held the hull block (live contents and all), else to air: so a paste cut short leaves every cell where the hull block
     * stands with its contents, which a retry of a redock cut short reads.
     */
    private static void paste(Ship ship, ServerLevel level, BlockPos origin, Rotation rotation) {
        ship.blocks.forEach((p, stored) -> {
            BlockPos at = DockFrame.toWorld(origin, rotation, p); BlockState state = stored.rotate(rotation);
            CompoundTag data = ship.blockEntities.get(p);
            BlockState before = level.getBlockState(at); BlockEntity live = level.getBlockEntity(at);
            CompoundTag liveTag = live == null ? null : live.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(at); level.setBlock(at, state, FLAGS);
            if (data == null) return;
            try {
                CompoundTag tag = data.copy(); tag.putInt("x", at.getX()); tag.putInt("y", at.getY()); tag.putInt("z", at.getZ());
                BlockEntity entity = BlockEntity.loadStatic(at, level.getBlockState(at), tag, level.registryAccess());
                if (entity == null) throw new IllegalStateException("Could not restore block entity at " + at);
                level.setBlockEntity(entity); entity.setChanged();
            } catch (RuntimeException e) {
                level.removeBlockEntity(at);
                boolean held = before.is(state.getBlock());
                level.setBlock(at, held ? before : Blocks.AIR.defaultBlockState(), FLAGS);
                if (held && liveTag != null) {
                    BlockEntity back = BlockEntity.loadStatic(at, before, liveTag, level.registryAccess());
                    if (back != null) { level.setBlockEntity(back); back.setChanged(); }
                }
                throw e;
            }
        });
        for (BlockPos p : ship.blocks.keySet()) level.updateNeighborsAt(DockFrame.toWorld(origin, rotation, p), ship.state(p).getBlock());
    }
}
