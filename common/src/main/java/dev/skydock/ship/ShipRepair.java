package dev.skydock.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;

/** The Shipwright's Hammer: restores blueprint cells the hull has lost, paying for them in materials. */
public final class ShipRepair {
    public static final int PER_USE = 16, REACH = 8;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private ShipRepair() {}

    /** Blueprint cells that are empty now and not blocked, nearest {@code nearLocal} first (ties by y, x, z), at most {@code limit}. */
    public static List<BlockPos> missing(Map<BlockPos, ?> blueprint, Predicate<BlockPos> emptyNow, Predicate<BlockPos> blocked, Vec3 nearLocal, int limit) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos pos : blueprint.keySet()) if (emptyNow.test(pos) && !blocked.test(pos)) cells.add(pos.immutable());
        cells.sort(Comparator.<BlockPos>comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(nearLocal))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return cells.size() > limit ? new ArrayList<>(cells.subList(0, Math.max(0, limit))) : cells;
    }

    /** Whether the player is on or within {@link #REACH} blocks of the hull. */
    public static boolean near(ServerPlayer player, Ship ship) { return ship.hullWorldBounds().inflate(REACH).contains(player.position()); }

    /** Restores up to {@link #PER_USE} missing cells for one hammer use and returns how many came back. */
    public static int repair(ServerPlayer player, Ship ship, ItemStack hammer) {
        if (!ShipManager.permitted(player, ship)) { ShipManager.tell(player, "message.skydock.device_not_permitted"); return 0; }
        if (!ship.phase.equals("active") || !ship.dimension.equals(player.level().dimension()) || !near(player, ship)) { ShipManager.tell(player, "message.skydock.repair_too_far"); return 0; }
        var server = player.getServer(); ShipManager.wake(server, ship);
        ServerLevel yard = server.getLevel(ShipManager.SHIPYARD), world = player.serverLevel(); if (yard == null) return 0;
        Vec3 near = ship.worldToBlock(player.position());
        List<BlockPos> cells = missing(ship.blueprint, p -> yard.getBlockState(ship.yard.offset(p)).isAir(),
                p -> Item.byBlock(ship.blueprint.get(p).getBlock()) == Items.AIR || occupied(world, ship, p), near, Integer.MAX_VALUE);
        if (cells.isEmpty()) { ShipManager.tell(player, "message.skydock.repair_nothing"); return 0; }
        List<Container> stores = new ArrayList<>();
        for (BlockPos p : ship.blocks.keySet()) if (yard.getBlockEntity(ship.yard.offset(p)) instanceof Container container && isStorage(container)) stores.add(container);
        List<Item> lacking = new ArrayList<>(); List<BlockPos> done = new ArrayList<>();
        restore(cells, p -> Item.byBlock(ship.blueprint.get(p).getBlock()), (p, item) -> {
            if (hammer.isEmpty()) return false;
            if (!take(stores, player, item)) { lacking.add(item); return false; }
            BlockPos at = ship.yard.offset(p);
            // Same flags as ShipTransfer: no drops land in the shipyard, neighbours are updated once the batch is in.
            if (!yard.setBlock(at, ship.blueprint.get(p), FLAGS)) { give(player, item); return false; }
            hammer.hurtAndBreak(1, player, EquipmentSlot.MAINHAND); done.add(p); return true;
        }, PER_USE);
        for (BlockPos p : done) yard.updateNeighborsAt(ship.yard.offset(p), ship.blueprint.get(p).getBlock());
        int restored = done.size();
        if (restored > 0) { ShipManager.refresh(server, ship); ShipManager.tell(player, "message.skydock.repaired", restored); }
        else if (!lacking.isEmpty()) ShipManager.tell(player, "message.skydock.repair_needs_materials", lacking.get(0).getDescription());
        else ShipManager.tell(player, "message.skydock.repair_nothing");
        return restored;
    }
    /**
     * Walks {@code sorted} (nearest first) and restores cells until {@code limit} succeed. A kind whose attempt fails
     * (unaffordable) is skipped for the rest of the batch, so it cannot block farther cells of other kinds.
     */
    static <K> int restore(List<BlockPos> sorted, java.util.function.Function<BlockPos, K> kind, java.util.function.BiPredicate<BlockPos, K> attempt, int limit) {
        Set<K> failed = new HashSet<>(); int restored = 0;
        for (BlockPos cell : sorted) {
            if (restored >= limit) break;
            K k = kind.apply(cell);
            if (failed.contains(k)) continue;
            if (attempt.test(cell, k)) restored++; else failed.add(k);
        }
        return restored;
    }
    private static void give(ServerPlayer player, Item item) { if (!player.getInventory().add(new ItemStack(item))) player.drop(new ItemStack(item), false); }
    /** Chests, barrels and the like; engine fuel slots and furnaces are machines, not stores. */
    private static boolean isStorage(Container container) {
        return !(container instanceof dev.skydock.block.EngineBlockEntity) && !(container instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                && !(container instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) && !(container instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity)
                && !(container instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity) && !(container instanceof net.minecraft.world.level.block.entity.CrafterBlockEntity);
    }
    private static boolean occupied(ServerLevel world, Ship ship, BlockPos cell) {
        AABB box = ship.transform().toWorld(new AABB(cell).move(ship.center().scale(-1)));
        return !world.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator()).isEmpty();
    }
    /** One of {@code item}, from containers aboard first and then the player's inventory. */
    private static boolean take(List<Container> stores, ServerPlayer player, Item item) {
        for (Container store : stores) if (take(store, item)) return true;
        var items = player.getInventory().items;
        for (ItemStack stack : items) if (stack.is(item)) { stack.shrink(1); player.getInventory().setChanged(); return true; }
        return false;
    }
    private static boolean take(Container store, Item item) {
        for (int i = 0; i < store.getContainerSize(); i++) {
            ItemStack stack = store.getItem(i);
            if (stack.is(item)) { stack.shrink(1); store.setChanged(); return true; }
        }
        return false;
    }
}
