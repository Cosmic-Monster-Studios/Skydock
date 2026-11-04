package dev.skydock.cargo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.skydock.fuzz.Fuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Properties that fail on the current code. Each is disabled with the bug it shows; remove the annotation once it is fixed.
 */
class CargoBugsFuzzTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void restoreRanks() { CargoRanks.setData(List.of()); }

    /**
     * Shop.fingerprint hashes with Objects.hash(rank, price, item, pattern) = 31^4 + 31^3·rank + 31^2·price + 31·item + pattern, so
     * raising the rank by one and cutting the price by 31 gives the same 32-bit hash, as does any item whose id string hashes 31^2
     * apart from the old one together with a rank shifted the other way (music_disc_11 and music_disc_13 at ranks 3 and 1). A screen
     * left open across a reload then buys the new entry at the old index. Minimal reproducer: entry 0 changes from
     * (rank 1, price 60, stone) to (rank 2, price 29, stone); resolve(newList, fingerprint(0, oldEntry)) returns the new entry.
     */
    @Test void aChangedEntryNeverMatchesTheOldFingerprint() {
        Fuzz.check("Shop.fingerprint near edits", 2000, (r, log) -> {
            Item item = Fuzz.pick(r, Items.STONE, Items.DIAMOND, Items.ENDER_PEARL, Items.MUSIC_DISC_11);
            ShopEntry old = new ShopEntry(1 + r.nextInt(4), 31 + r.nextInt(5000), new ItemStack(item), null);
            int k = 1 + r.nextInt(3);
            ShopEntry edited = switch (r.nextInt(3)) {
                case 0 -> new ShopEntry(old.rank() + k, old.price() - 31 * k, old.item().copy(), null);
                case 1 -> new ShopEntry(old.rank() - 1 < 1 ? old.rank() + 1 : old.rank() - 1, old.price() + (old.rank() - 1 < 1 ? -31 : 31), old.item().copy(), null);
                default -> new ShopEntry(old.rank(), old.price() + r.nextInt(3) - 1, new ItemStack(item, 2), null);
            };
            log.append("old=").append(old.rank()).append('/').append(old.price()).append('/').append(old.item()).append(" new=").append(edited.rank()).append('/').append(edited.price()).append('/').append(edited.item());
            assertNull(Shop.resolve(List.of(edited), Shop.fingerprint(0, old)), "the edited entry answers the old fingerprint");
        });
    }

    @Test void aDifferentItemNeverMatchesTheOldFingerprint() {
        ShopEntry old = new ShopEntry(3, 100, new ItemStack(Items.MUSIC_DISC_11), null), now = new ShopEntry(1, 100, new ItemStack(Items.MUSIC_DISC_13), null);
        assertNull(Shop.resolve(List.of(now), Shop.fingerprint(0, old)), "music_disc_13 at rank 1 answers the fingerprint of music_disc_11 at rank 3");
    }

    /**
     * CargoRankLoader.parse only bounds magnitudes from below, and the reward maths casts a long to int:
     * CargoRanks.reputation is {@code (int) Math.round(base * distance / min)}, which wraps negative once the product passes
     * Integer.MAX_VALUE, so deliveries then lower reputation (and can cost rank); CargoService.searchRadius doubles an open-ended top
     * band's minimum and fallbackRadius adds 2,000 to it, both in int, so a large minimum turns the search radius negative and boards
     * stay empty. Minimal reproducers: one rank {min_distance 1,500,000,000, no max} gives searchRadius(1) = -1,294,967,296;
     * a rank with base_per_pod 2,000,000,000 and min_distance 300 pays reputation(rank, 600) = -294,967,296.
     */
    @Test void acceptedLaddersNeverOverflowTheRewardOrRadiusMaths() {
        Fuzz.check("CargoRanks magnitudes", 3000, (r, log) -> {
            JsonObject json = new JsonObject(); JsonArray ranks = new JsonArray(); json.add("ranks", ranks);
            long threshold = 0, min = 1 + r.nextInt(1000); int n = 1 + r.nextInt(4);
            for (int level = 1; level <= n; level++) {
                JsonObject rank = new JsonObject(); rank.addProperty("level", level); rank.addProperty("key", "rank." + level);
                rank.addProperty("threshold", threshold); rank.addProperty("min_distance", min);
                if (level < n || r.nextBoolean()) rank.addProperty("max_distance", Math.min(Integer.MAX_VALUE, min + r.nextInt(5000)));
                rank.addProperty("deadline_days", 1 + r.nextInt(5)); rank.addProperty("crate", "tin");
                rank.addProperty("base_per_pod", Fuzz.pick(r, 10, 400, 1_000_000, 2_000_000_000, Integer.MAX_VALUE));
                ranks.add(rank);
                threshold += 1 + r.nextInt(10_000);
                min = Math.min(Integer.MAX_VALUE - (n - level), min + 1 + (r.nextInt(3) == 0 ? (long) (r.nextDouble() * 1.9e9) : r.nextInt(5000)));
            }
            log.append(json);
            List<Rank> ladder;
            try { ladder = CargoRankLoader.parse(json); } catch (RuntimeException refused) { return; }
            CargoRanks.setData(ladder);
            for (int rank = 0; rank <= n + 1; rank++) assertTrue(CargoService.searchRadius(rank) > 0, "searchRadius(" + rank + ") = " + CargoService.searchRadius(rank));
            assertTrue(CargoService.fallbackRadius() > 0, "fallbackRadius = " + CargoService.fallbackRadius());
            for (Rank rank : ladder) for (double d : new double[]{rank.minDistance(), rank.minDistance() * 2.0, Math.min(rank.maxDistance(), 6e7)})
                assertTrue(CargoRanks.reputation(rank, d) >= rank.basePerPod(), "reputation(" + rank.key() + ", " + d + ") = " + CargoRanks.reputation(rank, d));
        });
    }

    /**
     * Contract.load takes {@code Pods} as saved, and settled() and CargoService.expire walk every index from 0 to Pods, expire
     * calling settled() once per index, so a corrupted Pods value makes the expiry check quadratic in it: with Integer.MAX_VALUE it
     * never finishes and fills the lost set until the server runs out of memory. tickExpiry runs this for every profile once a
     * minute. Minimal reproducer: a saved contract with {@code Pods: 100000} past its deadline; expire() takes far longer than a tick
     * (Integer.MAX_VALUE hangs the server thread).
     */
    @Disabled("bug: a corrupted Pods count in a saved contract makes expiry quadratic in it and hangs the server thread")
    @Test void expiringALoadedContractStaysCheapWhateverItsPodCount() {
        Fuzz.check("Contract pods freeze", 3, (r, log) -> {
            GlobalPos a = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), b = GlobalPos.of(Level.OVERWORLD, new BlockPos(900, 64, 0));
            CompoundTag saved = new Contract(5, a, b, 1, CrateRank.TIN, "cargo.x", 2, 0, 10).save();
            // kept to sizes whose runaway check, once the timeout gives up on it, cannot exhaust the test JVM's memory
            int pods = Fuzz.pick(r, 100_000, 300_000); saved.putInt("Pods", pods); log.append("Pods=").append(pods);
            CargoSavedData data = new CargoSavedData(); UUID holder = new UUID(0, 1);
            data.terminals.put(b, new CargoSavedData.Terminal(b, b.pos(), true));
            Contract loaded = Contract.load(saved);
            data.profile(holder).contracts.put(loaded.id, loaded);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> CargoService.expire(data, holder, CargoService.EXPIRY_GRACE));
        });
    }
}
