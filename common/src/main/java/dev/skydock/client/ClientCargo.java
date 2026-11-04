package dev.skydock.client;

import dev.skydock.cargo.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.List;

/** The local player's cargo profile, rank ladder, shop stock and contract destinations as last synced by the server. */
public final class ClientCargo {
    /** A shop entry in server order (its index is what {@link CargoNetwork#BUY} sends) and, for a pattern, the pattern's name key or text. */
    public record Listing(int index, ShopEntry entry, String patternName) {}
    private static CargoProfile profile = new CargoProfile();
    private static List<Listing> shop = List.of();
    private static List<CargoNetwork.Destination> destinations = List.of();
    private static List<Rank> ranks = CargoRanks.defaults();
    private ClientCargo() {}

    public static CargoProfile profile() { return profile; }
    public static List<CargoNetwork.Destination> destinations() { return destinations; }
    public static List<Rank> ranks() { return ranks; }
    public static List<Listing> shop() { return shop; }

    /** The highest synced rank the reputation reaches. */
    public static Rank rank(int reputation) { Rank best = ranks.getFirst(); for (Rank r : ranks) if (r.threshold() <= reputation) best = r; return best; }
    public static Rank byLevel(int level) { for (Rank r : ranks) if (r.level() == level) return r; return ranks.getFirst(); }

    public static void receive(CargoNetwork.Sync sync) {
        CompoundTag tag = sync.profile();
        List<Rank> ladder = new ArrayList<>(); for (Tag t : tag.getList("Ranks", Tag.TAG_COMPOUND)) ladder.add(Rank.load((CompoundTag) t));
        List<Listing> stock = new ArrayList<>(); ListTag lines = tag.getList("Shop", Tag.TAG_COMPOUND);
        for (int i = 0; i < lines.size(); i++) { CompoundTag line = lines.getCompound(i); ShopEntry e = ShopEntry.load(line); stock.add(new Listing(i, e, line.contains("Name") ? line.getString("Name") : e.pattern() == null ? "" : e.pattern().toString())); }
        shop = List.copyOf(stock);
        profile = CargoProfile.load(tag); destinations = List.copyOf(sync.destinations()); ranks = ladder.isEmpty() ? CargoRanks.defaults() : List.copyOf(ladder);
    }
}
