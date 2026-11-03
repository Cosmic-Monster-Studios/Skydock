package dev.skydock.cargo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CargoRanksTest {
    @Test void ranksClimbWithReputation() {
        assertEquals(1, CargoRanks.forReputation(0).level());
        assertEquals(1, CargoRanks.forReputation(99).level());
        assertEquals(2, CargoRanks.forReputation(100).level());
        assertEquals(5, CargoRanks.forReputation(1_000_000).level());
    }
    @Test void podRewardScalesWithDistanceOverTheBandMinimum() {
        Rank deckhand = CargoRanks.ranks().getFirst();
        assertEquals(10, CargoRanks.reputation(deckhand, 300));
        assertEquals(20, CargoRanks.reputation(deckhand, 600));
        assertEquals(10, CargoRanks.reputation(deckhand, 100)); // never below the base
    }
    @Test void distanceDecidesTheRankItQualifiesFor() {
        assertEquals(1, CargoRanks.forDistance(250).level());
        assertEquals(3, CargoRanks.forDistance(1_300).level());
        assertEquals(5, CargoRanks.forDistance(9_000).level());
    }
    @Test void crateRankLowersToTinAtTheBottom() {
        assertEquals(CrateRank.BRASS, CrateRank.GOLD.lower());
        assertEquals(CrateRank.TIN, CrateRank.TIN.lower());
    }
}
