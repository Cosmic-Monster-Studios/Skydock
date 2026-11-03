package dev.skydock.block;

import dev.architectury.registry.menu.MenuRegistry;
import dev.skydock.data.DockTier;
import dev.skydock.assembly.AssemblyManager;
import dev.skydock.ship.ShipManager;
import dev.skydock.network.DockNetwork;
import dev.skydock.menu.DockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class DockControllerBlock extends Block implements EntityBlock {
    public final DockTier tier;
    public DockControllerBlock(DockTier tier) { super(SkydockBlocks.props().strength(5)); this.tier = tier; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DockBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (tickLevel, tickPos, tickState, entity) -> {
            if (tickLevel instanceof net.minecraft.server.level.ServerLevel serverLevel && entity instanceof DockBlockEntity dock)
                DockBlockEntity.serverTick(serverLevel, tickPos, tickState, dock);
        };
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof DockBlockEntity dock) dock.claim(player);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
            if (dock.owner == null) dock.claim(player);
            if (dock.canManage(player)) {
                MenuRegistry.openExtendedMenu(serverPlayer, dock, buffer -> buffer.writeBlockPos(pos));
                if (serverPlayer.containerMenu instanceof DockMenu menu) DockNetwork.sendSnapshot(serverPlayer, menu, dock);
            }
            else ShipManager.tell(player, "message.skydock.dock_not_owner");
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock() && level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof DockBlockEntity dock)
            AssemblyManager.cancel(serverLevel, dock, "status.skydock.cancelled_removed");
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
