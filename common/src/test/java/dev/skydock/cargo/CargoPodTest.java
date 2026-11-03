package dev.skydock.cargo;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CargoPodTest {
    private static final GlobalPos DEST = GlobalPos.of(Level.OVERWORLD, new BlockPos(120, 70, -45));
    private static final PodCargo CARGO = new PodCargo(new PodTag(77L, 2, true), "silk", DEST);

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static CargoPodBlockEntity entity() {
        return new CargoPodBlockEntity(BlockEntityType.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
    }

    @Test void podItemCarriesItsTagAndCargoName() {
        var lines = CargoPodItem.describe(CARGO);
        assertEquals(PodCargo.load(CARGO.save()).orElseThrow(), CARGO);
        assertEquals("cargo.skydock.name.silk", ((net.minecraft.network.chat.contents.TranslatableContents) lines.get(0).getContents()).getKey());
        assertEquals("120, 70, -45", ((net.minecraft.network.chat.contents.TranslatableContents) lines.get(1).getContents()).getArgs()[0]);
        assertEquals("item.skydock.cargo_pod.ejected", ((net.minecraft.network.chat.contents.TranslatableContents) lines.get(2).getContents()).getKey());
        assertEquals(2, CargoPodItem.describe(new PodCargo(new PodTag(77L, 2, false), "silk", DEST)).size());
    }

    @Test void podTagSurvivesTheBlockEntitySaveRoundTrip() {
        CargoPodBlockEntity pod = entity(); pod.setCargo(CARGO);
        CompoundTag nbt = pod.saveWithoutMetadata(RegistryAccess.EMPTY);
        CargoPodBlockEntity back = entity(); back.loadAdditional(nbt, RegistryAccess.EMPTY);
        assertEquals(CARGO, back.cargo());
    }

    @Test void breakingAPodReturnsItsDataAndPlacingRestoresIt() {
        CargoPodBlockEntity pod = entity(); pod.setCargo(CARGO);
        DataComponentMap components = pod.collectComponents();
        CustomData data = components.get(DataComponents.CUSTOM_DATA);
        assertNotNull(data);
        assertEquals(CARGO, PodCargo.load(data.copyTag()).orElseThrow());
        CargoPodBlockEntity placed = entity();
        placed.applyComponents(components, net.minecraft.core.component.DataComponentPatch.EMPTY);
        assertEquals(CARGO, placed.cargo());
    }

    @Test void malformedDataIsRejected() { assertTrue(PodCargo.load(new CompoundTag()).isEmpty()); }
}
