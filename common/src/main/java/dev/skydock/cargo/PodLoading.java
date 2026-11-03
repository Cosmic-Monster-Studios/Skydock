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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.Map;

/** Putting cargo pods into a moored ship's hull and taking them out again. */
public final class PodLoading {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
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

    public static void place(ServerPlayer player, Ship ship, ShipInteractions.Hit hit) {
        ItemStack held = player.getMainHandItem();
        PodCargo cargo = CargoPodItem.cargo(held).orElse(null); if (cargo == null) return;
        if (!ShipManager.permitted(player, ship)) { ShipManager.tell(player, "message.skydock.device_not_permitted"); return; }
        if (!ship.moored) { ShipManager.tell(player, "message.skydock.pod_moor_first"); return; }
        var server = player.getServer(); ServerLevel yard = server.getLevel(ShipManager.SHIPYARD), world = player.serverLevel(); if (yard == null) return;
        BlockPos cell = hit.localHit().getBlockPos().relative(hit.localHit().getDirection());
        int filled = 0; for (BlockState s : ship.blocks.values()) if (!s.isAir()) filled++;
        String refusal = placeable(ship.blocks, ship.tier, cell, filled, MassTable.cap(ship.tier));
        if (!refusal.isEmpty()) { ShipManager.tell(player, refusal); return; }
        AABB box = ship.transform().toWorld(new AABB(cell).move(ship.center().scale(-1)));
        if (!world.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator()).isEmpty()) { ShipManager.tell(player, "message.skydock.pod_occupied"); return; }
        BlockPos at = ship.yard.offset(cell); BlockState state = SkydockBlocks.CARGO_POD.get().defaultBlockState();
        if (!yard.setBlock(at, state, FLAGS)) return;
        if (yard.getBlockEntity(at) instanceof CargoPodBlockEntity pod) pod.setCargo(cargo);
        ship.blocks.put(cell.immutable(), state); ship.blueprint.put(cell.immutable(), state);
        yard.updateNeighborsAt(at, state.getBlock());
        if (!player.getAbilities().instabuild) held.shrink(1);
        ship.revision++; ShipManager.refresh(server, ship); ShipNetwork.syncPlayers(server);
    }

    /** Drops the cell from the live hull and the blueprint, so neither a refresh nor the hammer brings it back. */
    public static void forget(Ship ship, BlockPos cell) { ship.blocks.remove(cell); ship.blueprint.remove(cell); }

    public static void take(ServerPlayer player, Ship ship, BlockPos local) {
        var server = player.getServer(); ServerLevel yard = server.getLevel(ShipManager.SHIPYARD); if (yard == null) return;
        BlockPos at = ship.yard.offset(local);
        if (!(yard.getBlockEntity(at) instanceof CargoPodBlockEntity pod) || pod.cargo() == null) return;
        PodCargo cargo = pod.cargo(); PodTag tag = cargo.tag();
        var holder = CargoSavedData.get(server).findContract(tag.contract());
        boolean mine = holder != null && holder.getKey().equals(player.getUUID());
        if (!(mine || tag.ejected() || holder == null)) { ShipManager.tell(player, "message.skydock.pod_not_yours"); return; }
        if (!ship.moored) { ShipManager.tell(player, "message.skydock.pod_moor_first"); return; }
        yard.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), FLAGS);
        forget(ship, local);
        ItemStack item = CargoPodItem.create(tag, cargo.cargoKey(), cargo.destination());
        if (!player.getInventory().add(item)) player.drop(item, false);
        ship.revision++; ShipManager.refresh(server, ship); ShipNetwork.syncPlayers(server);
    }
}
