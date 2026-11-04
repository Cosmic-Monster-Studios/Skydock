package dev.skydock.cargo;

import com.mojang.serialization.MapCodec;
import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;

/** The world block at a cargo outpost. A generated one registers itself (see {@link CargoTerminalBlockEntity}); breaking it removes the terminal from the registry and drops nothing. */
public final class CargoTerminalBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<CargoTerminalBlock> CODEC = simpleCodec(CargoTerminalBlock::new);
    public CargoTerminalBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected MapCodec<CargoTerminalBlock> codec() { return CODEC; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CargoTerminalBlockEntity(pos, state); }
    @SuppressWarnings("unchecked")
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || type != SkydockBlocks.CARGO_TERMINAL_ENTITY.get() ? null : (BlockEntityTicker<T>) (BlockEntityTicker<CargoTerminalBlockEntity>) CargoTerminalBlockEntity::serverTick;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            MenuRegistry.openExtendedMenu(serverPlayer, new SimpleMenuProvider((id, inventory, ignored) -> new TerminalMenu(id, inventory, pos), getName()), buffer -> buffer.writeBlockPos(pos));
            if (serverPlayer.containerMenu instanceof TerminalMenu) CargoService.opened(serverPlayer, GlobalPos.of(level.dimension(), pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server)
            CargoSavedData.get(server.getServer()).removeTerminal(GlobalPos.of(level.dimension(), pos), level.getBlockEntity(pos) instanceof CargoTerminalBlockEntity terminal && terminal.fromOutpost());
        super.onRemove(state, level, pos, next, moved);
    }
}
