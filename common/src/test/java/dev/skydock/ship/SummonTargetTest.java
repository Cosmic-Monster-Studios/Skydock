package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummonTargetTest {
    private static final BlockPos DOCK = new BlockPos(10, 64, -20);

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Ship launched() {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.dimension = Level.OVERWORLD; ship.dock = DOCK; return ship;
    }

    @Test void picksTheActiveShipFromThatDock() { assertTrue(ShipManager.summonable(launched(), Level.OVERWORLD, DOCK)); }
    @Test void ignoresOtherDocksAndDimensions() {
        assertFalse(ShipManager.summonable(launched(), Level.OVERWORLD, DOCK.east()));
        assertFalse(ShipManager.summonable(launched(), Level.NETHER, DOCK));
    }
    @Test void ignoresShipsMidTransfer() {
        Ship ship = launched(); ship.phase = "redocking";
        assertFalse(ShipManager.summonable(ship, Level.OVERWORLD, DOCK));
    }
    @Test void anythingStandingOnTheDeckOrInsideTheHullIsAboard() {
        AABB hull = new AABB(0, 60, 0, 8, 66, 12), deck = ShipManager.deckBox(hull);
        assertEquals(new AABB(0, 60, 0, 8, 68, 12), deck, "two blocks above the hull top, nothing beside or below it");
        assertTrue(deck.intersects(new AABB(3.7, 66, 4.7, 4.3, 67.8, 5.3)), "a villager standing on deck");
        assertTrue(deck.intersects(new AABB(3.7, 61, 4.7, 4.3, 62.8, 5.3)), "a pet in the cabin");
        assertFalse(deck.intersects(new AABB(9.7, 60, 4.7, 10.3, 61.8, 5.3)), "a mob on the ground beside the hull");
    }
}
