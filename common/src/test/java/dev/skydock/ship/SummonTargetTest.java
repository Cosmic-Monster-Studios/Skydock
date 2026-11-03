package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
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
}
