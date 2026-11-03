package dev.skydock.item;

import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipManager;
import dev.skydock.ship.ShipRepair;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Repairs a launched ship. Aiming at the hull is handled by {@code ShipInteractions}; this covers aiming at a gap or the sky. */
public final class ShipwrightHammerItem extends Item {
    public ShipwrightHammerItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        if (!(player instanceof ServerPlayer server)) return InteractionResultHolder.pass(stack);
        Ship nearest = null; double best = Double.MAX_VALUE;
        for (Ship ship : ShipManager.ships(level)) {
            if (!ship.phase.equals("active") || !ShipManager.permitted(server, ship) || !ShipRepair.near(server, ship)) continue;
            double d = ship.hullWorldBounds().getCenter().distanceToSqr(player.position());
            if (d < best) { best = d; nearest = ship; }
        }
        if (nearest == null) return InteractionResultHolder.pass(stack);
        ShipRepair.repair(server, nearest, stack);
        return InteractionResultHolder.success(stack);
    }
}
