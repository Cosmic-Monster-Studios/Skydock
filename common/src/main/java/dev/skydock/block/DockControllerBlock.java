package dev.skydock.block;

import com.mojang.serialization.MapCodec;
import dev.architectury.registry.menu.MenuRegistry;
import dev.skydock.data.BerthClearance;
import dev.skydock.data.DockFrame;
import dev.skydock.data.DockTier;
import dev.skydock.assembly.AssemblyManager;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipManager;
import dev.skydock.network.DockNetwork;
import dev.skydock.menu.DockMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import java.util.ArrayList;
import java.util.List;

/** {@code FACING} is the way the bow points; the berth extends behind the controller. Controllers saved without it read north. */
public final class DockControllerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public final DockTier tier;
    public DockControllerBlock(DockTier tier) {
        super(SkydockBlocks.props().strength(5)); this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }
    @Override protected MapCodec<DockControllerBlock> codec() { return MapCodec.unit(this); }
    /** The berth must be clear to place a controller; both sides check, so the client never shows a block the server refuses. */
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        BerthClearance clearance = clearance(context.getLevel(), new DockFrame(context.getClickedPos(), context.getHorizontalDirection(), tier));
        if (clearance.clear()) return state;
        if (!context.getLevel().isClientSide && context.getPlayer() != null)
            ShipManager.tell(context.getPlayer(), Component.translatable(clearance.error(), clearance.args().toArray()));
        return null;
    }
    /** Furthest a controller can sit from a cell of its own berth, plus a chunk of slack. */
    private static final int BERTH_REACH = DockTier.DREADNOUGHT.width / 2 + DockTier.DREADNOUGHT.length + 16;
    public static BerthClearance clearance(Level level, DockFrame frame) {
        AABB envelope = frame.envelope();
        int minX = (int) Math.floor(envelope.minX), minZ = (int) Math.floor(envelope.minZ), maxX = (int) Math.ceil(envelope.maxX) - 1, maxZ = (int) Math.ceil(envelope.maxZ) - 1;
        if (!level.hasChunksAt(minX, minZ, maxX, maxZ)) return new BerthClearance("error.skydock.berth_unloaded", List.of(), List.of());
        if (!level.getWorldBorder().isWithinBounds(envelope)) return new BerthClearance("error.skydock.berth_border", List.of(), List.of());
        List<AABB> berths = new ArrayList<>();
        for (int x = (minX - BERTH_REACH) >> 4; x <= (maxX + BERTH_REACH) >> 4; x++) for (int z = (minZ - BERTH_REACH) >> 4; z <= (maxZ + BERTH_REACH) >> 4; z++)
            if (level.getChunkSource().getChunkNow(x, z) instanceof LevelChunk chunk)
                for (BlockEntity entity : chunk.getBlockEntities().values())
                    if (entity instanceof DockBlockEntity dock && !dock.getBlockPos().equals(frame.controller())) berths.add(dock.frame().envelope());
        List<AABB> hulls = ShipManager.ships(level).stream().map(Ship::hullWorldBounds).toList();
        return BerthClearance.check(frame, level.getMinBuildHeight(), level.getMaxBuildHeight(), p -> cell(level.getBlockState(p)), berths, hulls);
    }
    private static BerthClearance.Cell cell(BlockState state) {
        if (state.isAir() || state.is(SkydockBlocks.NOT_SHIP)) return BerthClearance.Cell.EMPTY;
        if (!state.getFluidState().isEmpty()) return BerthClearance.Cell.FLUID;
        // Replaceable blocks (grass, snow) and bush plants (flowers, saplings, mushrooms, crops) are cleared, not refused.
        return state.canBeReplaced() || state.getBlock() instanceof BushBlock ? BerthClearance.Cell.CLEARABLE : BerthClearance.Cell.SOLID;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
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
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof DockBlockEntity dock) {
            dock.claim(player);
            // Grass and flowers do not block placement, but they would read as hull, so they go now.
            for (BlockPos plant : clearance(level, dock.frame()).clearable()) level.destroyBlock(plant, true, player);
        }
    }
    /** A warp anchor binds to the dock rather than opening its screen. */
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return stack.getItem() instanceof dev.skydock.item.WarpAnchorItem ? ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION : super.useItemOn(stack, state, level, pos, player, hand, hit);
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
