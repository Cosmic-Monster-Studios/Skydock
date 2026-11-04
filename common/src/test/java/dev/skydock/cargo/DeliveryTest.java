package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryTest {
    static final GlobalPos ORIGIN = GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 100, 0));
    static final GlobalPos DEST = GlobalPos.of(Level.OVERWORLD, new BlockPos(600, 100, 0));
    // rank 2 (Mate): base 25, min distance 600 -> 25 per pod at 600 blocks
    static final int FULL = 25;

    static Contract contract(int pods, CrateRank crate) { return new Contract(7, ORIGIN, DEST, 2, crate, "cargo.skydock.grain", pods, 24000); }
    static PodTag pod(Contract c, int i, boolean ejected) { return new PodTag(c.id, i, ejected); }

    @Test void fullRewardOnTimeAndIntact() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        Delivery.Result r = Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST);
        assertEquals(FULL, r.reputation()); assertTrue(r.full()); assertEquals(FULL, p.reputation); assertTrue(c.delivered.contains(0)); assertFalse(c.anyReduced);
    }
    @Test void contractPaysTheReputationItsOfferShowed() {
        // A Deckhand-accepted fallback contract 1042 blocks out pays Mate's rate for the distance, as its offer said.
        GlobalPos far = GlobalPos.of(Level.OVERWORLD, new BlockPos(1042, 100, 0));
        int shown = CargoRanks.reputation(CargoRanks.forDistance(1042), 1042);
        Contract c = new Contract(8, ORIGIN, far, 1, CrateRank.COPPER, "cargo.skydock.grain", 2, 24000, shown);
        CargoProfile holder = new CargoProfile(), thief = new CargoProfile();
        assertEquals(shown, Delivery.deliver(holder, c, pod(c, 0, false), true, false, far).reputation());
        assertEquals(shown / 2, Delivery.deliver(thief, c, pod(c, 1, false), false, false, far).reputation());
        assertNotEquals(CargoRanks.reputation(CargoRanks.ranks().getFirst(), 1042), shown);
    }
    @Test void secondDeliveryOfTheSamePodPaysNothing() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST);
        assertEquals(new Delivery.Result(0, null, false, false, null), Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST));
        assertEquals(FULL, p.reputation);
    }
    @Test void latePodHalvesAndLowersTheCrate() {
        Contract c = contract(1, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        Delivery.Result r = Delivery.deliver(p, c, pod(c, 0, false), true, true, DEST);
        assertEquals(FULL / 2, r.reputation()); assertFalse(r.full()); assertTrue(c.anyReduced); assertEquals(CrateRank.COPPER, r.crate());
    }
    @Test void ejectedPodHalvesAndLowersTheCrate() {
        Contract c = contract(1, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        Delivery.Result r = Delivery.deliver(p, c, pod(c, 0, true), true, false, DEST);
        assertEquals(FULL / 2, r.reputation()); assertTrue(c.anyReduced); assertEquals(CrateRank.COPPER, r.crate());
    }
    @Test void thiefGetsHalfAndNoCrateAndTheHolderLosesThePod() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile thief = new CargoProfile();
        Delivery.Result r = Delivery.deliver(thief, c, pod(c, 0, false), false, false, DEST);
        assertEquals(FULL / 2, r.reputation()); assertNull(r.crate()); assertEquals(FULL / 2, thief.reputation);
        assertTrue(c.lost.contains(0)); assertFalse(c.delivered.contains(0));
    }
    @Test void aStrangerNeverEarnsACrate() {
        Contract c = contract(4, CrateRank.BRASS); CargoProfile thief = new CargoProfile();
        for (int i = 0; i < 4; i++) assertNull(Delivery.deliver(thief, c, pod(c, i, false), false, false, DEST).crate());
        assertEquals(4 * (FULL / 2), thief.reputation);
    }
    @Test void reputationStopsAtTheIntegerMaximum() {
        Contract c = contract(1, CrateRank.BRASS); CargoProfile p = new CargoProfile(); p.reputation = Integer.MAX_VALUE - 3;
        Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST);
        assertEquals(Integer.MAX_VALUE, p.reputation);
        assertEquals(Integer.MAX_VALUE, CargoProfile.saturatedAdd(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(7, CargoProfile.saturatedAdd(3, 4));
    }
    @Test void crateIsPaidOnceWhenTheLastPodArrives() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile holder = new CargoProfile();
        assertNull(Delivery.deliver(holder, c, new PodTag(c.id, 0, false), true, false, DEST).crate());
        Delivery.Result last = Delivery.deliver(holder, c, new PodTag(c.id, 1, false), true, false, DEST);
        assertEquals(CrateRank.BRASS, last.crate()); assertTrue(last.settled()); assertTrue(c.settled());
    }
    @Test void aLostPodLowersTheHoldersCrate() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile holder = new CargoProfile();
        Delivery.deliver(new CargoProfile(), c, pod(c, 1, false), false, false, DEST);
        assertEquals(CrateRank.COPPER, Delivery.deliver(holder, c, pod(c, 0, false), true, false, DEST).crate());
    }
    @Test void strangerDeliveringTheLastPodStillPaysTheHolderALowerCrate() {
        Contract c = contract(2, CrateRank.BRASS);
        Delivery.deliver(new CargoProfile(), c, pod(c, 0, false), true, false, DEST);
        Delivery.Result r = Delivery.deliver(new CargoProfile(), c, pod(c, 1, false), false, false, DEST);
        assertTrue(r.settled()); assertEquals(CrateRank.COPPER, r.holderCrate()); assertNull(r.crate());
    }
    @Test void strangerSettlingWhenTheHolderDeliveredNothingPaysTheHolderNothing() {
        Contract c = contract(1, CrateRank.BRASS);
        Delivery.Result r = Delivery.deliver(new CargoProfile(), c, pod(c, 0, false), false, false, DEST);
        assertTrue(r.settled()); assertNull(r.holderCrate()); assertNull(r.crate());
    }
    @Test void strangerNotSettlingOwesTheHolderNoCrate() {
        Contract c = contract(2, CrateRank.BRASS);
        assertNull(Delivery.deliver(new CargoProfile(), c, pod(c, 0, false), false, false, DEST).holderCrate());
    }
    @Test void podOfAnotherContractOrOutOfRangePaysNothing() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        assertEquals(0, Delivery.deliver(p, c, new PodTag(c.id + 1, 0, false), true, false, DEST).reputation());
        assertEquals(0, Delivery.deliver(p, c, pod(c, 2, false), true, false, DEST).reputation());
        assertEquals(0, Delivery.deliver(p, c, pod(c, -1, false), true, false, DEST).reputation());
        assertEquals(0, p.reputation); assertTrue(c.delivered.isEmpty());
    }
    @Test void deliveryAtTheOriginIsRefused() {
        Contract c = contract(1, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        assertEquals(new Delivery.Result(0, null, false, false, null), Delivery.deliver(p, c, pod(c, 0, false), true, false, ORIGIN));
        assertEquals(0, p.reputation); assertTrue(c.delivered.isEmpty());
    }
    @Test void orphanPodPaysHalfDeckhandAndNoCrate() {
        CargoProfile p = new CargoProfile();
        Delivery.Result r = Delivery.orphan(p, 9, ORIGIN, DEST, Set.of());
        assertEquals(5, r.reputation()); assertNull(r.crate()); assertEquals(5, p.reputation);
        assertEquals(0, Delivery.orphan(p, 9, ORIGIN, ORIGIN, Set.of()).reputation());
    }
    @Test void abandonedPodsPayNothing() {
        CargoProfile p = new CargoProfile();
        assertEquals(0, Delivery.orphan(p, 9, ORIGIN, DEST, Set.of(9L)).reputation()); assertEquals(0, p.reputation);
    }

    @Test void acceptsSignalsRefusalApartFromPayment() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        assertTrue(Delivery.accepts(c, pod(c, 0, false), DEST));
        assertFalse(Delivery.accepts(c, pod(c, 0, false), ORIGIN));
        assertFalse(Delivery.accepts(c, pod(c, 5, false), DEST));
        Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST);
        assertFalse(Delivery.accepts(c, pod(c, 0, false), DEST));
        assertFalse(Delivery.acceptsOrphan(7, ORIGIN, ORIGIN, Set.of()));
        assertFalse(Delivery.acceptsOrphan(7, ORIGIN, DEST, Set.of(7L)));
        assertTrue(Delivery.acceptsOrphan(7, ORIGIN, DEST, Set.of()));
    }
    @Test void losingTheLastPodSettlesTheContract() {
        Contract c = contract(2, CrateRank.BRASS); CargoProfile p = new CargoProfile();
        Delivery.deliver(p, c, pod(c, 0, false), true, false, DEST);
        Delivery.Result r = Delivery.lose(c, 1);
        assertTrue(r.settled()); assertEquals(CrateRank.COPPER, r.holderCrate()); assertEquals(0, r.reputation()); assertNull(r.crate());
        assertTrue(c.lost.contains(1));
        assertEquals(new Delivery.Result(0, null, false, false, null), Delivery.lose(c, 1), "a pod is lost only once");
    }
    @Test void losingTheLastPodWithNothingDeliveredPaysNoCrate() {
        Contract c = contract(2, CrateRank.BRASS);
        Delivery.Result first = Delivery.lose(c, 0);
        assertFalse(first.settled()); assertNull(first.holderCrate());
        Delivery.Result last = Delivery.lose(c, 1);
        assertTrue(last.settled()); assertNull(last.holderCrate());
        assertFalse(Delivery.lose(c, 5).settled(), "an index outside the contract is ignored");
    }
}
