package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** One player's cargo career: reputation, what they have spent, open offers, held contracts and unlocked patterns. */
public final class CargoProfile {
    public int reputation, spent;
    public final Map<GlobalPos, List<Offer>> offers = new LinkedHashMap<>();
    public final Map<GlobalPos, Long> offersRefreshed = new LinkedHashMap<>();
    public final Map<Long, Contract> contracts = new LinkedHashMap<>();
    public final Set<ResourceLocation> unlocked = new LinkedHashSet<>();
    /** Completion crates earned while away (a stranger settled the contract), handed out at the next terminal visit. */
    public final List<CrateRank> pendingCrates = new ArrayList<>();
    /** Cargo names of contracts that expired while away, told at the next terminal visit. */
    public final List<String> expired = new ArrayList<>();

    /** Rank is earned by total reputation and never lost by spending. */
    public int peakRank() { return CargoRanks.forReputation(reputation).level(); }
    public int balance() { return reputation - spent; }
    /** Adds reputation, stopping at {@link Integer#MAX_VALUE} rather than overflowing. */
    public void gain(int amount) { reputation = saturatedAdd(reputation, amount); }
    static int saturatedAdd(int a, int b) { return (int) Math.clamp((long) a + b, Integer.MIN_VALUE, Integer.MAX_VALUE); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Reputation", reputation); tag.putInt("Spent", spent);
        ListTag boards = new ListTag();
        offers.forEach((pos, list) -> {
            CompoundTag board = new CompoundTag(); board.put("Pos", Contract.savePos(pos));
            ListTag entries = new ListTag(); list.forEach(o -> entries.add(o.save())); board.put("Offers", entries);
            Long refreshed = offersRefreshed.get(pos); if (refreshed != null) board.putLong("Refreshed", refreshed);
            boards.add(board);
        });
        offersRefreshed.forEach((pos, time) -> { if (!offers.containsKey(pos)) { CompoundTag board = new CompoundTag(); board.put("Pos", Contract.savePos(pos)); board.put("Offers", new ListTag()); board.putLong("Refreshed", time); boards.add(board); } });
        tag.put("Boards", boards);
        ListTag held = new ListTag(); contracts.values().forEach(c -> held.add(c.save())); tag.put("Contracts", held);
        ListTag patterns = new ListTag(); unlocked.forEach(p -> patterns.add(StringTag.valueOf(p.toString()))); tag.put("Unlocked", patterns);
        ListTag pending = new ListTag(); pendingCrates.forEach(c -> pending.add(StringTag.valueOf(c.name()))); tag.put("Pending", pending);
        ListTag gone = new ListTag(); expired.forEach(k -> gone.add(StringTag.valueOf(k))); tag.put("Expired", gone);
        return tag;
    }
    /** Each board, offer, contract, pattern and crate loads on its own; one that cannot be read is logged and skipped. Empty boards are kept with their search time. */
    public static CargoProfile load(CompoundTag tag) {
        CargoProfile p = new CargoProfile(); p.reputation = tag.getInt("Reputation"); p.spent = tag.getInt("Spent");
        CargoSavedData.each(tag.getList("Boards", Tag.TAG_COMPOUND), "board", board -> {
            GlobalPos pos = Contract.loadPos(board.get("Pos"));
            List<Offer> list = new ArrayList<>(); CargoSavedData.each(board.getList("Offers", Tag.TAG_COMPOUND), "offer", o -> list.add(Offer.load(o)));
            p.offers.put(pos, list);
            if (board.contains("Refreshed")) p.offersRefreshed.put(pos, board.getLong("Refreshed"));
        });
        CargoSavedData.each(tag.getList("Contracts", Tag.TAG_COMPOUND), "contract", saved -> { Contract c = Contract.load(saved); p.contracts.put(c.id, c); });
        for (Tag t : tag.getList("Unlocked", Tag.TAG_STRING)) strings(t, "pattern", s -> p.unlocked.add(ResourceLocation.parse(s)));
        for (Tag t : tag.getList("Pending", Tag.TAG_STRING)) strings(t, "crate", s -> p.pendingCrates.add(CrateRank.valueOf(s)));
        for (Tag t : tag.getList("Expired", Tag.TAG_STRING)) p.expired.add(t.getAsString());
        return p;
    }
    private static void strings(Tag t, String what, java.util.function.Consumer<String> load) {
        try { load.accept(t.getAsString()); }
        catch (RuntimeException ex) { dev.skydock.Skydock.LOGGER.warn("Skipping unreadable cargo {}: {}", what, t, ex); }
    }
}
