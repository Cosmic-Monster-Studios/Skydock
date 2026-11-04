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
import net.minecraft.world.level.saveddata.SavedData;
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
    /** The shipyard corner of region {@code region}: regions are 256-block squares, 1,000 to a row. */
    static BlockPos yardOf(int region) { return new BlockPos((region % 1000) * 256, 64, (region / 1000) * 256); }
    static int regionOf(BlockPos yard) { return Math.floorDiv(yard.getZ(), 256) * 1000 + Math.floorDiv(yard.getX(), 256); }
    /** Loads each ship on its own. {@code nextRegion} never falls to a region a loaded or quarantined ship holds. */
    static ShipSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShipSavedData data = new ShipSavedData(); int highest = -1;
        for (Tag entry : tag.getList("Ships", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            if (saved.contains("Yard", Tag.TAG_LONG)) highest = Math.max(highest, regionOf(BlockPos.of(saved.getLong("Yard"))));
            try {
                Ship ship = Ship.loadPersisted(saved, registries);
                if (!saved.contains("PivotX", Tag.TAG_DOUBLE) || !saved.contains("PivotZ", Tag.TAG_DOUBLE)
                        || ship.revision != saved.getInt("Revision")) data.setDirty();
                ship.pilot = null; data.ships.put(ship.id, ship);
            } catch (RuntimeException e) {
                Skydock.LOGGER.error("Ship {} could not be loaded; its record is kept as saved and the ship stays out of the world", saved.hasUUID("Id") ? saved.getUUID("Id") : "(no id)", e);
                data.quarantined.add(saved.copy());
            }
        }
        data.nextRegion = Math.max(tag.getInt("NextRegion"), highest + 1);
        if (data.nextRegion != tag.getInt("NextRegion")) data.setDirty();
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextRegion", nextRegion);
        ListTag shipsTag = new ListTag(); ships.values().forEach(s -> shipsTag.add(s.save(registries, true)));
        quarantined.forEach(saved -> shipsTag.add(saved.copy()));
        tag.put("Ships", shipsTag); return tag;
    }
}
