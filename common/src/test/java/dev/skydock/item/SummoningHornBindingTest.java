package dev.skydock.item;

import dev.skydock.item.SummoningHornItem.Binding;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummoningHornBindingTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void bindingSurvivesSaveAndLoad() {
        Binding binding = new Binding(Level.NETHER, new BlockPos(-1200, -40, 33_000));
        assertEquals(binding, Binding.load(binding.save()).orElseThrow());
    }
    @Test void anUnboundHornHasNoBinding() {
        assertTrue(Binding.load(new CompoundTag()).isEmpty());
        CompoundTag noDock = new CompoundTag(); noDock.putString("Dimension", "minecraft:overworld");
        assertTrue(Binding.load(noDock).isEmpty());
    }
}
