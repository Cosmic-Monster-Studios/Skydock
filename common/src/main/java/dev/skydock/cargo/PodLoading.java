package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.DockTier;
import dev.skydock.data.MassTable;
import dev.skydock.network.ShipNetwork;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipInteractions;
import dev.skydock.ship.ShipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import java.util.UUID;

/** Putting cargo pods into a moored ship's hull and taking them out again. */
public final class PodLoading {
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private PodLoading() {}

    /** Empty when a pod may go in {@code cell}, else the lang key saying why not. */
    public static String placeable(Map<BlockPos, BlockState> blocks, DockTier tier, BlockPos cell, int filled, int cap) {
        if (cell.getX() < 0 || cell.getY() < 0 || cell.getZ() < 0 || cell.getX() >= tier.width || cell.getY() >= tier.height || cell.getZ() >= tier.length) return "message.skydock.pod_outside";
        BlockState there = blocks.get(cell);
        if (there != null && !there.isAir()) return "message.skydock.pod_occupied";
        boolean attached = false;
        for (Direction d : Direction.values()) { BlockState n = blocks.get(cell.relative(d)); if (n != null && !n.isAir()) { attached = true; break; } }
        if (!attached) return "message.skydock.pod_unattached";
        if (filled >= cap) return "message.skydock.pod_cap";
        return "";
    }

    /**
     * Empty when the ship can still fly with one more pod, else the lang key saying why not: the pod's mass must stay within lift and,
     * when the pod is structural (not a decoration), the structural block count within what the lift cells support, as at launch.
     */
    public static String liftable(double mass, double lift, int structural, int cells, double podMass, boolean podStructural) {
        if (mass + podMass > lift) return "message.skydock.pod_overweight";
        if (podStructural && structural + 1 > (long) cells * MassTable.blocksPerCell()) return "message.skydock.pod_need_cells";
        return "";
    }

    /** Puts a pod into the live hull. The blueprint keeps a block the hull lost there (so the hammer can bring it back later) and only gains the pod in an empty cell. */
    static void record(Ship ship, BlockPos cell, BlockState pod) { ship.blocks.put(cell.immutable(), pod); ship.blueprint.putIfAbsent(cell.immutable(), pod); }

    public static void place(ServerPlayer player, Ship ship, ShipInteractions.Hit hit) {
        ItemStack held = player.getMainHandItem();
        PodCargo cargo = CargoPodItem.cargo(held).orElse(null); if (cargo == null) return;
        if (!ShipManager.permitted(player, ship)) { ShipManager.tell(player, "message.skydock.device_not_permitted"); return; }
        if (!ship.moored) { ShipManager.tell(player, "message.skydock.pod_moor_first"); return; }
        var server = player.getServer(); ServerLevel yard = server.getLevel(ShipManager.SHIPYARD), world = player.serverLevel(); if (yard == null) return;
        BlockPos cell = hit.localHit().getBlockPos().relative(hit.localHit().getDirection());
        int filled = 0; for (BlockState s : ship.blocks.values()) if (!s.isAir()) filled++;
        String refusal = placeable(ship.blocks, ship.tier, cell, filled, MassTable.cap(ship.tier));
        BlockState state = SkydockBlocks.CARGO_POD.get().defaultBlockState();
        if (refusal.isEmpty()) {
            int structural = 0; for (BlockState s : ship.blocks.values()) if (!s.isAir() && !s.is(SkydockBlocks.DECORATIONS)) structural++;
            refusal = liftable(ship.mass, ship.lift, structural, ship.cells, MassTable.mass(state), !state.is(SkydockBlocks.DECORATIONS));
        }
        if (!refusal.isEmpty()) { ShipManager.tell(player, refusal); return; }
        if (!ShipManager.cellClear(world, ship, ship.pose, cell)) { ShipManager.tell(player, "message.skydock.pod_obstructed"); return; }
        BlockPos at = ship.yard.offset(cell);
        if (!yard.setBlock(at, state, FLAGS)) return;
        if (!(yard.getBlockEntity(at) instanceof CargoPodBlockEntity pod)) { yard.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS); return; }
        pod.setCargo(cargo);
        record(ship, cell, state);
        yard.updateNeighborsAt(at, state.getBlock());
        held.shrink(1);
        ship.revision++; ShipManager.refresh(server, ship); ShipNetwork.syncPlayers(server);
    }

    /** Drops the cell from the live hull and, when the blueprint has a pod there, from the blueprint, so neither a refresh nor the hammer brings the pod back. */
    public static void forget(Ship ship, BlockPos cell) { forget(ship, cell, s -> s.is(SkydockBlocks.CARGO_POD.get())); }
    /** {@link #forget(Ship, BlockPos)} with {@code pod} saying which blueprint states are pods; a block the pod took the place of stays, to be hammered back. */
    static void forget(Ship ship, BlockPos cell, java.util.function.Predicate<BlockState> pod) {
        ship.blocks.remove(cell);
        BlockState planned = ship.blueprint.get(cell); if (planned != null && pod.test(planned)) ship.blueprint.remove(cell);
    }

    /** Whether a player may take a pod off a hull: the contract holder may, and anyone may take an ejected pod or one whose contract is gone. */
    public static boolean takeable(UUID holder, UUID player, PodTag tag) { return holder == null || holder.equals(player) || tag.ejected(); }

    /**
     * Whether {@code player} may break a pod standing as a world block (a redocked hull): as {@link #takeable}, and a creative-mode operator
     * (permission level 2) always may. An empty pod is anyone's.
     */
    public static boolean breakable(ServerPlayer player, CargoPodBlockEntity pod) {
        if (player.isCreative() && player.hasPermissions(2)) return true;
        if (pod == null || pod.cargo() == null) return true;
        PodTag tag = pod.cargo().tag(); var holder = CargoSavedData.get(player.getServer()).findContract(tag.contract());
        return takeable(holder == null ? null : holder.getKey(), player.getUUID(), tag);
    }

    public static void take(ServerPlayer player, Ship ship, BlockPos local) {
        var server = player.getServer(); ServerLevel yard = server.getLevel(ShipManager.SHIPYARD); if (yard == null) return;
        BlockPos at = ship.yard.offset(local);
        if (!ship.moored) { ShipManager.tell(player, "message.skydock.pod_moor_first"); return; }
        if (!(yard.getBlockEntity(at) instanceof CargoPodBlockEntity pod) || pod.cargo() == null) {
            ShipManager.tell(player, "message.skydock.pod_empty");
            if (yard.getBlockState(at).is(SkydockBlocks.CARGO_POD.get())) yard.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
            forget(ship, local);
            ship.revision++; ShipManager.refresh(server, ship); ShipNetwork.syncPlayers(server); return;
        }
        PodCargo cargo = pod.cargo(); PodTag tag = cargo.tag();
        var holder = CargoSavedData.get(server).findContract(tag.contract());
        if (!takeable(holder == null ? null : holder.getKey(), player.getUUID(), tag)) { ShipManager.tell(player, "message.skydock.pod_not_yours"); return; }
        yard.setBlock(at, Blocks.AIR.defaultBlockState(), FLAGS);
        forget(ship, local);
        ItemStack item = CargoPodItem.create(tag, cargo.cargoKey(), cargo.destination());
        if (!player.getInventory().add(item)) player.drop(item, false);
        ship.revision++; ShipManager.refresh(server, ship); ShipNetwork.syncPlayers(server);
    }
}
