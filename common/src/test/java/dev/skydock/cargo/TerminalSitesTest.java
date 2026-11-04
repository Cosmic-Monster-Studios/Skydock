package dev.skydock.cargo;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalSitesTest {
    /** The values of data/skydock/worldgen/structure_set/cargo_outposts.json. */
    static RandomSpreadStructurePlacement placement;
    static final long SEED = "skydock-cargo".hashCode();
    static final BlockPos TERMINAL = new BlockPos(-516, 69, -970);

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); placement = new RandomSpreadStructurePlacement(32, 26, RandomSpreadType.LINEAR, 20261103); }

    /** Every chunk within {@code max} blocks on each axis, mapped to its region's potential chunk the way vanilla does, kept when in the band. */
    static Set<ChunkPos> bruteForce(long seed, BlockPos from, int min, int max) {
        Set<ChunkPos> out = new HashSet<>();
        for (int cx = (from.getX() - max) >> 4; cx <= (from.getX() + max) >> 4; cx++)
            for (int cz = (from.getZ() - max) >> 4; cz <= (from.getZ() + max) >> 4; cz++) {
                ChunkPos c = placement.getPotentialStructureChunk(seed, cx, cz);
                double d = Offers.distance(from, new BlockPos(c.getMiddleBlockX(), from.getY(), c.getMiddleBlockZ()));
                if (d >= min && d <= max) out.add(c);
            }
        return out;
    }

    @Test void candidatesMatchVanillaPlacementOverEveryChunk() {
        for (long seed : new long[]{SEED, 0L, -987654321012345L}) {
            List<ChunkPos> got = TerminalSites.candidateChunks(seed, placement, TERMINAL, 300, 3000);
            assertEquals(got.size(), new HashSet<>(got).size(), "no duplicates");
            assertEquals(bruteForce(seed, TERMINAL, 300, 3000), new HashSet<>(got), "seed " + seed);
        }
    }
    @Test void outpostLocateFindsIsACandidate() {
        assertTrue(TerminalSites.candidateChunks(SEED, placement, TERMINAL, 1, 10_000).contains(new ChunkPos(-64, -60)), "/locate answered (-1024, ~, -960)");
    }
    @Test void nearestFirst() {
        List<ChunkPos> got = TerminalSites.candidateChunks(SEED, placement, TERMINAL, 1, 10_000); double last = -1;
        assertTrue(got.size() > 1000, "a 10,000 block radius holds about 1,200 regions, got " + got.size());
        for (ChunkPos c : got) { double d = Offers.distance(TERMINAL, new BlockPos(c.getMiddleBlockX(), 0, c.getMiddleBlockZ())); assertTrue(d >= last); last = d; }
    }
    @Test void capKeepsTheClosestRegions() {
        List<ChunkPos> wide = TerminalSites.candidateChunks(SEED, placement, TERMINAL, 1, 60_000);
        assertTrue(wide.size() <= 4096, "capped, got " + wide.size());
        assertTrue(wide.containsAll(bruteForce(SEED, TERMINAL, 1, 10_000)), "every site within 10,000 blocks survives the cap");
    }
}
