package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-wide cargo state: terminals, retired terminal positions, every player's profile, the shared wind and abandoned contract ids. */
public final class CargoSavedData extends SavedData {
    public record Terminal(GlobalPos pos, BlockPos pad, boolean generated) {}
    public record Wind(double angle, double speed, long changedAt) {}

    public static final long WIND_PERIOD = 6_000;

    public final Map<GlobalPos, Terminal> terminals = new LinkedHashMap<>();
    public final Map<UUID, CargoProfile> profiles = new LinkedHashMap<>();
    public Wind wind = new Wind(0, 0, 0);
    public final Set<Long> voided = new TreeSet<>();
    /** Where terminals that generated with an outpost were destroyed; their outposts' structure starts remain, so no site is predicted near one again. */
    public final Set<GlobalPos> retired = new LinkedHashSet<>();

    public static CargoSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CargoSavedData::new, CargoSavedData::load, null), "skydock_cargo");
    }
    /** Records a generated terminal, dropping any predicted site within 64 blocks horizontally in the same dimension; site walks are walked afresh. */
    public void registerTerminal(GlobalPos pos, BlockPos pad) {
        terminals.values().removeIf(t -> !t.generated() && t.pos().dimension().equals(pos.dimension()) && Offers.distance(t.pos().pos(), pos.pos()) <= 64);
        terminals.put(pos, new Terminal(pos, pad, true)); setDirty(); TerminalSites.CACHE.clear();
    }
    /** Registers a generated terminal that is missing from the registry (or recorded only as a predicted site), un-retiring its position; true when it did. */
    public boolean ensureTerminal(GlobalPos pos, BlockPos pad) {
        Terminal known = terminals.get(pos); if (known != null && known.generated()) return false;
        retired.remove(pos); registerTerminal(pos, pad); return true;
    }
    /**
     * Drops the terminal and every board shown at it. One that generated with an outpost ({@code outpost}) is retired, taking any predicted
     * site near it along, so contracts bound for it fail rather than follow a prediction of the outpost that is already there. A terminal
     * placed by hand is not retired, so a real outpost near it is still predicted.
     */
    public void removeTerminal(GlobalPos pos, boolean outpost) {
        Terminal removed = terminals.remove(pos); if (removed == null) return;
        if (removed.generated() && outpost) { retired.add(pos); terminals.values().removeIf(t -> !t.generated() && TerminalSites.retired(List.of(pos), t.pos())); }
        for (CargoProfile p : profiles.values()) { p.offers.remove(pos); p.offersRefreshed.remove(pos); }
        setDirty(); TerminalSites.CACHE.clear();
    }
    /** Up to {@code limit} terminals nearest {@code from} horizontally, those in its dimension first. */
    public static List<Terminal> nearest(Collection<Terminal> terminals, GlobalPos from, int limit) {
        return terminals.stream().sorted(Comparator.<Terminal>comparingInt(t -> t.pos().dimension().equals(from.dimension()) ? 0 : 1)
                .thenComparingDouble(t -> Offers.distance(t.pos().pos(), from.pos()))).limit(limit).toList();
    }
    /** The player's profile, created and saved if new: for changes to cargo state. */
    public CargoProfile profile(UUID id) { return profiles.computeIfAbsent(id, k -> { setDirty(); return new CargoProfile(); }); }
    /** The player's profile, or a fresh empty one that is not stored: for reads, so that merely joining never grows the save. */
    public CargoProfile peek(UUID id) { CargoProfile p = profiles.get(id); return p == null ? new CargoProfile() : p; }
    /** Stores a profile from {@link #peek} once it holds a change. */
    public void keep(UUID id, CargoProfile p) { if (profiles.putIfAbsent(id, p) == null) setDirty(); }
    /**
     * A new contract or offer id: a random positive long that no held contract, offer on a board or voided id uses. A counter saved only
     * at autosave would hand out old ids again after a crash, matching old pods to a new contract. Ids saved before this load as they are.
     */
    public long newId(java.util.function.LongSupplier random) {
        while (true) {
            long id = random.getAsLong() & Long.MAX_VALUE; // positive, like the counter's ids; the client uses -1 as "none"
            if (id != 0 && !voided.contains(id) && findContract(id) == null
                    && profiles.values().stream().noneMatch(p -> p.offers.values().stream().anyMatch(board -> board.stream().anyMatch(o -> o.id() == id)))) return id;
        }
    }
    /** The contract with this id and the player holding it, or null. */
    public Map.Entry<UUID, Contract> findContract(long id) {
        for (Map.Entry<UUID, CargoProfile> e : profiles.entrySet()) { Contract c = e.getValue().contracts.get(id); if (c != null) return Map.entry(e.getKey(), c); }
        return null;
    }
    /** The wind holds for 6,000 ticks, then turns to a uniform new angle and a speed of 0 to 2 blocks per second. */
    public static Wind nextWind(Wind wind, long now, net.minecraft.util.RandomSource random) {
        boolean fresh = wind.changedAt() == 0 && wind.speed() == 0;
        if (!fresh && now >= wind.changedAt() && now - wind.changedAt() < WIND_PERIOD) return wind;
        return new Wind(random.nextDouble() * Math.PI * 2, random.nextDouble() * 2, now);
    }
    public static void tickWind(MinecraftServer server) {
        CargoSavedData data = get(server); Wind next = nextWind(data.wind, server.overworld().getGameTime(), server.overworld().getRandom());
        if (next != data.wind) { data.wind = next; data.setDirty(); }
    }

    /** Loads every entry on its own: one that cannot be read is logged and skipped, so it never costs the rest of the file. */
    static CargoSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CargoSavedData data = new CargoSavedData();
        each(tag.getList("Terminals", Tag.TAG_COMPOUND), "terminal", saved -> {
            GlobalPos pos = Contract.loadPos(saved.get("Pos"));
            data.terminals.put(pos, new Terminal(pos, BlockPos.of(saved.getLong("Pad")), saved.getBoolean("Generated")));
        });
        each(tag.getList("Retired", Tag.TAG_COMPOUND), "retired terminal", saved -> data.retired.add(Contract.loadPos(saved)));
        each(tag.getList("Profiles", Tag.TAG_COMPOUND), "profile", saved -> data.profiles.put(saved.getUUID("Player"), CargoProfile.load(saved.getCompound("Profile"))));
        // Boards at terminals that are gone would only be synced for nothing.
        for (CargoProfile p : data.profiles.values()) { p.offers.keySet().retainAll(data.terminals.keySet()); p.offersRefreshed.keySet().retainAll(data.terminals.keySet()); }
        CompoundTag w = tag.getCompound("Wind"); data.wind = new Wind(w.getDouble("Angle"), w.getDouble("Speed"), w.getLong("ChangedAt"));
        for (long id : tag.getLongArray("Voided")) data.voided.add(id);
        return data;
    }
    /** Runs {@code load} on each compound in the list, logging and skipping any that throws. */
    static void each(ListTag list, String what, java.util.function.Consumer<CompoundTag> load) {
        for (Tag t : list) {
            try { load.accept((CompoundTag) t); }
            catch (RuntimeException ex) { dev.skydock.Skydock.LOGGER.warn("Skipping unreadable cargo {}: {}", what, t, ex); }
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag terms = new ListTag();
        terminals.values().forEach(t -> { CompoundTag c = new CompoundTag(); c.put("Pos", Contract.savePos(t.pos())); c.putLong("Pad", t.pad().asLong()); c.putBoolean("Generated", t.generated()); terms.add(c); });
        tag.put("Terminals", terms);
        ListTag gone = new ListTag(); retired.forEach(r -> gone.add(Contract.savePos(r))); tag.put("Retired", gone);
        ListTag profs = new ListTag();
        profiles.forEach((id, p) -> { CompoundTag c = new CompoundTag(); c.putUUID("Player", id); c.put("Profile", p.save()); profs.add(c); });
        tag.put("Profiles", profs);
        CompoundTag w = new CompoundTag(); w.putDouble("Angle", wind.angle()); w.putDouble("Speed", wind.speed()); w.putLong("ChangedAt", wind.changedAt()); tag.put("Wind", w);
        tag.putLongArray("Voided", voided.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
}
