package dev.skydock.cargo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CargoRankLoaderTest {
    private static JsonObject shipped() throws Exception { return JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/skydock/cargo/ranks.json"))).getAsJsonObject(); }
    private static JsonObject rank(JsonObject json, int i) { return json.getAsJsonArray("ranks").get(i).getAsJsonObject(); }

    @Test void shippedRanksMatchTheDefaults() throws Exception { assertEquals(CargoRanks.defaults(), CargoRankLoader.parse(shipped())); }
    @Test void maximumBelowMinimumIsRefused() throws Exception {
        JsonObject json = shipped(); rank(json, 0).addProperty("max_distance", 200);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(json));
    }
    @Test void duplicateLevelIsRefused() throws Exception {
        JsonObject json = shipped(); rank(json, 1).addProperty("level", 1);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(json));
    }
    @Test void descendingLevelsAreRefused() throws Exception {
        JsonObject json = shipped(); rank(json, 0).addProperty("level", 2); rank(json, 1).addProperty("level", 1);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(json));
    }
}
