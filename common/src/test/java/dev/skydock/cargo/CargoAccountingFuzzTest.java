package dev.skydock.cargo;

import dev.skydock.fuzz.Fuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Property tests of the pure cargo accounting: deliveries, losses, orphans, abandons, expiry, boards and the shop. */
class CargoAccountingFuzzTest {
    static final UUID HOLDER = new UUID(1, 1);
    static final UUID[] STRANGERS = {new UUID(2, 2), new UUID(3, 3)};
    static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END,
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("skydock:shipyard")));

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @AfterEach void restoreRanks() { CargoRanks.setData(List.of()); }

    static GlobalPos pos(Random r, ResourceKey<Level> dim, int spread) {
        return GlobalPos.of(dim, new BlockPos(r.nextInt(2 * spread + 1) - spread, r.nextInt(380) - 64, r.nextInt(2 * spread + 1) - spread));
    }

    /** A random valid ladder: levels 1..N, thresholds and minimum distances strictly rising, maxima at or above minima. */
    static List<Rank> randomLadder(Random r) {
        int n = 1 + r.nextInt(6); List<Rank> out = new ArrayList<>(); int threshold = r.nextInt(3) == 0 ? 0 : r.nextInt(50), min = 1 + r.nextInt(500);
        for (int level = 1; level <= n; level++) {
            int max = level == n && r.nextBoolean() ? Integer.MAX_VALUE : min + r.nextInt(3000);
            out.add(new Rank(level, "rank." + level, threshold, min, max, 1 + r.nextInt(6), Fuzz.pick(r, CrateRank.values()), r.nextInt(500)));
            threshold += 1 + r.nextInt(2000); min += 1 + r.nextInt(2000);
        }
        return out;
    }

    // ---------------------------------------------------------------- deliveries

    /** One pod item in the world: its tag and whether it still exists. */
    private static final class Item_ { final PodTag tag; boolean gone; Item_(PodTag tag) { this.tag = tag; } }

    @Test void randomDeliverySequencesKeepTheLedger() {
        int[] seen = new int[4]; // settled with a crate, settled without, failed, paid orphan
        Fuzz.check("cargo ledger", 6000, (r, log) -> {
            if (r.nextInt(4) == 0) CargoRanks.setData(randomLadder(r)); else CargoRanks.setData(List.of());
            CargoSavedData data = new CargoSavedData();
            GlobalPos origin = pos(r, Level.OVERWORLD, 3000), dest; do dest = pos(r, Level.OVERWORLD, 3000); while (dest.equals(origin));
            GlobalPos elsewhere = pos(r, Level.OVERWORLD, 3000);
            for (GlobalPos t : List.of(origin, dest, elsewhere)) data.terminals.put(t, new CargoSavedData.Terminal(t, t.pos(), r.nextInt(6) != 0));
            data.terminals.put(dest, new CargoSavedData.Terminal(dest, dest.pos(), true));
            int pods = 1 + r.nextInt(6);
            int rate = Fuzz.pick(r, 0, 1, 2, 25, r.nextInt(1000), Integer.MAX_VALUE - r.nextInt(3), Integer.MAX_VALUE / 2 + 1);
            CrateRank crate = Fuzz.pick(r, CrateRank.values()); long deadline = r.nextInt(200_000), id = 1 + r.nextInt(1 << 20);
            Contract c = new Contract(id, origin, dest, 1 + r.nextInt(5), crate, "cargo.skydock.name.silk", pods, deadline, rate);
            data.profile(HOLDER).contracts.put(id, c);
            log.append("pods=").append(pods).append(" rate=").append(rate).append(" crate=").append(crate).append(" deadline=").append(deadline).append('\n');

            List<Item_> items = new ArrayList<>();
            for (int i = 0; i < pods; i++) items.add(new Item_(new PodTag(id, i, r.nextInt(5) == 0)));
            for (int extra = r.nextInt(4); extra > 0; extra--) // duplicated items, stray indices and other contracts
                items.add(new Item_(switch (r.nextInt(4)) {
                    case 0 -> new PodTag(id, r.nextInt(pods), r.nextBoolean());
                    case 1 -> new PodTag(id, Fuzz.pick(r, -1, pods, pods + 1, Integer.MIN_VALUE, Integer.MAX_VALUE), false);
                    case 2 -> new PodTag(id + 1, 0, false);
                    default -> new PodTag(id, r.nextInt(pods), true);
                }));

            Map<UUID, Integer> lastRep = new HashMap<>();
            int[] paid = new int[pods]; int holderCrates = 0; CrateRank got = null; boolean failed = false;
            int steps = 1 + r.nextInt(24);
            for (int step = 0; step < steps; step++) {
                int op = r.nextInt(12);
                if (op <= 6) { // deliver one item
                    List<Item_> left = items.stream().filter(i -> !i.gone).toList(); if (left.isEmpty()) continue;
                    Item_ item = Fuzz.pick(r, left); UUID who = r.nextInt(3) == 0 ? Fuzz.pick(r, STRANGERS) : HOLDER;
                    boolean late = r.nextInt(4) == 0; GlobalPos at = Fuzz.pick(r, dest, dest, origin, elsewhere);
                    log.append("deliver ").append(item.tag).append(" by ").append(who == HOLDER ? "holder" : "stranger").append(late ? " late" : "").append(" at ").append(at.equals(dest) ? "dest" : at.equals(origin) ? "origin" : "elsewhere").append('\n');
                    CargoProfile me = data.profile(who); var held = data.findContract(item.tag.contract()); Delivery.Result result;
                    if (held != null) {
                        Contract hc = held.getValue();
                        if (!at.equals(CargoService.resolveDestination(data.terminals, hc.destination)) || !Delivery.accepts(hc, item.tag, at)) continue;
                        boolean byHolder = held.getKey().equals(who);
                        result = Delivery.deliver(me, hc, item.tag, byHolder, late, at);
                        CargoService.applyResult(data, held.getKey(), hc, result);
                        if (hc == c) {
                            paid[item.tag.index()]++;
                            assertEquals(byHolder && !late && !item.tag.ejected() ? rate : rate / 2, result.reputation(), "pay");
                            if (result.crate() != null) { assertTrue(byHolder, "only the holder is handed a crate"); assertTrue(result.settled()); holderCrates++; got = result.crate(); }
                        }
                    } else {
                        if (!Delivery.acceptsOrphan(item.tag.contract(), null, at, data.voided)) continue;
                        result = Delivery.orphan(me, item.tag.contract(), null, at, data.voided); if (result.reputation() > 0) seen[3]++;
                        if (item.tag.contract() == id) assertTrue(failed && !data.voided.contains(id), "an orphan of this contract pays only once it failed unvoided");
                        assertNull(result.crate());
                    }
                    item.gone = true;
                } else if (op == 7) { // a pod falls out of the world
                    int index = r.nextInt(pods + 4) - 2; log.append("lose ").append(index).append('\n');
                    for (Item_ i : items) if (!i.gone && i.tag.contract() == id && i.tag.index() == index) { i.gone = true; break; }
                    var held = data.findContract(id); if (held != null) CargoService.applyResult(data, held.getKey(), held.getValue(), Delivery.lose(held.getValue(), index));
                } else if (op == 8) { // abandon
                    log.append("abandon\n");
                    if (data.profile(HOLDER).contracts.remove(id) != null) data.voided.add(id);
                } else if (op == 9 || op == 10) { // expiry check, sometimes before the grace is up
                    long now = deadline + CargoService.EXPIRY_GRACE + (r.nextBoolean() ? r.nextInt(100_000) : -1 - r.nextInt(1000));
                    log.append("expire at ").append(now).append('\n');
                    boolean wasHeld = data.profile(HOLDER).contracts.containsKey(id);
                    boolean any = CargoService.expire(data, HOLDER, now);
                    if (wasHeld && now - deadline >= CargoService.EXPIRY_GRACE && CargoService.resolveDestination(data.terminals, dest) != null) {
                        assertTrue(any); assertFalse(data.profile(HOLDER).contracts.containsKey(id)); assertTrue(data.voided.contains(id)); assertTrue(c.settled());
                    }
                } else { // the destination terminal goes away
                    log.append("destination gone\n");
                    data.terminals.remove(dest);
                    for (Contract gone : CargoService.failGone(data, data.profile(HOLDER))) if (gone == c) { failed = true; seen[2]++; }
                }

                // ---- invariants after every step
                Set<Integer> both = new HashSet<>(c.delivered); both.retainAll(c.lost);
                assertTrue(both.isEmpty(), "an index is never both delivered and lost: " + both);
                for (int i : c.delivered) assertTrue(i >= 0 && i < pods, "delivered index in range");
                for (int i : c.lost) assertTrue(i >= 0 && i < pods, "lost index in range");
                for (int i = 0; i < pods; i++) assertTrue(paid[i] <= 1, "index " + i + " paid " + paid[i] + " times");
                boolean all = true; for (int i = 0; i < pods; i++) all &= c.delivered.contains(i) || c.lost.contains(i);
                assertEquals(all, c.settled(), "settled() iff every index is delivered or lost");
                if (c.settled() && !failed) { assertFalse(data.profile(HOLDER).contracts.containsKey(id), "a settled contract is dropped"); assertTrue(data.voided.contains(id), "a settled contract is voided"); }
                for (var e : data.profiles.entrySet()) {
                    int rep = e.getValue().reputation, before = lastRep.getOrDefault(e.getKey(), 0);
                    assertTrue(rep >= before, "reputation of " + e.getKey() + " fell from " + before + " to " + rep); lastRep.put(e.getKey(), rep);
                    if (!e.getKey().equals(HOLDER)) assertTrue(e.getValue().pendingCrates.isEmpty(), "strangers are never owed crates");
                }
                int crates = holderCrates + data.profile(HOLDER).pendingCrates.size();
                assertTrue(crates <= 1, "the holder got " + crates + " crates for one contract");
                if (!data.profile(HOLDER).pendingCrates.isEmpty()) got = data.profile(HOLDER).pendingCrates.getFirst();
                if (c.settled()) {
                    // only deliveries, losses and expiry settle a contract; abandoning or failing it drops it unsettled
                    assertFalse(failed, "a failed contract never settles afterwards");
                    assertEquals(c.delivered.isEmpty() ? 0 : 1, crates, "a settled contract owes one crate iff the holder delivered a pod");
                    if (crates == 1) assertEquals(c.delivered.size() == pods && !c.anyReduced ? crate : crate.lower(), got, "crate rank");
                }
            }
            if (c.settled()) seen[holderCrates + data.profile(HOLDER).pendingCrates.size() == 1 ? 0 : 1]++;
        });
        for (int n : seen) assertTrue(n > 50, "every branch is exercised: " + Arrays.toString(seen));
    }

    @Test void applyResultQueuesTheHolderCrateAndRetiresSettledContracts() {
        Fuzz.check("applyResult", 4000, (r, log) -> {
            CargoSavedData data = new CargoSavedData(); UUID holder = new UUID(r.nextLong(), r.nextLong());
            GlobalPos a = pos(r, Level.OVERWORLD, 5000), b = pos(r, Level.OVERWORLD, 5000);
            Contract c = new Contract(r.nextLong(), a, b, 1, CrateRank.BRASS, "k", 1 + r.nextInt(4), 0, 10);
            if (r.nextBoolean()) data.profile(holder).contracts.put(c.id, c);
            int pendingBefore = data.profile(holder).pendingCrates.size();
            Delivery.Result result = new Delivery.Result(r.nextInt(100), r.nextBoolean() ? null : CrateRank.GOLD, r.nextBoolean(), r.nextBoolean(), r.nextBoolean() ? null : Fuzz.pick(r, CrateRank.values()));
            log.append(result);
            CargoService.applyResult(data, holder, c, result);
            CargoProfile p = data.profile(holder);
            assertEquals(pendingBefore + (result.holderCrate() == null ? 0 : 1), p.pendingCrates.size());
            if (result.holderCrate() != null) assertEquals(result.holderCrate(), p.pendingCrates.getLast());
            assertEquals(result.settled(), data.voided.contains(c.id));
            if (result.settled()) assertFalse(p.contracts.containsKey(c.id));
            assertTrue(data.isDirty());
        });
    }

    @Test void acceptRefusalOfferStandsAndShopRefusalAgreeWithTheirRules() {
        Fuzz.check("refusals", 5000, (r, log) -> {
            CargoProfile p = new CargoProfile(); int held = r.nextInt(6);
            for (int i = 0; i < held; i++) p.contracts.put((long) i, new Contract(i, pos(r, Level.OVERWORLD, 100), pos(r, Level.OVERWORLD, 100), 1, CrateRank.TIN, "k", 1, 0, 1));
            int playerRank = r.nextInt(8) - 1;
            Offer offer = new Offer(r.nextLong(), pos(r, Level.OVERWORLD, 9000), r.nextInt(8) - 1, CrateRank.TIN, "k", 1, 1, 1);
            String refusal = CargoService.acceptRefusal(p, offer, playerRank);
            String expected = offer.rankLevel() > playerRank ? "message.skydock.cargo_rank_locked" : held >= CargoService.MAX_CONTRACTS ? "message.skydock.cargo_full" : "";
            assertEquals(expected, refusal);

            // boards: offers bound for unresolved terminals drop, an emptied board forgets its search time
            Map<GlobalPos, CargoSavedData.Terminal> terminals = new LinkedHashMap<>();
            List<GlobalPos> sites = new ArrayList<>();
            for (int i = 0, n = r.nextInt(6); i < n; i++) { GlobalPos t = pos(r, Fuzz.pick(r, DIMENSIONS), 2000); sites.add(t); terminals.put(t, new CargoSavedData.Terminal(t, t.pos(), r.nextInt(3) != 0)); }
            GlobalPos at = pos(r, Level.OVERWORLD, 2000);
            List<Offer> board = new ArrayList<>();
            for (int i = 0, n = r.nextInt(5); i < n; i++) {
                GlobalPos d = !sites.isEmpty() && r.nextBoolean() ? Fuzz.pick(r, sites) : r.nextBoolean() && !sites.isEmpty()
                        ? GlobalPos.of(Fuzz.pick(r, sites).dimension(), Fuzz.pick(r, sites).pos().offset(r.nextInt(140) - 70, 0, r.nextInt(140) - 70)) : pos(r, Fuzz.pick(r, DIMENSIONS), 2000);
                board.add(new Offer(i, d, 1, CrateRank.TIN, "k", 1, 1, 1));
            }
            if (r.nextInt(5) != 0) p.offers.put(at, board);
            if (r.nextBoolean()) p.offersRefreshed.put(at, (long) r.nextInt(100000));
            Long refreshedBefore = p.offersRefreshed.get(at); int sizeBefore = board.size();
            Offer probe = board.isEmpty() || r.nextInt(4) == 0 ? offer : Fuzz.pick(r, board);
            boolean onBoardBefore = p.offers.containsKey(at) && board.contains(probe);
            boolean stands = CargoService.offerStands(p, at, probe, terminals);
            assertEquals(onBoardBefore && CargoService.resolveDestination(terminals, probe.destination()) != null, stands, "offer stands");
            if (p.offers.containsKey(at)) {
                for (Offer o : p.offers.get(at)) assertNotNull(CargoService.resolveDestination(terminals, o.destination()), "every remaining offer resolves");
                boolean dropped = sizeBefore != p.offers.get(at).size();
                if (dropped && p.offers.get(at).isEmpty()) assertFalse(p.offersRefreshed.containsKey(at), "an emptied board forgets its search time");
                else assertEquals(refreshedBefore, p.offersRefreshed.get(at));
            }
            // a resolved destination is the original when registered, else a generated terminal in range in the same dimension
            GlobalPos resolved = CargoService.resolveDestination(terminals, probe.destination());
            if (resolved != null && !resolved.equals(probe.destination())) {
                assertTrue(terminals.get(resolved).generated()); assertEquals(probe.destination().dimension(), resolved.dimension());
                assertTrue(Offers.distance(resolved.pos(), probe.destination().pos()) <= CargoService.SITE_MATCH);
            }

            CargoSavedData data = new CargoSavedData(); data.terminals.putAll(terminals);
            GlobalPos shop = !sites.isEmpty() && r.nextBoolean() ? Fuzz.pick(r, sites) : at;
            CargoSavedData.Terminal site = terminals.get(shop);
            assertEquals(site != null && site.generated() ? "" : "message.skydock.cargo_terminal_unregistered", CargoService.shopRefusal(data, shop));
        });
    }

    // ---------------------------------------------------------------- boards

    @Test void generatedBoardsOnlyOfferFarEnoughSameDimensionDestinations() {
        Fuzz.check("Offers.generate", 4000, (r, log) -> {
            boolean custom = r.nextBoolean(); if (custom) CargoRanks.setData(randomLadder(r)); else CargoRanks.setData(List.of());
            List<Rank> ranks = CargoRanks.ranks(); int top = ranks.getLast().level(), min = ranks.getFirst().minDistance();
            ResourceKey<Level> dim = Fuzz.pick(r, DIMENSIONS); GlobalPos at = pos(r, dim, 20000);
            int spread = Fuzz.pick(r, 50, 500, 3000, 12000);
            List<GlobalPos> near = new ArrayList<>(), wide = new ArrayList<>();
            for (int i = 0, n = r.nextInt(8); i < n; i++) near.add(generateSite(r, at, spread));
            for (int i = 0, n = r.nextInt(8); i < n; i++) wide.add(generateSite(r, at, spread * 4));
            if (r.nextInt(5) == 0 && !near.isEmpty()) near.add(near.getFirst());
            int playerRank = r.nextInt(top + 3) - 1;
            long[] next = {Math.abs(r.nextLong() >> 2)}; long firstId = next[0]; int[] widerCalls = {0};
            List<String> names = r.nextInt(20) == 0 ? List.of() : List.of("cargo.a", "cargo.b", "cargo.c");
            log.append("ranks=").append(ranks).append("\nat=").append(at).append(" playerRank=").append(playerRank).append("\nnear=").append(near).append("\nwide=").append(wide);
            List<Offer> board = Offers.generate(at, playerRank, near, () -> { widerCalls[0]++; return wide; }, RandomSource.create(r.nextLong()), () -> next[0]++, names);
            assertTrue(widerCalls[0] <= 1, "the wider search runs at most once");
            assertTrue(board.size() <= Offers.BOARD_SIZE);
            Set<Long> ids = new HashSet<>();
            for (Offer o : board) {
                assertTrue(ids.add(o.id()), "offer ids are unique"); assertTrue(o.id() >= firstId && o.id() < next[0]);
                assertEquals(at.dimension(), o.destination().dimension(), "same dimension");
                assertNotEquals(at, o.destination(), "never the terminal itself");
                assertTrue(Offers.distance(at.pos(), o.destination().pos()) >= min, "no nearer than the lowest minimum distance: " + o);
                assertTrue(near.contains(o.destination()) || wide.contains(o.destination()));
                assertTrue(o.rankLevel() >= 1 && o.rankLevel() <= top && o.rankLevel() <= Math.max(1, Math.min(playerRank, top)) + 1, "rank " + o.rankLevel());
                assertTrue(o.pods() >= 1 && o.pods() <= Math.min(o.rankLevel(), 4), "pods " + o.pods());
                assertTrue(o.deadlineDays() >= 1, "days"); assertTrue(o.reputationPerPod() >= 0, "pay");
                assertTrue(names.contains(o.cargoKey()));
            }
            boolean anyEligible = false;
            for (GlobalPos d : concat(near, wide)) anyEligible |= d.dimension().equals(at.dimension()) && Offers.distance(at.pos(), d.pos()) >= min;
            if (!names.isEmpty()) assertEquals(anyEligible ? 3 : 0, board.size(), "every slot is filled when any destination qualifies");
        });
    }
    private static List<GlobalPos> concat(List<GlobalPos> a, List<GlobalPos> b) { List<GlobalPos> out = new ArrayList<>(a); out.addAll(b); return out; }
    private static GlobalPos generateSite(Random r, GlobalPos at, int spread) {
        return switch (r.nextInt(10)) {
            case 0 -> at;
            case 1 -> GlobalPos.of(Fuzz.pick(r, DIMENSIONS), at.pos().offset(r.nextInt(2 * spread + 1) - spread, 0, r.nextInt(2 * spread + 1) - spread));
            case 2 -> GlobalPos.of(at.dimension(), at.pos().offset(r.nextInt(3) - 1, r.nextInt(200) - 100, r.nextInt(3) - 1));
            default -> GlobalPos.of(at.dimension(), at.pos().offset(r.nextInt(2 * spread + 1) - spread, r.nextInt(100) - 50, r.nextInt(2 * spread + 1) - spread));
        };
    }

    @Test void refreshingABoardKeepsUntakenOffersAndNeverOverfills() {
        Fuzz.check("Offers.refreshIfDue", 6000, (r, log) -> {
            CargoProfile p = new CargoProfile(); GlobalPos at = pos(r, Level.OVERWORLD, 1000), there = pos(r, Level.OVERWORLD, 1000);
            long[] id = {r.nextInt(1000)};
            List<Offer> current = null;
            if (r.nextInt(6) != 0) { current = new ArrayList<>(); for (int i = 0, n = r.nextInt(5); i < n; i++) current.add(new Offer(id[0]++, there, 1 + r.nextInt(5), CrateRank.TIN, "k", 1, 1, 1)); p.offers.put(at, current); }
            Long last = r.nextInt(5) == 0 ? null : (long) r.nextInt(100_000);
            if (last != null) p.offersRefreshed.put(at, last);
            long now = (last == null ? 0 : last) + Fuzz.pick(r, 0L, 1L, Offers.DAY_TICKS - 1, Offers.DAY_TICKS, Offers.DAY_TICKS + 1, (long) r.nextInt(100_000));
            List<Offer> fresh = new ArrayList<>(); for (int i = 0, n = r.nextInt(4); i < n; i++) fresh.add(new Offer(id[0]++, there, 1 + r.nextInt(5), CrateRank.TIN, "k", 1, 1, 1));
            List<Offer> before = current == null ? null : List.copyOf(current);
            log.append("current=").append(before).append(" last=").append(last).append(" now=").append(now).append(" fresh=").append(fresh);
            Supplier<List<Offer>> make = () -> List.copyOf(fresh);
            boolean changed = Offers.refreshIfDue(p, at, now, make);
            List<Offer> after = p.offers.get(at);
            if (before == null || last == null || now - last >= Offers.DAY_TICKS) {
                assertTrue(changed); assertEquals(fresh, after); assertEquals(now, p.offersRefreshed.get(at)); return;
            }
            assertEquals(last, p.offersRefreshed.get(at), "a top-up keeps the board's day");
            assertEquals(!before.equals(after), changed, "returns whether the board changed");
            if (before.size() >= Offers.BOARD_SIZE) { assertEquals(before, after); return; }
            assertTrue(after.size() <= Offers.BOARD_SIZE, "never overfills");
            assertEquals(before, after.subList(0, before.size()), "untaken offers stay, in order");
            Set<Long> ids = new HashSet<>(); for (Offer o : after) assertTrue(ids.add(o.id()), "unique ids");
            Map<Integer, Integer> keptByRank = new HashMap<>(), freshByRank = new HashMap<>(), addedByRank = new HashMap<>();
            before.forEach(o -> keptByRank.merge(o.rankLevel(), 1, Integer::sum)); fresh.forEach(o -> freshByRank.merge(o.rankLevel(), 1, Integer::sum));
            after.subList(before.size(), after.size()).forEach(o -> { assertTrue(fresh.contains(o)); addedByRank.merge(o.rankLevel(), 1, Integer::sum); });
            addedByRank.forEach((rank, n) -> assertTrue(n <= freshByRank.get(rank) - keptByRank.getOrDefault(rank, 0), "a fresh offer only fills a rank no kept offer has"));
        });
    }

    // ---------------------------------------------------------------- ranks

    @Test void rankLookupsAreMonotoneAndRewardsNeverFallBelowTheBase() {
        Fuzz.check("CargoRanks", 3000, (r, log) -> {
            if (r.nextBoolean()) CargoRanks.setData(randomLadder(r)); else CargoRanks.setData(List.of());
            List<Rank> ranks = CargoRanks.ranks(); log.append(ranks);
            int a = Fuzz.edgyInt(r), b = Fuzz.edgyInt(r); if (a > b) { int t = a; a = b; b = t; }
            assertTrue(CargoRanks.forReputation(a).level() <= CargoRanks.forReputation(b).level(), "rank by reputation rises with it");
            double d1 = r.nextDouble() * 1e5, d2 = d1 + r.nextDouble() * 1e5;
            assertTrue(CargoRanks.forDistance(d1).level() <= CargoRanks.forDistance(d2).level(), "rank by distance rises with it");
            int unknown = Fuzz.edgyInt(r);
            Rank byLevel = CargoRanks.byLevel(unknown);
            assertEquals(unknown >= 1 && unknown <= ranks.size() ? unknown : ranks.getFirst().level(), byLevel.level());
            for (Rank rank : ranks) {
                double dist = r.nextDouble() * 6e4;
                int pay = CargoRanks.reputation(rank, dist), farther = CargoRanks.reputation(rank, dist + r.nextDouble() * 1e4);
                assertTrue(pay >= rank.basePerPod(), "reward " + pay + " below base " + rank.basePerPod());
                assertTrue(farther >= pay, "reward rises with distance");
            }
            assertTrue(CargoService.searchRadius(r.nextInt(8)) > 0); assertTrue(CargoService.fallbackRadius() > 0);
        });
    }

    // ---------------------------------------------------------------- shop

    static List<Item> shopItems() { return List.of(Items.DIAMOND, Items.STONE, Items.OAK_LOG, Items.ENDER_PEARL, Items.DIAMOND_SWORD, Items.BREAD); }
    static ShopEntry randomEntry(Random r) {
        int rank = 1 + r.nextInt(6), price = Fuzz.pick(r, 0, 1, r.nextInt(100), r.nextInt(5000), Integer.MAX_VALUE);
        if (r.nextInt(3) == 0) return new ShopEntry(rank, price, ItemStack.EMPTY, ResourceLocation.fromNamespaceAndPath("skydock", "p" + r.nextInt(6)));
        Item item = Fuzz.pick(r, shopItems()); return new ShopEntry(rank, price, new ItemStack(item, 1 + r.nextInt(item.getDefaultMaxStackSize())), null);
    }
    static boolean sameEntry(ShopEntry a, ShopEntry b) {
        return a.rank() == b.rank() && a.price() == b.price() && Objects.equals(a.pattern(), b.pattern()) && ItemStack.matches(a.item(), b.item());
    }

    @Test void buyingOnlyEverSpendsWhatTheBalanceCoversAndNeverCostsRank() {
        Fuzz.check("Shop.buy", 6000, (r, log) -> {
            CargoProfile p = new CargoProfile(); p.reputation = Fuzz.pick(r, 0, r.nextInt(400), r.nextInt(10_000), Integer.MAX_VALUE); p.spent = (int) (r.nextDouble() * p.reputation);
            for (int i = r.nextInt(3); i > 0; i--) p.unlocked.add(ResourceLocation.fromNamespaceAndPath("skydock", "p" + r.nextInt(6)));
            for (int n = 1 + r.nextInt(6); n > 0; n--) {
                ShopEntry e = randomEntry(r); log.append(e.rank()).append('/').append(e.price()).append('/').append(e.pattern()).append(' ');
                int rank = p.peakRank(), spent = p.spent, balance = p.balance(); boolean owned = e.isPattern() && p.unlocked.contains(e.pattern());
                String refusal = Shop.buy(p, e);
                String expected = e.rank() > rank ? "message.skydock.shop_rank" : owned ? "message.skydock.shop_owned" : balance < e.price() ? "message.skydock.shop_funds" : "";
                assertEquals(expected, refusal);
                assertEquals(rank, p.peakRank(), "rank is never lost by spending");
                if (refusal.isEmpty()) { assertEquals(spent + e.price(), p.spent); assertTrue(p.balance() >= 0, "balance stays non-negative"); if (e.isPattern()) assertTrue(p.unlocked.contains(e.pattern())); }
                else assertEquals(spent, p.spent);
            }
        });
    }

    @Test void fingerprintsResolveToTheirEntryAndNothingElse() {
        Fuzz.check("Shop.fingerprint", 3000, (r, log) -> {
            List<ShopEntry> entries = new ArrayList<>(); for (int i = 0, n = r.nextInt(14); i < n; i++) entries.add(randomEntry(r));
            Set<Long> prints = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                long f = Shop.fingerprint(i, entries.get(i));
                assertTrue(f >= 0, "fingerprints are non-negative"); assertTrue(prints.add(f), "entries of one list never share a fingerprint");
                assertSame(entries.get(i), Shop.resolve(entries, f));
            }
            // a reloaded list: an index whose entry changed must not resolve
            List<ShopEntry> reloaded = new ArrayList<>(entries);
            for (int i = 0; i < reloaded.size(); i++) if (r.nextBoolean()) reloaded.set(i, randomEntry(r));
            for (int i = 0; i < entries.size(); i++) {
                ShopEntry hit = Shop.resolve(reloaded, Shop.fingerprint(i, entries.get(i)));
                if (hit != null) assertTrue(sameEntry(hit, entries.get(i)), "a stale fingerprint resolved to a different entry at " + i);
            }
            for (int k = 0; k < 20; k++) { long junk = Fuzz.edgyLong(r); ShopEntry hit = Shop.resolve(entries, junk); if (hit != null) assertEquals(junk, Shop.fingerprint((int) (junk & 0xFFFF), hit)); }
        });
    }
}
