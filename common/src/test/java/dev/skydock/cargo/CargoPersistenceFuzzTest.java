package dev.skydock.cargo;

import dev.skydock.fuzz.Fuzz;
import dev.skydock.fuzz.NbtFuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Round trips of every cargo save format, and corrupted saves that the world-load paths must survive. */
class CargoPersistenceFuzzTest {
    static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END,
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("skydock:shipyard")),
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("some_mod:deep/dark_realm.v2")));

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    static GlobalPos pos(Random r) {
        int x = r.nextInt(4) == 0 ? Fuzz.pick(r, 29_999_999, -30_000_000, 0) : r.nextInt(200_001) - 100_000;
        int z = r.nextInt(4) == 0 ? Fuzz.pick(r, 29_999_999, -30_000_000, 0) : r.nextInt(200_001) - 100_000;
        return GlobalPos.of(Fuzz.pick(r, DIMENSIONS), new BlockPos(x, r.nextInt(4064) - 2032, z));
    }
    static String key(Random r) { return Fuzz.pick(r, "cargo.skydock.name.silk", "", "silk", "cargo.mod.ünïcødé", "a\"quote'", "x".repeat(r.nextInt(300))); }

    static Contract contract(Random r) {
        int pods = Fuzz.pick(r, 0, 1, 2, 3, 4, r.nextInt(40));
        Contract c = new Contract(Fuzz.edgyLong(r), pos(r), pos(r), r.nextInt(7) - 1, Fuzz.pick(r, CrateRank.values()), key(r), pods, Fuzz.edgyLong(r), Fuzz.edgyInt(r));
        for (int i = 0; i < pods; i++) switch (r.nextInt(3)) { case 0 -> c.delivered.add(i); case 1 -> c.lost.add(i); default -> {} }
        c.anyReduced = r.nextBoolean();
        return c;
    }
    static Offer offer(Random r) { return new Offer(Fuzz.edgyLong(r), pos(r), Fuzz.edgyInt(r), Fuzz.pick(r, CrateRank.values()), key(r), Fuzz.edgyInt(r), Fuzz.edgyInt(r), Fuzz.edgyInt(r)); }

    static CargoProfile profile(Random r, List<GlobalPos> boards) {
        CargoProfile p = new CargoProfile(); p.reputation = Fuzz.edgyInt(r); p.spent = Fuzz.edgyInt(r);
        for (GlobalPos at : boards) if (r.nextBoolean()) {
            List<Offer> list = new ArrayList<>(); for (int i = r.nextInt(4); i > 0; i--) list.add(offer(r));
            p.offers.put(at, list); if (r.nextBoolean()) p.offersRefreshed.put(at, Fuzz.edgyLong(r));
        }
        for (int i = r.nextInt(4); i > 0; i--) { Contract c = contract(r); p.contracts.put(c.id, c); }
        for (int i = r.nextInt(4); i > 0; i--) p.unlocked.add(ResourceLocation.fromNamespaceAndPath(Fuzz.pick(r, "skydock", "other"), "p_" + r.nextInt(50)));
        for (int i = r.nextInt(4); i > 0; i--) p.pendingCrates.add(Fuzz.pick(r, CrateRank.values()));
        for (int i = r.nextInt(3); i > 0; i--) p.expired.add(key(r));
        return p;
    }

    static void assertSameContract(Contract a, Contract b) {
        assertEquals(a.id, b.id); assertEquals(a.origin, b.origin); assertEquals(a.destination, b.destination); assertEquals(a.rankLevel, b.rankLevel);
        assertEquals(a.crate, b.crate); assertEquals(a.cargoKey, b.cargoKey); assertEquals(a.pods, b.pods); assertEquals(a.deadline, b.deadline);
        assertEquals(a.reputationPerPod, b.reputationPerPod); assertEquals(a.delivered, b.delivered); assertEquals(a.lost, b.lost); assertEquals(a.anyReduced, b.anyReduced);
        assertEquals(a.settled(), b.settled());
    }
    static void assertSameProfile(CargoProfile a, CargoProfile b) {
        assertEquals(a.reputation, b.reputation); assertEquals(a.spent, b.spent); assertEquals(a.offers, b.offers); assertEquals(a.offersRefreshed, b.offersRefreshed);
        assertEquals(new ArrayList<>(a.offers.keySet()), new ArrayList<>(b.offers.keySet()), "board order");
        assertEquals(a.contracts.keySet(), b.contracts.keySet()); a.contracts.forEach((id, c) -> assertSameContract(c, b.contracts.get(id)));
        assertEquals(a.unlocked, b.unlocked); assertEquals(a.pendingCrates, b.pendingCrates); assertEquals(a.expired, b.expired);
    }

    // ---------------------------------------------------------------- round trips

    @Test void contractsOffersPodsAndRanksRoundTripExactly() {
        Fuzz.check("cargo records round trip", 5000, (r, log) -> {
            Contract c = contract(r); log.append(c.save());
            assertSameContract(c, Contract.load(c.save()));
            Offer o = offer(r); assertEquals(o, Offer.load(o.save()));
            PodTag tag = new PodTag(Fuzz.edgyLong(r), Fuzz.edgyInt(r), r.nextBoolean()); assertEquals(tag, PodTag.load(tag.save()));
            PodCargo cargo = new PodCargo(tag, key(r), pos(r)); assertEquals(Optional.of(cargo), PodCargo.load(cargo.save()));
            // block entities merge the pod's keys into their own tag
            CompoundTag be = new CompoundTag(); be.putString("id", "skydock:cargo_pod"); be.putInt("x", 3); be.merge(cargo.save());
            assertEquals(Optional.of(cargo), PodCargo.load(be));
            Rank rank = new Rank(Fuzz.edgyInt(r), key(r), Fuzz.edgyInt(r), Fuzz.edgyInt(r), Fuzz.edgyInt(r), Fuzz.edgyInt(r), Fuzz.pick(r, CrateRank.values()), Fuzz.edgyInt(r));
            assertEquals(rank, Rank.load(rank.save()));
        });
    }

    @Test void profilesRoundTripExactly() {
        Fuzz.check("CargoProfile round trip", 3000, (r, log) -> {
            List<GlobalPos> boards = new ArrayList<>(); for (int i = r.nextInt(5); i > 0; i--) boards.add(pos(r));
            CargoProfile p = profile(r, boards); CompoundTag saved = p.save(); log.append(saved);
            CargoProfile back = CargoProfile.load(saved);
            assertSameProfile(p, back);
            assertEquals(saved, back.save(), "saving a loaded profile writes the same tag");
        });
    }

    static CargoSavedData savedData(Random r) {
        CargoSavedData data = new CargoSavedData();
        List<GlobalPos> terminals = new ArrayList<>();
        for (int i = r.nextInt(6); i > 0; i--) { GlobalPos t = pos(r); terminals.add(t); data.terminals.put(t, new CargoSavedData.Terminal(t, BlockPos.of(Fuzz.edgyLong(r)), r.nextBoolean())); }
        for (int i = r.nextInt(3); i > 0; i--) data.retired.add(pos(r));
        for (int i = r.nextInt(4); i > 0; i--) data.profiles.put(new UUID(r.nextLong(), r.nextLong()), profile(r, terminals));
        data.wind = new CargoSavedData.Wind(r.nextDouble() * Math.PI * 2, r.nextDouble() * 2, Fuzz.edgyLong(r));
        data.nextContract = Fuzz.edgyLong(r);
        for (int i = r.nextInt(6); i > 0; i--) data.voided.add(Fuzz.edgyLong(r));
        return data;
    }

    @Test void worldCargoStateRoundTripsExactly() {
        Fuzz.check("CargoSavedData round trip", 2000, (r, log) -> {
            CargoSavedData data = savedData(r); CompoundTag saved = data.save(new CompoundTag(), null); log.append(saved);
            CargoSavedData back = CargoSavedData.load(saved, null);
            assertEquals(saved, back.save(new CompoundTag(), null));
            assertEquals(data.terminals, back.terminals); assertEquals(data.retired, back.retired); assertEquals(data.voided, back.voided);
            assertEquals(data.wind, back.wind); assertEquals(data.nextContract, back.nextContract);
            assertEquals(new ArrayList<>(data.profiles.keySet()), new ArrayList<>(back.profiles.keySet()));
            data.profiles.forEach((id, p) -> assertSameProfile(p, back.profiles.get(id)));
        });
    }

    @Test void boardsAtTerminalsThatAreGoneAreDroppedOnLoad() {
        Fuzz.check("CargoSavedData drops orphan boards", 1000, (r, log) -> {
            CargoSavedData data = savedData(r); UUID who = new UUID(1, 2);
            List<GlobalPos> strays = List.of(pos(r), pos(r)); data.profiles.put(who, profile(r, strays));
            for (GlobalPos s : strays) data.terminals.remove(s);
            CargoSavedData back = CargoSavedData.load(data.save(new CompoundTag(), null), null);
            for (CargoProfile p : back.profiles.values()) {
                assertTrue(back.terminals.keySet().containsAll(p.offers.keySet())); assertTrue(back.terminals.keySet().containsAll(p.offersRefreshed.keySet()));
            }
        });
    }

    // ---------------------------------------------------------------- corrupted saves

    @Test void corruptedCargoSavesNeverThrowOutOfWorldLoad() {
        Fuzz.check("CargoSavedData corrupted", 6000, (r, log) -> {
            CompoundTag saved = savedData(r).save(new CompoundTag(), null);
            CompoundTag bad = NbtFuzz.corrupt(r, saved, Fuzz.pick(r, .02, .1, .3), log);
            CargoSavedData back = CargoSavedData.load(bad, null);
            back.save(new CompoundTag(), null); // the next world save must not throw either
            for (CargoProfile p : back.profiles.values()) {
                for (Contract c : p.contracts.values()) { assertNotNull(c.origin); assertNotNull(c.destination); assertNotNull(c.crate); assertNotNull(c.cargoKey); }
                for (List<Offer> board : p.offers.values()) for (Offer o : board) { assertNotNull(o.destination()); assertNotNull(o.crate()); }
                p.pendingCrates.forEach(Assertions_::notNull); p.unlocked.forEach(Assertions_::notNull);
            }
        });
    }

    @Test void oneDamagedContractCostsNothingElse() {
        Fuzz.check("CargoSavedData isolates damage", 3000, (r, log) -> {
            CargoSavedData data = savedData(r); CompoundTag saved = data.save(new CompoundTag(), null);
            List<CompoundTag> contracts = new ArrayList<>();
            for (Tag prof : saved.getList("Profiles", Tag.TAG_COMPOUND)) for (Tag c : ((CompoundTag) prof).getCompound("Profile").getList("Contracts", Tag.TAG_COMPOUND)) contracts.add((CompoundTag) c);
            if (contracts.isEmpty()) return;
            CompoundTag victim = Fuzz.pick(r, contracts); long victimId = victim.getLong("Id");
            List<String> keys = new ArrayList<>(victim.getAllKeys()); keys.remove("Id");
            String k = Fuzz.pick(r, keys); victim.put(k, NbtFuzz.randomTag(r, 0)); log.append("contract ").append(victimId).append(' ').append(k).append('=').append(victim.get(k));
            CargoSavedData back = CargoSavedData.load(saved, null);
            data.profiles.forEach((id, p) -> p.contracts.forEach((cid, c) -> { if (cid != victimId) assertSameContract(c, back.profiles.get(id).contracts.get(cid)); }));
            assertEquals(data.terminals, back.terminals); assertEquals(data.voided, back.voided);
        });
    }

    @Test void corruptedPodDataReadsAsNoCargoRatherThanThrowing() {
        Fuzz.check("PodCargo corrupted", 6000, (r, log) -> {
            PodCargo cargo = new PodCargo(new PodTag(Fuzz.edgyLong(r), Fuzz.edgyInt(r), r.nextBoolean()), key(r), pos(r));
            CompoundTag bad = NbtFuzz.corrupt(r, cargo.save(), Fuzz.pick(r, .1, .3, .6), log);
            Optional<PodCargo> back = PodCargo.load(bad);
            back.ifPresent(c -> { assertNotNull(c.tag()); assertNotNull(c.destination()); assertNotNull(c.cargoKey()); c.save(); });
            PodTag.load(NbtFuzz.corrupt(r, cargo.tag().save(), .5, log));
            CargoProfile.load(NbtFuzz.corrupt(r, profile(r, List.of(pos(r))).save(), .2, log)).save();
        });
    }

    private static final class Assertions_ { static void notNull(Object o) { assertNotNull(o); } }
}
