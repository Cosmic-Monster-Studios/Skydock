package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CargoServiceTest {
    static final GlobalPos HERE = GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 70, 0));
    static final GlobalPos THERE = GlobalPos.of(Level.OVERWORLD, new BlockPos(700, 70, 0));

    static Offer offer(int rank) { return new Offer(5, THERE, rank, CrateRank.COPPER, "cargo.skydock.name.silk", 2, 2, 25); }
    static Contract contract(long id, int pods) { return new Contract(id, HERE, THERE, 2, CrateRank.COPPER, "cargo.skydock.name.silk", pods, 48000); }

    @Test void acceptRefusesAboveRank() {
        CargoProfile p = new CargoProfile();
        assertEquals("message.skydock.cargo_rank_locked", CargoService.acceptRefusal(p, offer(2), 1));
        assertEquals("", CargoService.acceptRefusal(p, offer(2), 2));
        assertEquals("", CargoService.acceptRefusal(p, offer(1), 3));
    }

    @Test void acceptRefusesAtThreeContracts() {
        CargoProfile p = new CargoProfile();
        for (long id = 1; id <= 2; id++) p.contracts.put(id, contract(id, 1));
        assertEquals("", CargoService.acceptRefusal(p, offer(1), 1));
        p.contracts.put(3L, contract(3, 1));
        assertEquals("message.skydock.cargo_full", CargoService.acceptRefusal(p, offer(1), 1));
    }

    @Test void deadlineIsDaysOfGameTime() {
        assertEquals(1_000 + 3 * 24_000L, CargoService.deadline(1_000, 3));
        assertEquals(24_000L, CargoService.deadline(0, 1));
    }

    @Test void settledContractIsRemovedAndHolderCrateQueued() {
        CargoSavedData data = new CargoSavedData(); UUID holder = UUID.randomUUID(), stranger = UUID.randomUUID();
        Contract c = contract(9, 2); data.profile(holder).contracts.put(c.id, c);
        Delivery.Result first = Delivery.deliver(data.profile(holder), c, new PodTag(9, 0, false), true, false, THERE);
        CargoService.applyResult(data, holder, c, first);
        assertTrue(data.profile(holder).contracts.containsKey(9L));
        assertTrue(data.profile(holder).pendingCrates.isEmpty());
        Delivery.Result last = Delivery.deliver(data.profile(stranger), c, new PodTag(9, 1, false), false, false, THERE);
        assertTrue(last.settled()); assertEquals(CrateRank.TIN, last.holderCrate());
        CargoService.applyResult(data, holder, c, last);
        assertFalse(data.profile(holder).contracts.containsKey(9L));
        assertEquals(java.util.List.of(CrateRank.TIN), data.profile(holder).pendingCrates);
        assertNull(data.findContract(9));
        assertTrue(data.voided.contains(9L));
        // a leftover duplicate pod of the settled contract pays nothing as an orphan
        assertFalse(Delivery.acceptsOrphan(9, null, THERE, data.voided));
        assertEquals(0, Delivery.orphan(data.profile(stranger), 9, null, THERE, data.voided).reputation());
    }

    @Test void goneDestinationLeavesOrphanPodsDeliverable() {
        CargoSavedData data = new CargoSavedData(); UUID holder = UUID.randomUUID();
        data.terminals.put(HERE, new CargoSavedData.Terminal(HERE, HERE.pos(), true));
        CargoProfile p = data.profile(holder); Contract c = contract(11, 2); p.contracts.put(c.id, c);
        assertEquals(java.util.List.of(c), CargoService.failGone(data, p));
        assertFalse(p.contracts.containsKey(11L));
        assertFalse(data.voided.contains(11L));
        CargoProfile carrier = new CargoProfile();
        assertTrue(Delivery.acceptsOrphan(11, null, HERE, data.voided));
        assertEquals(CargoRanks.ranks().getFirst().basePerPod() / 2, Delivery.orphan(carrier, 11, null, HERE, data.voided).reputation());
    }

    @Test void predictedDestinationFollowsTheGeneratedTerminal() {
        GlobalPos site = GlobalPos.of(Level.OVERWORLD, new BlockPos(696, 63, 8)), real = THERE;
        var terminals = new java.util.LinkedHashMap<GlobalPos, CargoSavedData.Terminal>();
        assertNull(CargoService.resolveDestination(terminals, site));
        terminals.put(real, new CargoSavedData.Terminal(real, real.pos().north(5), true));
        assertEquals(real, CargoService.resolveDestination(terminals, site));
        assertEquals(real, CargoService.resolveDestination(terminals, real));
        assertNull(CargoService.resolveDestination(terminals, GlobalPos.of(Level.OVERWORLD, new BlockPos(900, 63, 0))));
    }

    @Test void emptyBoardWaitsADayBeforeSearchingAgain() {
        CargoProfile p = new CargoProfile();
        assertFalse(CargoService.siteSearchWaits(p, HERE, 100));
        Offers.refreshIfDue(p, HERE, 100, java.util.List::of);
        assertTrue(CargoService.siteSearchWaits(p, HERE, 100 + Offers.DAY_TICKS - 1));
        assertFalse(CargoService.siteSearchWaits(p, HERE, 100 + Offers.DAY_TICKS));
    }
    @Test void fallbackSearchReachesPastTheTopBandsMinimum() {
        assertEquals(7000, CargoService.fallbackRadius()); // Admiral's 5,000 + 2,000 beats Commodore's 6,000
        try {
            CargoRanks.setData(java.util.List.of(new Rank(1, "a", 0, 300, 800, 2, CrateRank.TIN, 10), new Rank(2, "b", 100, 1000, 9000, 2, CrateRank.COPPER, 25), new Rank(3, "c", 400, 2000, Integer.MAX_VALUE, 3, CrateRank.BRASS, 60)));
            assertEquals(9000, CargoService.fallbackRadius());
        } finally { CargoRanks.setData(java.util.List.of()); }
    }
    @Test void podCountsPickTheSingularForOne() {
        assertEquals("one", CargoService.byCount(1, "one", "many"));
        assertEquals("many", CargoService.byCount(0, "one", "many"));
        assertEquals("many", CargoService.byCount(3, "one", "many"));
    }
    @Test void voidLossOfTheLastPodDropsTheContractAndQueuesTheHolderCrate() {
        CargoSavedData data = new CargoSavedData(); UUID holder = UUID.randomUUID();
        Contract c = contract(13, 2); data.profile(holder).contracts.put(c.id, c);
        CargoService.applyResult(data, holder, c, Delivery.deliver(data.profile(holder), c, new PodTag(13, 0, false), true, false, THERE));
        data.setDirty(false);
        CargoService.applyResult(data, holder, c, Delivery.lose(c, 1));
        assertNull(data.findContract(13)); assertTrue(data.voided.contains(13L)); assertTrue(data.isDirty());
        assertEquals(java.util.List.of(CrateRank.TIN), data.profile(holder).pendingCrates);
    }
    @Test void anOverdueContractExpiresTwoDaysPastItsDeadline() {
        CargoSavedData data = new CargoSavedData(); UUID holder = UUID.randomUUID();
        Contract c = contract(21, 3); data.profile(holder).contracts.put(c.id, c);
        CargoService.applyResult(data, holder, c, Delivery.deliver(data.profile(holder), c, new PodTag(21, 0, false), true, false, THERE));
        assertFalse(CargoService.expire(data, holder, c.deadline + CargoService.EXPIRY_GRACE - 1));
        assertTrue(data.profile(holder).contracts.containsKey(21L));
        data.setDirty(false);
        assertTrue(CargoService.expire(data, holder, c.deadline + CargoService.EXPIRY_GRACE));
        CargoProfile p = data.profile(holder);
        assertFalse(p.contracts.containsKey(21L)); assertTrue(data.voided.contains(21L)); assertTrue(data.isDirty());
        assertEquals(java.util.Set.of(1, 2), c.lost, "every undelivered pod counts as lost");
        assertEquals(java.util.List.of(CrateRank.TIN), p.pendingCrates, "the holder delivered a pod, so the lowered crate waits for them");
        assertEquals(java.util.List.of(c.cargoKey), p.expired);
        assertEquals(java.util.List.of(c.cargoKey), CargoProfile.load(p.save()).expired, "the notice survives a restart");
        assertFalse(CargoService.expire(data, holder, Long.MAX_VALUE / 2));
    }
    @Test void anExpiredContractWithNothingDeliveredPaysNoCrate() {
        CargoSavedData data = new CargoSavedData(); UUID holder = UUID.randomUUID();
        Contract c = contract(22, 2); data.profile(holder).contracts.put(c.id, c);
        assertTrue(CargoService.expire(data, holder, c.deadline + CargoService.EXPIRY_GRACE));
        assertTrue(data.profile(holder).pendingCrates.isEmpty()); assertNull(data.findContract(22)); assertTrue(data.voided.contains(22L));
        assertFalse(CargoService.expire(data, UUID.randomUUID(), Long.MAX_VALUE / 2), "no profile, nothing to expire");
    }
    @Test void onlyARegisteredTerminalIsOnTheNetwork() {
        CargoSavedData data = new CargoSavedData();
        assertFalse(CargoService.onNetwork(data, HERE), "a hand-placed terminal nobody registered");
        data.terminals.put(HERE, new CargoSavedData.Terminal(HERE, HERE.pos(), false));
        assertFalse(CargoService.onNetwork(data, HERE), "a predicted site is not a terminal yet");
        data.registerTerminal(HERE, HERE.pos().north(5));
        assertTrue(CargoService.onNetwork(data, HERE));
    }
}
