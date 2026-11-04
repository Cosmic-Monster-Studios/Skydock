package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ShipSavedDataTest {
    private static HolderLookup.Provider registries;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    private static CompoundTag ship(BlockPos yard) {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.dock = BlockPos.ZERO; ship.yard = yard;
        ship.pose = new ShipPose(0, 80, 0, 0); ship.previousPose = ship.pose;
        ship.blocks.put(BlockPos.ZERO, Blocks.SPRUCE_PLANKS.defaultBlockState());
        return ship.save(registries, true);
    }

    /** One record that throws on load costs only itself: the rest load, it is saved back untouched, and no new ship takes its yard. */
    @Test void aCorruptShipIsQuarantinedAndKept() {
        CompoundTag good = ship(new BlockPos(256, 64, 0)), corrupt = ship(new BlockPos(5 * 256, 64, 2 * 256));
        corrupt.putString("Tier", "NO_SUCH_TIER");
        ListTag ships = new ListTag(); ships.add(good); ships.add(corrupt);
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships); tag.putInt("NextRegion", 0);

        ShipSavedData data = ShipSavedData.load(tag, registries);
        assertEquals(1, data.ships.size());
        assertTrue(data.ships.containsKey(good.getUUID("Id")));
        assertEquals(1, data.quarantined.size());
        assertEquals(2006, data.nextRegion, "the quarantined ship's region 2005 stays taken");

        ListTag saved = data.save(new CompoundTag(), registries).getList("Ships", Tag.TAG_COMPOUND);
        assertEquals(2, saved.size());
        assertTrue(saved.contains(corrupt), "the bad record is written back as it was");
        ShipSavedData again = ShipSavedData.load(data.save(new CompoundTag(), registries), registries);
        assertEquals(1, again.ships.size()); assertEquals(1, again.quarantined.size()); assertEquals(2006, again.nextRegion);
    }

    @Test void nextRegionNeverFallsBehindALoadedShip() {
        ListTag ships = new ListTag(); ships.add(ship(new BlockPos(3 * 256, 64, 256)));
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships); tag.putInt("NextRegion", 0);
        assertEquals(1004, ShipSavedData.load(tag, registries).nextRegion);
        tag.putInt("NextRegion", 4000);
        assertEquals(4000, ShipSavedData.load(tag, registries).nextRegion);
        assertEquals(0, ShipSavedData.load(new CompoundTag(), registries).nextRegion);
    }

    @Test void regionsMatchTheYardLayout() {
        for (int region : new int[] {0, 1, 999, 1000, 123_456, 999_999})
            assertEquals(region, ShipSavedData.regionOf(new BlockPos((region % 1000) * 256, 64, (region / 1000) * 256)));
    }
}
