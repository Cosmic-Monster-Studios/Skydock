package dev.skydock.cargo;

import com.google.gson.JsonParser;
import dev.skydock.data.ShipPattern;
import dev.skydock.data.ShipPatterns;
import dev.skydock.ship.ShipManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import java.util.*;

/**
 * The reputation shop: rank-locked items and ship patterns bought with spent reputation (rank is kept), and the crate pattern
 * prize. Stock is data-driven via {@link ShopLoader}; the defaults below match the shipped {@code shop.json}.
 */
public final class Shop {
    /** Reputation granted instead of a crate pattern prize when the opener already owns every shop pattern. */
    public static final int PRIZE_REFUND = 200;
    static final String DEFAULTS = """
            {"entries": [
              {"rank": 1, "price": 60, "item": "skydock:warp_anchor"},
              {"rank": 1, "price": 40, "item": "skydock:shipwright_hammer"},
              {"rank": 2, "price": 120, "item": "skydock:lift_cell", "count": 16},
              {"rank": 2, "price": 150, "item": "skydock:engine_reinforced"},
              {"rank": 3, "price": 400, "item": "skydock:engine_turbine"},
              {"rank": 3, "price": 500, "pattern": "skydock:brig_freighter"},
              {"rank": 4, "price": 1200, "item": "skydock:engine_aether"},
              {"rank": 4, "price": 1500, "pattern": "skydock:cruiser_freighter"},
              {"rank": 5, "price": 4000, "pattern": "skydock:dreadnought_freighter"},
              {"rank": 5, "price": 200, "item": "skydock:brass_lantern", "count": 64},
              {"rank": 5, "price": 200, "item": "skydock:canvas_awning", "count": 64},
              {"rank": 5, "price": 200, "item": "skydock:timber_railing", "count": 64},
              {"rank": 5, "price": 200, "item": "skydock:signal_flag", "count": 64}
            ]}""";
    /** The running server, so a reload can resync open terminals; null while none runs. */
    private static volatile MinecraftServer server;
    /** Null until a reload sets data; the defaults resolve lazily because their items must be registered first. */
    private static volatile List<ShopEntry> loaded, defaults;
    private Shop() {}

    public static List<ShopEntry> defaults() {
        List<ShopEntry> d = defaults;
        if (d == null) defaults = d = ShopLoader.parse(JsonParser.parseString(DEFAULTS).getAsJsonObject());
        return d;
    }
    public static List<ShopEntry> entries() { List<ShopEntry> l = loaded; return l == null ? defaults() : l; }
    /** Null keeps the defaults. */
    public static void setData(List<ShopEntry> entries) { loaded = entries == null ? null : List.copyOf(entries); }
    /** Every loaded pattern the shop sells, once each, in shop order. */
    public static List<ResourceLocation> patterns() {
        return entries().stream().map(ShopEntry::pattern).filter(Objects::nonNull).distinct().filter(id -> ShipPatterns.get(id).isPresent()).toList();
    }

    public static void started(MinecraftServer started) { server = started; }
    public static void stopped(MinecraftServer stopped) { server = null; }
    /** After a reload changes the stock, every player looking at a terminal gets the new list. */
    static void resyncTerminals() {
        MinecraftServer s = server; if (s == null) return;
        s.execute(() -> { for (ServerPlayer player : s.getPlayerList().getPlayers()) if (player.containerMenu instanceof TerminalMenu) CargoService.sync(player); });
    }

