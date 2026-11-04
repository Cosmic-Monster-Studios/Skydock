package dev.skydock.ship;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CellClearTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void looseItemsAndOrbsDoNotKeepAPodOut() {
        assertTrue(ShipManager.looseInCell(ItemEntity.class));
        assertTrue(ShipManager.looseInCell(ExperienceOrb.class));
        assertFalse(ShipManager.looseInCell(Zombie.class));
        assertFalse(ShipManager.looseInCell(Player.class));
        assertFalse(ShipManager.looseInCell(dev.skydock.cargo.PodEntity.class));
    }
}
