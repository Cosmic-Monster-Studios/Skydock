package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-wide cargo state: terminals, every player's profile, the shared wind and abandoned contract ids. */
public final class CargoSavedData extends SavedData {
    public record Terminal(GlobalPos pos, BlockPos pad, boolean generated) {}
    public record Wind(double angle, double speed, long changedAt) {}

    public final Map<GlobalPos, Terminal> terminals = new LinkedHashMap<>();
    public final Map<UUID, CargoProfile> profiles = new LinkedHashMap<>();
    public Wind wind = new Wind(0, 0, 0);
    public long nextContract = 1;
    public final Set<Long> voided = new TreeSet<>();

    public static CargoSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CargoSavedData::new, CargoSavedData::load, null), "skydock_cargo");
    }
    public CargoProfile profile(UUID id) { return profiles.computeIfAbsent(id, k -> { setDirty(); return new CargoProfile(); }); }
    /** The contract with this id and the player holding it, or null. */
    public Map.Entry<UUID, Contract> findContract(long id) {
        for (Map.Entry<UUID, CargoProfile> e : profiles.entrySet()) { Contract c = e.getValue().contracts.get(id); if (c != null) return Map.entry(e.getKey(), c); }
        return null;
    }

    private static CargoSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CargoSavedData data = new CargoSavedData(); data.nextContract = tag.contains("NextContract") ? tag.getLong("NextContract") : 1;
        for (Tag t : tag.getList("Terminals", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) t; GlobalPos pos = Contract.loadPos(saved.get("Pos"));
            data.terminals.put(pos, new Terminal(pos, BlockPos.of(saved.getLong("Pad")), saved.getBoolean("Generated")));
        }
        for (Tag t : tag.getList("Profiles", Tag.TAG_COMPOUND)) { CompoundTag saved = (CompoundTag) t; data.profiles.put(saved.getUUID("Player"), CargoProfile.load(saved.getCompound("Profile"))); }
        CompoundTag w = tag.getCompound("Wind"); data.wind = new Wind(w.getDouble("Angle"), w.getDouble("Speed"), w.getLong("ChangedAt"));
        for (long id : tag.getLongArray("Voided")) data.voided.add(id);
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextContract", nextContract);
        ListTag terms = new ListTag();
        terminals.values().forEach(t -> { CompoundTag c = new CompoundTag(); c.put("Pos", Contract.savePos(t.pos())); c.putLong("Pad", t.pad().asLong()); c.putBoolean("Generated", t.generated()); terms.add(c); });
        tag.put("Terminals", terms);
        ListTag profs = new ListTag();
        profiles.forEach((id, p) -> { CompoundTag c = new CompoundTag(); c.putUUID("Player", id); c.put("Profile", p.save()); profs.add(c); });
        tag.put("Profiles", profs);
        CompoundTag w = new CompoundTag(); w.putDouble("Angle", wind.angle()); w.putDouble("Speed", wind.speed()); w.putLong("ChangedAt", wind.changedAt()); tag.put("Wind", w);
        tag.putLongArray("Voided", voided.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
}
