package dev.skydock.cargo;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShopTest {
    private static final ResourceLocation BRIG = ResourceLocation.parse("skydock:brig_freighter"), CRUISER = ResourceLocation.parse("skydock:cruiser_freighter");

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static CargoProfile withReputation(int reputation) { CargoProfile p = new CargoProfile(); p.reputation = reputation; return p; }
    private static ShopEntry item(int rank, int price) { return new ShopEntry(rank, price, new ItemStack(Items.DIAMOND, 2), null); }
    private static ShopEntry pattern(int rank, int price, ResourceLocation id) { return new ShopEntry(rank, price, ItemStack.EMPTY, id); }

    @Test void buyingNeedsTheRankAndTheBalance() {
        CargoProfile captainBroke = withReputation(450); captainBroke.spent = 400; // Captain by peak, balance 50
        assertEquals("message.skydock.shop_funds", Shop.buy(captainBroke, pattern(3, 60, BRIG)));
        CargoProfile mate = withReputation(300); // rich enough, but only Mate
        assertEquals("message.skydock.shop_rank", Shop.buy(mate, pattern(3, 60, BRIG)));
        assertEquals(0, mate.spent); assertTrue(mate.unlocked.isEmpty());
        CargoProfile deckhand = withReputation(60);
        assertEquals("", Shop.buy(deckhand, item(1, 60)));
        assertEquals(60, deckhand.spent); assertEquals(0, deckhand.balance());
        assertEquals("message.skydock.shop_funds", Shop.buy(deckhand, item(1, 1)));
    }

    @Test void buyingSpendsWithoutLosingRank() {
        CargoProfile p = withReputation(1300); int rank = p.peakRank(), balance = p.balance();
        assertEquals("", Shop.buy(p, pattern(3, 500, BRIG)));
        assertEquals(rank, p.peakRank());
        assertEquals(balance - 500, p.balance());
        assertTrue(p.unlocked.contains(BRIG));
        assertEquals("", Shop.buy(p, item(4, 700)), "spending leaves the Commodore rank in place");
        assertEquals(100, p.balance()); assertEquals(rank, p.peakRank());
    }

    @Test void patternCannotBeBoughtTwice() {
        CargoProfile p = withReputation(5000);
        assertEquals("", Shop.buy(p, pattern(3, 500, BRIG)));
        assertEquals("message.skydock.shop_owned", Shop.buy(p, pattern(3, 500, BRIG)));
        assertEquals(500, p.spent);
        assertEquals("", Shop.buy(p, item(1, 10))); assertEquals("", Shop.buy(p, item(1, 10)), "items can be bought again");
    }

    @Test void shopFileParsesItemsAndPatternsAndRejectsBadEntries() {
        List<ShopEntry> entries = ShopLoader.parse(JsonParser.parseString("""
                {"entries": [{"rank": 1, "price": 60, "item": "minecraft:diamond", "count": 3},
                             {"rank": 3, "price": 500, "pattern": "skydock:brig_freighter"}]}""").getAsJsonObject());
        assertEquals(2, entries.size());
        assertEquals(Items.DIAMOND, entries.get(0).item().getItem()); assertEquals(3, entries.get(0).item().getCount()); assertNull(entries.get(0).pattern());
        assertEquals(BRIG, entries.get(1).pattern()); assertTrue(entries.get(1).item().isEmpty()); assertEquals(500, entries.get(1).price());
        for (String bad : List.of("{\"rank\": 1, \"price\": 5}", "{\"rank\": 1, \"price\": 5, \"item\": \"minecraft:no_such_item\"}",
                "{\"rank\": 0, \"price\": 5, \"item\": \"minecraft:diamond\"}", "{\"rank\": 1, \"price\": -1, \"item\": \"minecraft:diamond\"}",
                "{\"rank\": 1, \"price\": 5, \"item\": \"minecraft:diamond\", \"pattern\": \"skydock:brig_freighter\"}",
                "{\"rank\": 1, \"price\": 5, \"item\": \"minecraft:diamond\", \"count\": 65}"))
            assertThrows(RuntimeException.class, () -> ShopLoader.parse(JsonParser.parseString("{\"entries\": [" + bad + "]}").getAsJsonObject()), bad);
    }

    @Test void shippedShopFileMatchesTheDefaults() throws java.io.IOException {
        assertEquals(JsonParser.parseString(Shop.DEFAULTS), JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/data/skydock/cargo/shop.json"))));
    }

    @Test void syncedEntriesRoundTrip() {
        for (ShopEntry entry : List.of(item(2, 120), pattern(4, 1500, CRUISER))) {
            ShopEntry back = ShopEntry.load(entry.save());
            assertEquals(entry.rank(), back.rank()); assertEquals(entry.price(), back.price()); assertEquals(entry.pattern(), back.pattern());
            assertTrue(ItemStack.matches(entry.item(), back.item()));
        }
    }

    @Test void cratePrizeUnlocksAMissingPatternOrRefundsWhenAllAreOwned() {
        List<ResourceLocation> shop = List.of(BRIG, CRUISER);
        CargoProfile p = withReputation(0); p.unlocked.add(BRIG);
        assertEquals(CRUISER, Shop.cratePrize(p, CrateRank.AETHER, shop, RandomSource.create(1)).pattern());
        assertTrue(p.unlocked.contains(CRUISER)); assertEquals(0, p.reputation);
        Shop.Prize refund = Shop.cratePrize(p, CrateRank.AETHER, shop, RandomSource.create(1));
        assertNull(refund.pattern()); assertTrue(refund.refunded()); assertEquals(Shop.PRIZE_REFUND, p.reputation);
        Shop.Prize none = Shop.cratePrize(p, CrateRank.TIN, shop, RandomSource.create(1));
        assertNull(none.pattern()); assertFalse(none.refunded()); assertEquals(Shop.PRIZE_REFUND, p.reputation);
        CargoProfile fresh = withReputation(0);
        assertFalse(Shop.cratePrize(fresh, CrateRank.AETHER, List.of(), RandomSource.create(1)).refunded(), "no shop patterns, nothing to refund");
    }
}
