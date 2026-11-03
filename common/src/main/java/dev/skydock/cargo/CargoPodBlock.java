package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.serialization.MapCodec;

/** The sealed crate a contract's cargo travels in. It has no menu; pickup and delivery are handled elsewhere. */
public final class CargoPodBlock extends BaseEntityBlock {
    public static final MapCodec<CargoPodBlock> CODEC = simpleCodec(CargoPodBlock::new);
    public CargoPodBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CargoPodBlockEntity(pos, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
}
