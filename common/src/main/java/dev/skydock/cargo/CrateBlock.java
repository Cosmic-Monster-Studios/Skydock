package dev.skydock.cargo;

import com.mojang.serialization.MapCodec;
import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A cargo crate of one rank. Using it starts its opening show; while {@link #OPEN} the block entity renderer draws it and it cannot be mined. */
public final class CrateBlock extends Block implements EntityBlock {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public final CrateRank rank;
    public CrateBlock(CrateRank rank) {
        super(Properties.of().strength(1.5f).sound(SoundType.WOOD).noOcclusion().lightLevel(state -> rank == CrateRank.AETHER ? 6 : 0));
        this.rank = rank; registerDefaultState(stateDefinition.any().setValue(OPEN, false));
    }
    @Override protected MapCodec<CrateBlock> codec() { return MapCodec.unit(this); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(OPEN); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CrateBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return state.getValue(OPEN) ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.MODEL; }
    @Override protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) { return state.getValue(OPEN) ? 0 : super.getDestroyProgress(state, player, level, pos); }
    @SuppressWarnings("unchecked")
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != SkydockBlocks.CRATE_ENTITY.get()) return null;
        BlockEntityTicker<CrateBlockEntity> ticker = level.isClientSide ? CrateBlockEntity::clientTick : CrateBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (state.getValue(OPEN)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer opener && level.getBlockEntity(pos) instanceof CrateBlockEntity crate) crate.open(opener);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
