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
        Delivery.Result first = Delivery.deliver(data.profile(holder), holder, c, new PodTag(9, 0, false), true, false, THERE);
        CargoService.applyResult(data, holder, c, first);
        assertTrue(data.profile(holder).contracts.containsKey(9L));
        assertTrue(data.profile(holder).pendingCrates.isEmpty());
        Delivery.Result last = Delivery.deliver(data.profile(stranger), stranger, c, new PodTag(9, 1, false), false, false, THERE);
        assertTrue(last.settled()); assertEquals(CrateRank.TIN, last.holderCrate());
        CargoService.applyResult(data, holder, c, last);
        assertFalse(data.profile(holder).contracts.containsKey(9L));
        assertEquals(java.util.List.of(CrateRank.TIN), data.profile(holder).pendingCrates);
        assertNull(data.findContract(9));
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
}
