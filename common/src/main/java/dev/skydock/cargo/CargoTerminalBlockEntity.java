package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers its terminal on the first server tick. A generated terminal carries {@code PadOffset}, the pad centre relative to the
 * block for a terminal facing north, rotated here with the block; a hand-placed one has its pad 5 blocks in front. Generated
 * terminals also spawn two villagers on the pad, once.
 */
public final class CargoTerminalBlockEntity extends BlockEntity {
    static final BlockPos HAND_PLACED_OFFSET = new BlockPos(0, 0, -5);
    private BlockPos padOffset;
    private boolean registered, spawnedVillagers;
    public CargoTerminalBlockEntity(BlockPos pos, BlockState state) { super(SkydockBlocks.CARGO_TERMINAL_ENTITY.get(), pos, state); }

    /** The pad centre in world coordinates. */
    public BlockPos pad() { return pad(worldPosition, getBlockState().getValue(HorizontalDirectionalBlock.FACING), padOffset); }
    static BlockPos pad(BlockPos pos, Direction facing, BlockPos offset) { return pos.offset((offset == null ? HAND_PLACED_OFFSET : offset).rotate(rotationFromNorth(facing))); }
    private static Rotation rotationFromNorth(Direction facing) {
        return switch (facing) { case EAST -> Rotation.CLOCKWISE_90; case SOUTH -> Rotation.CLOCKWISE_180; case WEST -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CargoTerminalBlockEntity terminal) {
        if (terminal.registered && (terminal.spawnedVillagers || terminal.padOffset == null) || !(level instanceof ServerLevel server)) return;
        BlockPos pad = terminal.pad();
        if (!terminal.registered) { CargoSavedData.get(server.getServer()).registerTerminal(GlobalPos.of(level.dimension(), pos), pad); terminal.registered = true; }
        if (!terminal.spawnedVillagers && terminal.padOffset != null) { terminal.spawnVillagers(server, pad, state.getValue(HorizontalDirectionalBlock.FACING)); terminal.spawnedVillagers = true; }
        terminal.setChanged();
    }

    private void spawnVillagers(ServerLevel level, BlockPos pad, Direction facing) {
        BlockPos edge = pad.relative(facing.getOpposite(), 3);
        for (int side : new int[] {-2, 2}) {
            Villager villager = EntityType.VILLAGER.create(level);
            if (villager == null) continue;
            BlockPos at = edge.relative(facing.getClockWise(), side);
            villager.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, facing.toYRot(), 0);
            villager.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null);
            villager.setPersistenceRequired();
            level.addFreshEntityWithPassengers(villager);
        }
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (padOffset != null) tag.putIntArray("PadOffset", new int[] {padOffset.getX(), padOffset.getY(), padOffset.getZ()});
        tag.putBoolean("Registered", registered); tag.putBoolean("SpawnedVillagers", spawnedVillagers);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int[] offset = tag.contains("PadOffset", Tag.TAG_INT_ARRAY) ? tag.getIntArray("PadOffset") : new int[0];
        padOffset = offset.length == 3 ? new BlockPos(offset[0], offset[1], offset[2]) : null;
        registered = tag.getBoolean("Registered"); spawnedVillagers = tag.getBoolean("SpawnedVillagers");
    }
}
