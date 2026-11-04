package dev.skydock.item;

import dev.skydock.block.SkydockBlocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Summoning Horn of 0.2.0-alpha.2 worlds, since renamed the Warp Anchor. Hidden (no tab, no recipe), it exists only so old stacks
 * load: in a player's inventory, or when used, it turns in place into a Warp Anchor keeping its binding and wear.
 */
public final class LegacySummoningHornItem extends Item {
    public LegacySummoningHornItem(Properties properties) { super(properties); }

    /** The stack as an {@code anchor}, with every component (binding, damage, custom name) carried over. */
    static ItemStack convert(ItemStack horn, Item anchor) { return horn.transmuteCopy(anchor, horn.getCount()); }
    private static ItemStack anchor(ItemStack horn) { return convert(horn, SkydockBlocks.WARP_ANCHOR.get()); }

    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof Player player)) return;
        // The slot index is per inventory section (main, armour, offhand), so the stack is found by identity.
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) { inventory.setItem(i, anchor(stack)); return; }
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.setItemInHand(hand, anchor(player.getItemInHand(hand)));
        return player.getItemInHand(hand).use(level, player, hand);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer(); if (player == null) return InteractionResult.PASS;
        player.setItemInHand(context.getHand(), anchor(context.getItemInHand()));
        return player.getItemInHand(context.getHand()).useOn(new UseOnContext(player, context.getHand(),
                new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside())));
    }
}
