package dev.skydock.cargo;

import com.google.gson.*;
import dev.skydock.Skydock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.*;

/** Loads {@code data/<ns>/cargo/cargo_names.json}, a list of lang keys for what contracts carry; an invalid file is logged and the defaults kept. */
public final class CargoNames extends SimpleJsonResourceReloadListener {
    private static final List<String> DEFAULTS = Arrays.stream(new String[] { "machine_parts", "silk", "spices", "glassware", "lantern_oil", "wool_bales",
            "tinned_fish", "maps_charts", "clockwork", "seed_stock", "medicine", "mail" }).map(n -> "cargo.skydock.name." + n).toList();
    private static List<String> loaded = DEFAULTS;
    public CargoNames() { super(new Gson(), "cargo"); }

    public static List<String> names() { return loaded; }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<String> next = DEFAULTS;
        JsonElement file = files.get(Skydock.id("cargo_names"));
        if (file != null) {
            try { next = parse(file.getAsJsonObject()); }
            catch (RuntimeException ex) { Skydock.LOGGER.error("Invalid Skydock cargo names", ex); }
        }
        loaded = next;
    }

    static List<String> parse(JsonObject json) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("names")) out.add(e.getAsString());
        if (out.isEmpty()) throw new IllegalArgumentException("No cargo names");
        return List.copyOf(out);
    }
}
