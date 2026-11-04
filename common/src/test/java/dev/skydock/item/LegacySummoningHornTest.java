package dev.skydock.item;

import dev.skydock.item.WarpAnchorItem.Binding;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacySummoningHornTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    /** Vanilla stand-ins: the horn and the anchor are registered only in the game, and shears wear like the 64-use anchor. */
    @Test void aHornBecomesAnAnchorKeepingItsBindingAndWear() {
        Binding binding = new Binding(Level.NETHER, new BlockPos(40, 70, -12));
        ItemStack horn = new ItemStack(Items.GOAT_HORN);
        horn.set(DataComponents.CUSTOM_DATA, CustomData.of(binding.save())); horn.set(DataComponents.DAMAGE, 17);
        ItemStack anchor = LegacySummoningHornItem.convert(horn, Items.SHEARS);
        assertTrue(anchor.is(Items.SHEARS));
        assertEquals(binding, WarpAnchorItem.binding(anchor).orElseThrow());
        assertEquals(17, anchor.getDamageValue());
        assertEquals(1, anchor.getCount());
    }
    @Test void anUnboundHornBecomesAnUnboundAnchor() {
        ItemStack anchor = LegacySummoningHornItem.convert(new ItemStack(Items.GOAT_HORN), Items.SHEARS);
        assertTrue(WarpAnchorItem.binding(anchor).isEmpty()); assertEquals(0, anchor.getDamageValue());
    }
}
