package dev.skydock.ship;

import dev.skydock.Skydock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import dev.skydock.data.DockFrame;
import dev.skydock.data.DockTier;
import java.util.*;

public final class ShipSavedData extends SavedData {
    public final Map<UUID, Ship> ships = new LinkedHashMap<>();
    /** Ship records that threw on load, kept exactly as saved and written back, so one bad record neither costs the rest nor is destroyed. */
    public final List<CompoundTag> quarantined = new ArrayList<>();
    public int nextRegion;
    public static ShipSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(ShipSavedData::new, ShipSavedData::load, null), "skydock_ships");
    }
    /** The berth a quarantined record was launched from; a null dimension (unreadable) stands for every dimension. */
    record Berth(ResourceKey<Level> dimension, BlockPos dock) {
        boolean is(ResourceKey<Level> where, BlockPos at) { return (dimension == null || dimension.equals(where)) && dock.equals(at); }
    }
    /** Reads a raw ship record's berth without trusting it: null when it names no dock. */
    static Berth berthOf(CompoundTag saved) {
        if (!saved.contains("Dock", Tag.TAG_LONG)) return null;
        ResourceLocation dimension = saved.contains("Dimension", Tag.TAG_STRING) ? ResourceLocation.tryParse(saved.getString("Dimension")) : null;
        return new Berth(dimension == null ? null : ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(saved.getLong("Dock")));
    }
    /** Whether the berth of {@code dock} in {@code dimension} is held by a ship other than {@code except}, or by a quarantined record, which keeps it until an operator restores the ship. */
    public boolean reserves(ResourceKey<Level> dimension, BlockPos dock, UUID except) {
        for (Ship ship : ships.values()) if (!ship.id.equals(except) && ship.dimension.equals(dimension) && dock.equals(ship.dock)) return true;
        for (CompoundTag saved : quarantined) { Berth berth = berthOf(saved); if (berth != null && berth.is(dimension, dock)) return true; }
        return false;
    }
    /**
     * The berth of a transfer that has not finished. A null dimension, a null origin (no envelope known), or several rotations stand
     * for what a quarantined record could not tell; each widens what the transfer guards rather than narrowing it.
     */
    record Transfer(ResourceKey<Level> dimension, BlockPos dock, BlockPos origin, List<Rotation> rotations, DockTier tier) {
        private boolean in(ResourceKey<Level> where) { return dimension == null || dimension.equals(where); }
        /** Whether world {@code pos} lies in this transfer's berth envelope. */
        boolean protects(ResourceKey<Level> where, BlockPos pos) {
            if (!in(where) || origin == null) return false;
            for (Rotation rotation : rotations) if (ShipTransfer.inBerth(origin, rotation, tier, pos)) return true;
            return false;
        }
        /** Whether this transfer targets {@code dock} or its envelope overlaps that dock's berth {@code envelope}. */
        boolean holds(ResourceKey<Level> where, BlockPos dock, AABB envelope) {
            if (!in(where)) return false;
            if (dock.equals(this.dock)) return true;
            if (origin != null) for (Rotation rotation : rotations) if (ShipTransfer.berthBox(origin, rotation, tier).intersects(envelope)) return true;
            return false;
        }
    }
    /** Whether a raw record is in the middle of a transfer: it names a transfer berth, or its phase is anything but "active". */
    static boolean midTransfer(CompoundTag saved) { return saved.contains("TransferOrigin", Tag.TAG_LONG) || !"active".equals(saved.getString("Phase")); }
    /** Reads a raw record's unfinished transfer without trusting it: null when it is not mid-transfer or names no berth at all. */
    static Transfer transferOf(CompoundTag saved) {
        if (!midTransfer(saved)) return null;
        BlockPos origin = saved.contains("TransferOrigin", Tag.TAG_LONG) ? BlockPos.of(saved.getLong("TransferOrigin")) : null;
        BlockPos dock = saved.contains("TransferDock", Tag.TAG_LONG) ? BlockPos.of(saved.getLong("TransferDock")) : null;
        if (origin == null && dock == null) return null;
        ResourceLocation dimension = saved.contains("TransferDimension", Tag.TAG_STRING) ? ResourceLocation.tryParse(saved.getString("TransferDimension")) : null;
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.byName(saved.getString("TransferFacing"));
        List<Rotation> rotations = facing == null || facing.getAxis().isVertical() ? List.of(Rotation.values()) : List.of(DockFrame.rotation(facing));
        DockTier tier = DockTier.DREADNOUGHT;
        try { tier = DockTier.valueOf(saved.getString("Tier")); } catch (IllegalArgumentException e) { /* the largest tier covers any hull */ }
        return new Transfer(dimension == null ? null : ResourceKey.create(Registries.DIMENSION, dimension), dock, origin, rotations, tier);
    }
    /** Every transfer that has not finished: loaded ships out of the "active" phase, and quarantined records that were mid-transfer. */
    List<Transfer> transfers() {
        List<Transfer> transfers = new ArrayList<>();
        for (Ship ship : ships.values()) if (!ship.phase.equals("active") && ship.transferOrigin != null)
            transfers.add(new Transfer(ship.transferDimension, ship.transferDock, ship.transferOrigin, List.of(DockFrame.rotation(ship.transferFacing)), ship.tier));
        for (CompoundTag saved : quarantined) { Transfer transfer = transferOf(saved); if (transfer != null) transfers.add(transfer); }
        return transfers;
    }
    /** Whether an unfinished transfer targets {@code dock} in {@code dimension} or overlaps its berth {@code envelope}; a launch or assembly there would duplicate that hull. */
    public boolean transferHolds(ResourceKey<Level> dimension, BlockPos dock, AABB envelope) {
        for (Transfer transfer : transfers()) if (transfer.holds(dimension, dock, envelope)) return true;
        return false;
    }
    /** Whether {@code pos} in {@code dimension} lies in the berth of an unfinished transfer. */
    public boolean transferProtects(ResourceKey<Level> dimension, BlockPos pos) {
        for (Transfer transfer : transfers()) if (transfer.protects(dimension, pos)) return true;
        return false;
    }
    /** The shipyard corner of region {@code region}: regions are 256-block squares, 1,000 to a row. */
    static BlockPos yardOf(int region) { return new BlockPos((region % 1000) * 256, 64, (region / 1000) * 256); }
    static int regionOf(BlockPos yard) { return Math.floorDiv(yard.getZ(), 256) * 1000 + Math.floorDiv(yard.getX(), 256); }
    /** Loads each ship on its own. {@code nextRegion} never falls to a region a loaded or quarantined ship holds. */
    static ShipSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShipSavedData data = new ShipSavedData(); int highest = -1;
        for (Tag entry : tag.getList("Ships", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            // any numeric tag, as getLong (and so Ship.loadPersisted) reads one, so a ship whose Yard was saved as another number still counts
            if (saved.contains("Yard", Tag.TAG_ANY_NUMERIC)) highest = Math.max(highest, regionOf(BlockPos.of(saved.getLong("Yard"))));
            try {
                Ship ship = Ship.loadPersisted(saved, registries);
                if (!saved.contains("PivotX", Tag.TAG_DOUBLE) || !saved.contains("PivotZ", Tag.TAG_DOUBLE)
                        || ship.revision != saved.getInt("Revision")) data.setDirty();
                ship.pilot = null; data.ships.put(ship.id, ship);
            } catch (RuntimeException e) {
                Skydock.LOGGER.error("Ship {} could not be loaded; its record is kept as saved and the ship stays out of the world", saved.hasUUID("Id") ? saved.getUUID("Id") : "(no id)", e);
                data.quarantined.add(quarantine(saved));
            }
        }
        data.nextRegion = Math.max(tag.getInt("NextRegion"), highest + 1);
        if (data.nextRegion != tag.getInt("NextRegion")) data.setDirty();
        return data;
    }
    /**
     * The record kept for a ship that failed to load. One caught mid-transfer is marked held: while it was out of the world its berth or
     * yard may have moved on, so if it loads again later it must wait for an operator instead of being replayed from a stale journal.
     */
    static CompoundTag quarantine(CompoundTag saved) {
        CompoundTag kept = saved.copy();
        if (midTransfer(saved)) kept.putBoolean(Ship.HELD, true);
        return kept;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return save(tag, registries, null, null); }
    /** Saves with ship {@code id}'s record replaced by {@code record}, or left out when that is null: the journal of a phase not yet applied in memory. */
    CompoundTag save(CompoundTag tag, HolderLookup.Provider registries, UUID id, CompoundTag record) {
        tag.putInt("NextRegion", nextRegion);
        ListTag shipsTag = new ListTag();
        ships.values().forEach(s -> { if (!s.id.equals(id)) shipsTag.add(s.save(registries, true)); else if (record != null) shipsTag.add(record); });
        quarantined.forEach(saved -> shipsTag.add(saved.copy()));
        tag.put("Ships", shipsTag); return tag;
    }
}
