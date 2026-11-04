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

    /** The largest values a rank may set, so the reward and search-radius maths stay far from int overflow. */
    static final long MAX_BASE_PER_POD = 1_000_000, MAX_DISTANCE = 30_000_000, MAX_THRESHOLD = 1_000_000_000, MAX_DEADLINE_DAYS = 365;

    /**
     * Levels must be exactly 1 to N in file order; thresholds and minimum distances must rise with them. Magnitudes are bounded:
     * {@code base_per_pod} to {@link #MAX_BASE_PER_POD}, distances to {@link #MAX_DISTANCE}, {@code threshold} to {@link #MAX_THRESHOLD},
     * {@code deadline_days} to {@link #MAX_DEADLINE_DAYS}. An absent {@code max_distance} is open-ended.
     */
    static List<Rank> parse(JsonObject json) {
        List<Rank> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("ranks")) {
            JsonObject r = e.getAsJsonObject(); String key = r.get("key").getAsString();
            int max = r.has("max_distance") ? bounded(r, "max_distance", 1, MAX_DISTANCE, key) : Integer.MAX_VALUE;
            Rank rank = new Rank(r.get("level").getAsInt(), key, bounded(r, "threshold", 0, MAX_THRESHOLD, key),
                    bounded(r, "min_distance", 1, MAX_DISTANCE, key), max, bounded(r, "deadline_days", 1, MAX_DEADLINE_DAYS, key),
                    CrateRank.valueOf(r.get("crate").getAsString().toUpperCase(Locale.ROOT)), bounded(r, "base_per_pod", 0, MAX_BASE_PER_POD, key));
            if (rank.maxDistance() < rank.minDistance()) throw new IllegalArgumentException("Invalid rank " + rank.key());
            if (rank.level() != out.size() + 1) throw new IllegalArgumentException("Rank levels must run 1, 2, 3... in order: " + rank.key());
            if (!out.isEmpty() && (rank.threshold() <= out.getLast().threshold() || rank.minDistance() <= out.getLast().minDistance()))
                throw new IllegalArgumentException("Ranks must ascend: " + rank.key());
            out.add(rank);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No ranks");
        return out;
    }
    /** A whole-number field read as a long (so a huge value cannot wrap into range) and refused outside {@code min..max}. */
    private static int bounded(JsonObject r, String field, long min, long max, String key) {
        long value = r.get(field).getAsLong();
        if (value < min || value > max) throw new IllegalArgumentException("Rank " + key + ": " + field + " " + value + " is outside " + min + ".." + max);
        return (int) value;
    }
}
