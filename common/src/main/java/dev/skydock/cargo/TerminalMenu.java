package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** The slotless menu behind the terminal screen; it only pins which terminal the player is using. */
public final class TerminalMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public TerminalMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos()); }
    public TerminalMenu(int id, Inventory inventory, BlockPos pos) { super(SkydockBlocks.TERMINAL_MENU.get(), id); this.pos = pos.immutable(); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) {
        return player.level().getBlockState(pos).is(SkydockBlocks.CARGO_TERMINAL.get()) && player.distanceToSqr(pos.getCenter()) <= 64;
    }
}
