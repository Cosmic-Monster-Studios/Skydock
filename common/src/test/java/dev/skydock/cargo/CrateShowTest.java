package dev.skydock.cargo;

import dev.skydock.cargo.CrateShow.Phase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrateShowTest {
    private static final List<ResourceLocation> SHOP = List.of(ResourceLocation.parse("skydock:brig_hauler"), ResourceLocation.parse("skydock:cruiser_cutter"), ResourceLocation.parse("skydock:scout_skiff"));

    @Test void biggerCratesPlayLonger() {
        assertEquals(List.of(10, 20, 40, 60, 100), Arrays.stream(CrateRank.values()).map(CrateShow::duration).toList());
    }
    @Test void everyShowEndsInABurst() {
        for (CrateRank rank : CrateRank.values()) {
            int end = CrateShow.duration(rank);
            assertNotEquals(Phase.BURST, CrateShow.phase(rank, 0), rank.name());
            assertNotEquals(Phase.BURST, CrateShow.phase(rank, end - 1), rank.name());
            assertEquals(Phase.BURST, CrateShow.phase(rank, end), rank.name());
            assertEquals(Phase.DONE, CrateShow.phase(rank, end + 1), rank.name());
        }
    }
    @Test void aetherShowsABeamBeforeTheBurst() {
        assertEquals(Phase.RISE, CrateShow.phase(CrateRank.AETHER, 0));
        assertEquals(Phase.BEAM, CrateShow.phase(CrateRank.AETHER, CrateShow.duration(CrateRank.AETHER) - 1));
    }
    @Test void aetherAlwaysUnlocksAnUnownedPattern() {
        Set<ResourceLocation> owned = Set.of(SHOP.get(0), SHOP.get(2));
        for (long seed = 0; seed < 50; seed++) assertEquals(Optional.of(SHOP.get(1)), CrateShow.patternPrize(CrateRank.AETHER, owned, SHOP, RandomSource.create(seed)));
        Set<ResourceLocation> seen = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) seen.add(CrateShow.patternPrize(CrateRank.AETHER, Set.of(), SHOP, RandomSource.create(seed)).orElseThrow());
        assertEquals(Set.copyOf(SHOP), seen);
    }
    @Test void tinNeverUnlocksAPattern() {
        for (long seed = 0; seed < 50; seed++) assertEquals(Optional.empty(), CrateShow.patternPrize(CrateRank.TIN, Set.of(), SHOP, RandomSource.create(seed)));
    }
    @Test void ownedEverythingMeansARefund() {
        assertEquals(Optional.empty(), CrateShow.patternPrize(CrateRank.AETHER, Set.copyOf(SHOP), SHOP, RandomSource.create(1)));
    }
}
