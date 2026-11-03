package dev.skydock.cargo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

/** The outpost worldgen files carry every field their vanilla 1.21.1 counterparts carry, so the codecs decode them. */
class OutpostWorldgenJsonTest {
    private static JsonObject vanilla(String path) throws Exception {
        var in = OutpostWorldgenJsonTest.class.getResourceAsStream("/data/minecraft/" + path);
        assertNotNull(in, "vanilla " + path + " is not on the test classpath");
        try (var reader = new InputStreamReader(in)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
    private static JsonObject ours(String path) throws Exception { return JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/skydock", path))).getAsJsonObject(); }
    private static void sameKeys(JsonObject vanilla, JsonObject ours, Set<String> optional, String what) {
        Set<String> missing = new TreeSet<>(vanilla.keySet()); missing.removeAll(ours.keySet()); missing.removeAll(optional);
        assertTrue(missing.isEmpty(), what + " is missing " + missing);
    }

    @Test void structureHasEveryVanillaJigsawField() throws Exception {
        sameKeys(vanilla("worldgen/structure/pillager_outpost.json"), ours("worldgen/structure/cargo_outpost.json"), Set.of(), "cargo_outpost structure");
    }
    @Test void structureSetHasEveryRequiredPlacementField() throws Exception {
        JsonObject v = vanilla("worldgen/structure_set/pillager_outposts.json"), o = ours("worldgen/structure_set/cargo_outposts.json");
        sameKeys(v, o, Set.of(), "cargo_outposts structure set");
        // Optional with codec defaults, and left out on purpose: TerminalSites predicts sites without frequency or exclusion.
        sameKeys(v.getAsJsonObject("placement"), o.getAsJsonObject("placement"), Set.of("exclusion_zone", "frequency", "frequency_reduction_method"), "cargo_outposts placement");
        sameKeys(v.getAsJsonArray("structures").get(0).getAsJsonObject(), o.getAsJsonArray("structures").get(0).getAsJsonObject(), Set.of(), "cargo_outposts structure entry");
    }
    @Test void templatePoolHasEveryVanillaField() throws Exception {
        JsonObject v = vanilla("worldgen/template_pool/pillager_outpost/base_plates.json"), o = ours("worldgen/template_pool/cargo_outpost/start.json");
        sameKeys(v, o, Set.of(), "cargo_outpost/start pool");
        JsonObject ve = v.getAsJsonArray("elements").get(0).getAsJsonObject(), oe = o.getAsJsonArray("elements").get(0).getAsJsonObject();
        sameKeys(ve, oe, Set.of(), "pool entry"); sameKeys(ve.getAsJsonObject("element"), oe.getAsJsonObject("element"), Set.of(), "pool element");
    }
}
