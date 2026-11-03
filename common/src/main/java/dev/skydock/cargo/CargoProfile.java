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

    /** Rank is earned by total reputation and never lost by spending. */
    public int peakRank() { return CargoRanks.forReputation(reputation).level(); }
    public int balance() { return reputation - spent; }

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
        return tag;
    }
    public static CargoProfile load(CompoundTag tag) {
        CargoProfile p = new CargoProfile(); p.reputation = tag.getInt("Reputation"); p.spent = tag.getInt("Spent");
        for (Tag t : tag.getList("Boards", Tag.TAG_COMPOUND)) {
            CompoundTag board = (CompoundTag) t; GlobalPos pos = Contract.loadPos(board.get("Pos"));
            List<Offer> list = new ArrayList<>(); for (Tag o : board.getList("Offers", Tag.TAG_COMPOUND)) list.add(Offer.load((CompoundTag) o));
            if (!list.isEmpty() || !board.contains("Refreshed")) p.offers.put(pos, list);
            if (board.contains("Refreshed")) p.offersRefreshed.put(pos, board.getLong("Refreshed"));
        }
        for (Tag t : tag.getList("Contracts", Tag.TAG_COMPOUND)) { Contract c = Contract.load((CompoundTag) t); p.contracts.put(c.id, c); }
        for (Tag t : tag.getList("Unlocked", Tag.TAG_STRING)) p.unlocked.add(ResourceLocation.parse(t.getAsString()));
        for (Tag t : tag.getList("Pending", Tag.TAG_STRING)) p.pendingCrates.add(CrateRank.valueOf(t.getAsString()));
        return p;
    }
}
