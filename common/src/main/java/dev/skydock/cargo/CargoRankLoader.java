package dev.skydock.cargo;

import com.google.gson.*;
import dev.skydock.Skydock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Loads {@code data/<ns>/cargo/ranks.json}; an invalid file is logged and the defaults kept. */
public final class CargoRankLoader extends SimpleJsonResourceReloadListener {
    public CargoRankLoader() { super(new Gson(), "cargo"); }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<Rank> next = CargoRanks.defaults();
        JsonElement file = files.get(Skydock.id("ranks"));
        if (file != null) {
            try { next = parse(file.getAsJsonObject()); }
            catch (RuntimeException ex) { Skydock.LOGGER.error("Invalid Skydock cargo ranks", ex); }
        }
        CargoRanks.setData(next);
    }

    static List<Rank> parse(JsonObject json) {
        List<Rank> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("ranks")) {
            JsonObject r = e.getAsJsonObject();
            int max = r.has("max_distance") ? r.get("max_distance").getAsInt() : Integer.MAX_VALUE;
            Rank rank = new Rank(r.get("level").getAsInt(), r.get("key").getAsString(), r.get("threshold").getAsInt(),
                    r.get("min_distance").getAsInt(), max, r.get("deadline_days").getAsInt(),
                    CrateRank.valueOf(r.get("crate").getAsString().toUpperCase(Locale.ROOT)), r.get("base_per_pod").getAsInt());
            if (rank.minDistance() <= 0 || rank.basePerPod() < 0 || rank.deadlineDays() <= 0 || rank.threshold() < 0)
                throw new IllegalArgumentException("Invalid rank " + rank.key());
            if (!out.isEmpty() && (rank.threshold() <= out.getLast().threshold() || rank.minDistance() <= out.getLast().minDistance()))
                throw new IllegalArgumentException("Ranks must ascend: " + rank.key());
            out.add(rank);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No ranks");
        return out;
    }
}
