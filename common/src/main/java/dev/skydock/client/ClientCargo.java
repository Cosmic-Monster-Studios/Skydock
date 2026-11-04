package dev.skydock.client;

import dev.skydock.cargo.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.List;

/** The local player's cargo profile, rank ladder and contract destinations as last synced by the server. */
public final class ClientCargo {
    private static CargoProfile profile = new CargoProfile();
    private static List<CargoNetwork.Destination> destinations = List.of();
    private static List<Rank> ranks = CargoRanks.defaults();
    private ClientCargo() {}

    public static CargoProfile profile() { return profile; }
    public static List<CargoNetwork.Destination> destinations() { return destinations; }
    public static List<Rank> ranks() { return ranks; }

    /** The highest synced rank the reputation reaches. */
    public static Rank rank(int reputation) { Rank best = ranks.getFirst(); for (Rank r : ranks) if (r.threshold() <= reputation) best = r; return best; }
    public static Rank byLevel(int level) { for (Rank r : ranks) if (r.level() == level) return r; return ranks.getFirst(); }

    public static void receive(CargoNetwork.Sync sync) {
        CompoundTag tag = sync.profile();
        List<Rank> ladder = new ArrayList<>(); for (Tag t : tag.getList("Ranks", Tag.TAG_COMPOUND)) ladder.add(Rank.load((CompoundTag) t));
        profile = CargoProfile.load(tag); destinations = List.copyOf(sync.destinations()); ranks = ladder.isEmpty() ? CargoRanks.defaults() : List.copyOf(ladder);
    }
}
