package dev.skydock.block;

import dev.architectury.registry.menu.MenuRegistry;
import dev.skydock.menu.DeviceMenu;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;

/** Six-way connected canvas lift cell. Models use these faces to merge 3D clusters. */
public final class LiftCellBlock extends Block {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    public LiftCellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(EAST, false)
                .setValue(SOUTH, false).setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockGetter level = context.getLevel(); BlockPos pos = context.getClickedPos(); BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) state = state.setValue(property(direction), connects(level.getBlockState(pos.relative(direction))));
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(property(direction), connects(neighbor));
    }

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Moves each horizontal connection (indexed N, E, S, W) to the side it faces after the rotation. Pure so it can be tested without a registered block. */
    static boolean[] rotateConnections(boolean[] nesw, Rotation rotation) {
        boolean[] out = new boolean[4];
        for (int i = 0; i < 4; i++) out[java.util.Arrays.asList(HORIZONTAL).indexOf(rotation.rotate(HORIZONTAL[i]))] = nesw[i];
        return out;
    }

    /** Same idea for a mirror. */
    static boolean[] mirrorConnections(boolean[] nesw, Mirror mirror) {
        boolean[] out = new boolean[4];
        for (int i = 0; i < 4; i++) out[java.util.Arrays.asList(HORIZONTAL).indexOf(mirror.mirror(HORIZONTAL[i]))] = nesw[i];
        return out;
    }

    private static boolean[] horizontal(BlockState state) {
        boolean[] nesw = new boolean[4];
        for (int i = 0; i < 4; i++) nesw[i] = state.getValue(property(HORIZONTAL[i]));
        return nesw;
    }

    private static BlockState withHorizontal(BlockState state, boolean[] nesw) {
        for (int i = 0; i < 4; i++) state = state.setValue(property(HORIZONTAL[i]), nesw[i]);
        return state;
    }

    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return withHorizontal(state, rotateConnections(horizontal(state), rotation)); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return withHorizontal(state, mirrorConnections(horizontal(state), mirror)); }

    private static boolean connects(BlockState state) { return state.is(SkydockBlocks.LIFT_CELLS); }
    private static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH; case WEST -> WEST; case UP -> UP; case DOWN -> DOWN;
        };
    }

    public static int clusterSize(BlockGetter level, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>(); ArrayDeque<BlockPos> open = new ArrayDeque<>();
        seen.add(start.immutable()); open.add(start.immutable());
        while (!open.isEmpty() && seen.size() < 4096) {
            BlockPos pos = open.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (level.getBlockState(next).is(SkydockBlocks.LIFT_CELLS) && seen.add(next.immutable())) open.addLast(next.immutable());
            }
        }
        return seen.size();
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            int size = clusterSize(level, pos);
            DeviceMenu.openGround(serverPlayer, pos, DeviceBlock.Kind.LIFT, size, size * dev.skydock.data.MassTable.liftPerCell());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
