package dev.skydock.cargo;

import com.google.gson.*;
import dev.skydock.fuzz.Fuzz;
import dev.skydock.fuzz.JsonFuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** ranks.json and shop.json: damaged files are refused as a whole (an unchecked exception the reload logs), valid ones obey their rules. */
class CargoLoaderFuzzTest {
    static final String RANKS = """
            {"ranks": [
              {"level": 1, "key": "rank.skydock.deckhand", "threshold": 0, "min_distance": 300, "max_distance": 800, "deadline_days": 2, "crate": "tin", "base_per_pod": 10},
              {"level": 2, "key": "rank.skydock.mate", "threshold": 100, "min_distance": 600, "max_distance": 1500, "deadline_days": 2, "crate": "copper", "base_per_pod": 25},
              {"level": 3, "key": "rank.skydock.captain", "threshold": 400, "min_distance": 1200, "max_distance": 3000, "deadline_days": 3, "crate": "brass", "base_per_pod": 60},
              {"level": 4, "key": "rank.skydock.commodore", "threshold": 1200, "min_distance": 2500, "max_distance": 6000, "deadline_days": 4, "crate": "gold", "base_per_pod": 150},
              {"level": 5, "key": "rank.skydock.admiral", "threshold": 3000, "min_distance": 5000, "deadline_days": 5, "crate": "aether", "base_per_pod": 400}
            ]}""";
    static final String SHOP = """
            {"entries": [
              {"rank": 1, "price": 60, "item": "minecraft:ender_pearl"},
              {"rank": 1, "price": 40, "item": "minecraft:iron_pickaxe"},
              {"rank": 2, "price": 120, "item": "minecraft:oak_planks", "count": 16},
              {"rank": 3, "price": 500, "pattern": "skydock:brig_freighter"},
              {"rank": 5, "price": 200, "item": "minecraft:lantern", "count": 64}
            ]}""";

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void restore() { CargoRanks.setData(List.of()); }

    static void assertValidLadder(List<Rank> ranks) {
        assertFalse(ranks.isEmpty());
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(i);
            assertEquals(i + 1, r.level(), "levels run 1..N"); assertTrue(r.minDistance() > 0); assertTrue(r.maxDistance() >= r.minDistance());
            assertTrue(r.basePerPod() >= 0); assertTrue(r.deadlineDays() > 0); assertTrue(r.threshold() >= 0); assertNotNull(r.crate()); assertNotNull(r.key());
            if (i > 0) { assertTrue(r.threshold() > ranks.get(i - 1).threshold()); assertTrue(r.minDistance() > ranks.get(i - 1).minDistance()); }
        }
    }

    @Test void damagedRankFilesAreRefusedOrObeyTheLadderRules() {
        int[] parsed = {0};
        Fuzz.check("CargoRankLoader damaged", 8000, (r, log) -> {
            JsonElement bad = JsonFuzz.mutate(r, JsonParser.parseString(RANKS), Fuzz.pick(r, .01, .03, .1, .3), log);
            List<Rank> ranks;
            try { ranks = CargoRankLoader.parse(bad.getAsJsonObject()); }
            catch (RuntimeException refused) { return; }
            parsed[0]++;
            assertValidLadder(ranks);
            // a ladder the loader accepts must drive a board without throwing
            CargoRanks.setData(ranks);
            GlobalPos at = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO);
            List<GlobalPos> sites = new ArrayList<>(); for (int i = 0; i < 6; i++) sites.add(GlobalPos.of(Level.OVERWORLD, new BlockPos(r.nextInt(20001) - 10000, 64, r.nextInt(20001) - 10000)));
            long[] id = {0};
            Offers.generate(at, r.nextInt(ranks.size() + 2), sites, List::of, RandomSource.create(r.nextLong()), () -> id[0]++, List.of("cargo.a"));
        });
        assertTrue(parsed[0] > 300, "enough damaged files still parse to test the rules: " + parsed[0]);
    }

    @Test void randomRankFilesNeverEscapeAsErrors() {
        Fuzz.check("CargoRankLoader random", 8000, (r, log) -> {
            JsonObject json = new JsonObject(); JsonArray ranks = new JsonArray();
            for (int i = r.nextInt(4); i > 0; i--) {
                JsonObject rank = new JsonObject();
                for (String k : List.of("level", "key", "threshold", "min_distance", "max_distance", "deadline_days", "crate", "base_per_pod"))
                    if (r.nextInt(6) != 0) rank.add(k, r.nextBoolean() ? JsonFuzz.number(r) : JsonFuzz.randomJson(r, 2));
                ranks.add(r.nextInt(8) == 0 ? JsonFuzz.randomJson(r, 2) : rank);
            }
            json.add(Fuzz.pick(r, "ranks", "ranks", "Ranks"), r.nextInt(10) == 0 ? JsonFuzz.randomJson(r, 1) : ranks);
            log.append(json);
            try { assertValidLadder(CargoRankLoader.parse(json)); } catch (RuntimeException refused) {}
        });
    }

    @Test void damagedShopFilesAreRefusedOrObeyTheEntryRules() {
        int[] parsed = {0};
        Fuzz.check("ShopLoader damaged", 8000, (r, log) -> {
            JsonElement bad = JsonFuzz.mutate(r, JsonParser.parseString(SHOP), Fuzz.pick(r, .01, .03, .1, .3), log);
            List<ShopEntry> entries;
            try { entries = ShopLoader.parse(bad.getAsJsonObject()); }
            catch (RuntimeException refused) { return; }
            parsed[0]++;
            for (ShopEntry e : entries) {
                assertTrue(e.rank() >= 1, "rank"); assertTrue(e.price() >= 0, "price");
                assertTrue(e.isPattern() != !e.item().isEmpty(), "exactly one of item or pattern");
                if (!e.isPattern()) assertTrue(e.item().getCount() >= 1 && e.item().getCount() <= e.item().getMaxStackSize(), "count");
                ShopEntry back = ShopEntry.load(e.save());
                assertEquals(e.rank(), back.rank()); assertEquals(e.price(), back.price()); assertEquals(e.pattern(), back.pattern());
                assertTrue(net.minecraft.world.item.ItemStack.matches(e.item(), back.item()), "the synced entry reads back the same");
            }
            for (int i = 0; i < entries.size(); i++) assertSame(entries.get(i), Shop.resolve(entries, Shop.fingerprint(i, entries.get(i))));
        });
        assertTrue(parsed[0] > 300, "enough damaged files still parse to test the rules: " + parsed[0]);
    }
}