    /**
     * What the client sends to buy an entry: its index in the low 16 bits and a 32-bit hash of what it sells (rank, price,
     * item id and count, pattern id) above, so a screen left open across a reload cannot buy whatever now sits at that index.
     */
    public static long fingerprint(int index, ShopEntry entry) {
        String item = entry.item().isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.item().getItem()) + "*" + entry.item().getCount();
        int hash = Objects.hash(entry.rank(), entry.price(), item, entry.pattern() == null ? "" : entry.pattern().toString());
        return (hash & 0xFFFFFFFFL) << 16 | (index & 0xFFFF);
    }
    /** The entry a fingerprint names, or null when the index is out of range or the entry there has changed. */
    public static ShopEntry resolve(List<ShopEntry> entries, long fingerprint) {
        int index = (int) (fingerprint & 0xFFFF);
        if (fingerprint < 0 || index >= entries.size()) return null;
        ShopEntry entry = entries.get(index);
        return fingerprint(index, entry) == fingerprint ? entry : null;
    }

    /**
     * Empty on success, else the lang key saying why not. Success adds the price to {@code spent} and unlocks a pattern entry;
     * for an item entry the caller gives a copy of {@link ShopEntry#item()}.
     */
    public static String buy(CargoProfile profile, ShopEntry entry) {
        if (entry.rank() > profile.peakRank()) return "message.skydock.shop_rank";
        if (entry.isPattern() && profile.unlocked.contains(entry.pattern())) return "message.skydock.shop_owned";
        if (profile.balance() < entry.price()) return "message.skydock.shop_funds";
        profile.spent += entry.price();
        if (entry.isPattern()) profile.unlocked.add(entry.pattern());
        return "";
    }

    /** Built-ins and free datapack patterns are visible to everyone, shop-only ones to players who unlocked them. */
    public static boolean visible(Set<ResourceLocation> unlocked, ShipPattern pattern) { return !pattern.shopOnly() || unlocked.contains(pattern.id()); }
    public static boolean visible(ServerPlayer player, ShipPattern pattern) {
        if (!pattern.shopOnly()) return true;
        // Read without creating a profile, so looking at a dock never dirties the saved data.
        CargoProfile profile = CargoSavedData.get(player.getServer()).profiles.get(player.getUUID());
        return profile != null && visible(profile.unlocked, pattern);
    }

    /** What a crate gave: an unlocked pattern, or the refund when every shop pattern was already owned, or neither. */
    public record Prize(ResourceLocation pattern, boolean refunded) {}

    /**
     * Rolls the crate's pattern prize ({@link CrateShow#patternPrize}) into the profile. When the opener owns every shop pattern
     * the roll still decides whether a prize was won, and a won prize becomes {@link #PRIZE_REFUND} reputation.
     */
    public static Prize cratePrize(CargoProfile profile, CrateRank rank, List<ResourceLocation> shopPatterns, RandomSource random) {
        boolean ownsAll = !shopPatterns.isEmpty() && profile.unlocked.containsAll(shopPatterns);
        Optional<ResourceLocation> won = CrateShow.patternPrize(rank, ownsAll ? Set.of() : profile.unlocked, shopPatterns, random);
        if (won.isEmpty()) return new Prize(null, false);
        if (ownsAll) { profile.reputation += PRIZE_REFUND; return new Prize(null, true); }
        profile.unlocked.add(won.get());
        return new Prize(won.get(), false);
    }

    /** Applies a crate's pattern prize to its opener's saved profile whether or not they are online, telling and syncing them if they are. */
    public static void awardCratePrize(MinecraftServer server, UUID opener, CrateRank rank, RandomSource random) {
        CargoSavedData data = CargoSavedData.get(server); CargoProfile profile = data.profile(opener); int rankBefore = profile.peakRank();
        Prize prize = cratePrize(profile, rank, patterns(), random);
        if (prize.pattern() == null && !prize.refunded()) return;
        data.setDirty();
        ServerPlayer player = server.getPlayerList().getPlayer(opener);
        if (player == null) return;
        if (prize.refunded()) { ShipManager.tell(player, "message.skydock.crate_pattern_refund", PRIZE_REFUND); CargoService.announceRankUp(player, profile, rankBefore); }
        else ShipManager.tell(player, "message.skydock.crate_pattern", patternName(prize.pattern()));
        CargoService.sync(player);
    }

    public static Component patternName(ResourceLocation id) {
        return ShipPatterns.get(id).map(p -> (Component) Component.translatable(p.name())).orElseGet(() -> Component.translatable(id.toString()));
    }
}
