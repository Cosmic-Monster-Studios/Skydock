package dev.skydock.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.skydock.Skydock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Loads {@code data/<namespace>/skydock_patterns/*.json}. A file that fails any check is logged and skipped whole. */
public final class PatternLoader extends SimpleJsonResourceReloadListener {
    public PatternLoader() { super(new Gson(), "skydock_patterns"); }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, ShipPattern> patterns = new LinkedHashMap<>();
        for (var file : new TreeMap<>(files).entrySet()) {
            try {
                if (!file.getValue().isJsonObject()) throw new IllegalArgumentException("A pattern file must be a JSON object");
                patterns.put(file.getKey(), PatternJson.parse(file.getKey(), file.getValue().getAsJsonObject(), PatternJson::state));
            } catch (RuntimeException ex) { Skydock.LOGGER.error("Invalid Skydock ship pattern {}", file.getKey(), ex); }
        }
        ShipPatterns.setData(patterns);
        if (!patterns.isEmpty()) Skydock.LOGGER.info("Loaded {} Skydock ship pattern(s) from data packs", patterns.size());
    }
}
