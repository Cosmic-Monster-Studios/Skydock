package dev.skydock.cargo;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class OffersTest {
    static final GlobalPos AT = at(0, 0);
    static final List<String> NAMES = List.of("a", "b", "c");

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    static GlobalPos at(int x, int z) { return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, z)); }
    static List<Offer> generate(int rank, List<GlobalPos> dests) { AtomicLong ids = new AtomicLong(1); return Offers.generate(AT, rank, dests, RandomSource.create(42), ids::getAndIncrement, NAMES); }
    static double dist(Offer o) { return Offers.distance(AT.pos(), o.destination().pos()); }

    @Test void alwaysThreeOffersWhenAnyDestinationExists() {
        List<Offer> offers = generate(2, List.of(AT, at(50, 0)));
        assertEquals(3, offers.size());
        assertEquals(3, new HashSet<>(offers.stream().map(Offer::id).toList()).size());
        offers.forEach(o -> { assertTrue(o.pods() >= 1 && o.pods() <= 4); assertTrue(NAMES.contains(o.cargoKey())); });
    }
    @Test void oneOfferAtTheRankAboveForAPreview() {
        List<GlobalPos> dests = List.of(at(400, 0), at(900, 0), at(1500, 0), at(3000, 0), at(7000, 0));
        List<Offer> offers = generate(2, dests);
        assertEquals(2, offers.get(0).rankLevel());
        assertEquals(3, offers.get(1).rankLevel());
        assertTrue(offers.get(2).rankLevel() <= 2);
        assertEquals(5, generate(5, dests).get(1).rankLevel());
    }
    @Test void neverOffersTheSameTerminal() {
        List<GlobalPos> dests = List.of(AT, at(500, 0), at(0, 1000), at(2000, 2000));
        for (int rank = 1; rank <= 5; rank++) for (Offer o : generate(rank, dests)) assertNotEquals(AT, o.destination());
    }
    @Test void destinationsComeFromTheRanksBand() {
        GlobalPos near = at(500, 0), far = at(2000, 0);
        List<Offer> offers = generate(1, List.of(near, far));
        Offer own = offers.get(0);
        assertEquals(near, own.destination());
        assertEquals(1, own.rankLevel());
        assertEquals(CargoRanks.reputation(CargoRanks.ranks().get(0), 500), own.reputationPerPod());
        assertEquals(CrateRank.TIN, own.crate());
        assertEquals(2, own.deadlineDays()); // 2 days, nothing extra under 2000 blocks
    }
    @Test void emptyBandFallsBackToTheFarthestAndItsRank() {
        GlobalPos mid = at(1300, 0), far = at(2600, 0); // rank 1 band is 300..800; neither fits
        Offer own = generate(1, List.of(mid, far)).get(0);
        assertEquals(far, own.destination());
        assertEquals(CargoRanks.forDistance(2600).level(), own.rankLevel());
        assertEquals(CargoRanks.forDistance(2600).crate(), own.crate());
        assertEquals(CargoRanks.forDistance(2600).deadlineDays() + 1, own.deadlineDays());
    }
    @Test void noOffersWithNoOtherTerminal() {
        assertTrue(generate(3, List.of(AT)).isEmpty());
        assertTrue(generate(3, List.of()).isEmpty());
        assertTrue(generate(3, List.of(GlobalPos.of(Level.NETHER, new BlockPos(500, 64, 0)))).isEmpty());
    }
    @Test void refreshesAfterADayOrWhenShort() {
        CargoProfile p = new CargoProfile();
        List<Offer> three = generate(1, List.of(at(500, 0)));
        int[] calls = {0};
        java.util.function.Supplier<List<Offer>> make = () -> { calls[0]++; return three; };
        assertTrue(Offers.refreshIfDue(p, AT, 1000, make));
        assertEquals(three, p.offers.get(AT));
        assertFalse(Offers.refreshIfDue(p, AT, 1000 + Offers.DAY_TICKS - 1, make));
        assertEquals(1, calls[0]);
        assertTrue(Offers.refreshIfDue(p, AT, 1000 + Offers.DAY_TICKS, make));
        assertEquals(2, calls[0]);
        p.offers.get(AT).remove(0);
        assertTrue(Offers.refreshIfDue(p, AT, 1000 + Offers.DAY_TICKS + 5, make));
        assertEquals(3, calls[0]);
    }
}
