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
    @Test void levelsMustStartAtOneAndRunWithoutGaps() throws Exception {
        JsonObject gap = shipped(); rank(gap, 4).addProperty("level", 6);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(gap));
        JsonObject shifted = shipped(); for (int i = 0; i < 5; i++) rank(shifted, i).addProperty("level", i + 2);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(shifted));
        JsonObject zero = shipped(); for (int i = 0; i < 5; i++) rank(zero, i).addProperty("level", i);
        assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(zero));
    }
    @Test void magnitudesAreBounded() throws Exception {
        JsonObject atCaps = shipped(); JsonObject top = rank(atCaps, 4);
        top.addProperty("base_per_pod", CargoRankLoader.MAX_BASE_PER_POD); top.addProperty("min_distance", CargoRankLoader.MAX_DISTANCE);
        top.addProperty("threshold", CargoRankLoader.MAX_THRESHOLD); top.addProperty("deadline_days", CargoRankLoader.MAX_DEADLINE_DAYS);
        assertEquals(CargoRankLoader.MAX_DISTANCE, CargoRankLoader.parse(atCaps).getLast().minDistance());
        for (String[] over : new String[][]{{"base_per_pod", "1000001"}, {"min_distance", "30000001"}, {"max_distance", "30000001"}, {"threshold", "1000000001"},
                {"deadline_days", "366"}, {"base_per_pod", "4294967306"}, {"threshold", "-1"}, {"deadline_days", "0"}}) {
            JsonObject json = shipped(); rank(json, 4).addProperty(over[0], Long.parseLong(over[1]));
            assertThrows(IllegalArgumentException.class, () -> CargoRankLoader.parse(json), over[0] + " " + over[1]);
        }
    }
    @Test void rewardAndRadiusSaturateInsteadOfWrapping() {
        Rank far = new Rank(1, "rank.far", 0, 1, Integer.MAX_VALUE, 1, CrateRank.TIN, 1_000_000);
        assertEquals(Integer.MAX_VALUE, CargoRanks.reputation(far, 6e7));
        try {
            CargoRanks.setData(java.util.List.of(new Rank(1, "rank.far", 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, CrateRank.TIN, 10)));
            assertEquals(Integer.MAX_VALUE, CargoService.searchRadius(1)); assertEquals(Integer.MAX_VALUE, CargoService.fallbackRadius());
        } finally { CargoRanks.setData(java.util.List.of()); }
    }
}
