package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ShipPersistenceTest {
    private static HolderLookup.Provider registries;

    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    private static Ship hull() {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.dock = BlockPos.ZERO; ship.yard = new BlockPos(256, 64, 0);
        ship.pose = new ShipPose(10, 80, -4, 30); ship.previousPose = ship.pose;
        for (int x = 0; x < 12; x++) for (int z = 0; z < 20; z++) ship.blocks.put(new BlockPos(x, 0, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
        ship.blocks.put(new BlockPos(3, 1, 4), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP));
        ship.blocks.put(new BlockPos(4, 1, 4), Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        ship.blocks.put(new BlockPos(5, 1, 4), Blocks.CHEST.defaultBlockState());
        CompoundTag chest = new CompoundTag(); chest.putString("id", "minecraft:chest"); chest.putString("CustomName", "\"Stores\"");
        ship.blockEntities.put(new BlockPos(5, 1, 4), chest);
        return ship;
    }

    @Test void hullsSaveOneStatePerPaletteEntryAndRoundTripInOrder() {
        Ship source = hull();
        CompoundTag saved = source.save(registries, true);
        assertFalse(saved.contains("Blocks"));
        assertEquals(4, saved.getList("Palette", Tag.TAG_COMPOUND).size());
        assertEquals(source.blocks.size(), saved.getLongArray("BlockPositions").length);
        Ship loaded = Ship.loadPersisted(saved, registries);
        assertEquals(new ArrayList<>(source.blocks.entrySet()), new ArrayList<>(loaded.blocks.entrySet()));
        assertEquals(source.blockEntities, loaded.blockEntities);
    }

    @Test void savesFromBeforeThePaletteStillLoad() {
        Ship source = hull();
        CompoundTag legacy = source.save(registries, false);
        ListTag blocks = new ListTag();
        source.blocks.forEach((pos, state) -> {
            CompoundTag entry = new CompoundTag(); entry.putLong("Pos", pos.asLong()); entry.put("State", NbtUtils.writeBlockState(state));
            if (source.blockEntities.containsKey(pos)) entry.put("Data", source.blockEntities.get(pos).copy());
            blocks.add(entry);
        });
        legacy.put("Blocks", blocks);
        Ship loaded = Ship.loadPersisted(legacy, registries);
        assertEquals(new ArrayList<>(source.blocks.entrySet()), new ArrayList<>(loaded.blocks.entrySet()));
        assertEquals(source.blockEntities, loaded.blockEntities);
    }

    @Test void surfaceCollisionSkipsCellsBuriedInsideTheHull() {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT;
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) ship.blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        assertEquals(125 - 27, ship.surfaceCollisionBoxes().size());
        ship.blocks.remove(new BlockPos(2, 2, 2)); ship.revision++;
        assertEquals(125 - 1 - 20, ship.surfaceCollisionBoxes().size());
    }

    @Test void legacyShipLoadsFacingNorth() {
        CompoundTag legacy = hull().save(registries, true);
        legacy.remove("DockFacing");
        legacy.putLong("TransferOrigin", 5); legacy.putLong("TransferDock", 6); legacy.putString("TransferDimension", "minecraft:overworld");
        Ship loaded = Ship.loadPersisted(legacy, registries);
        assertEquals(Direction.NORTH, loaded.dockFacing);
        assertEquals(Direction.NORTH, loaded.transferFacing);
        Ship east = hull(); east.dockFacing = Direction.EAST;
        east.transferOrigin = BlockPos.ZERO; east.transferDock = BlockPos.ZERO; east.transferDimension = east.dimension; east.transferFacing = Direction.WEST;
        Ship reloaded = Ship.loadPersisted(east.save(registries, true), registries);
        assertEquals(Direction.EAST, reloaded.dockFacing);
        assertEquals(Direction.WEST, reloaded.transferFacing);
        assertEquals(new dev.skydock.data.DockFrame(BlockPos.ZERO, Direction.EAST, DockTier.SCOUT), reloaded.berthFrame());
    }
}
