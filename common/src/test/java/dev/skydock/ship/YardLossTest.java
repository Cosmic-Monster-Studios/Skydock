package dev.skydock.ship;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YardLossTest {
    private static BlockState AIR;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); AIR = Blocks.AIR.defaultBlockState(); }

    private static Map<BlockPos, BlockState> hull() {
        Map<BlockPos, BlockState> hull = new LinkedHashMap<>();
        hull.put(new BlockPos(0, 0, 0), Blocks.SPRUCE_PLANKS.defaultBlockState());
        hull.put(new BlockPos(1, 0, 0), Blocks.CHEST.defaultBlockState());
        hull.put(new BlockPos(0, 1, 0), AIR);
        return hull;
    }

    @Test void aHullWhoseEveryBlockReadsAirHasVanished() {
        assertTrue(ShipManager.hullVanished(hull(), p -> AIR));
    }
    @Test void oneBlockLeftMeansTheYardIsStillThere() {
        assertFalse(ShipManager.hullVanished(hull(), p -> p.equals(new BlockPos(1, 0, 0)) ? Blocks.CHEST.defaultBlockState() : AIR));
        // A block that changed is still a block: the hull was edited, not deleted.
        assertFalse(ShipManager.hullVanished(hull(), p -> p.equals(BlockPos.ZERO) ? Blocks.STONE.defaultBlockState() : AIR));
    }
    @Test void aHullWithNoBlocksCannotVanish() {
        assertFalse(ShipManager.hullVanished(Map.of(BlockPos.ZERO, AIR), p -> AIR));
        assertFalse(ShipManager.hullVanished(Map.of(), p -> AIR));
    }
}
