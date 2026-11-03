package dev.skydock.item;

import dev.skydock.block.DockBlockEntity;
import dev.skydock.ship.ShipManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Optional;

/**
 * Brings a launched ship to its owner. It binds to a dock (used on the controller, or on the hull of a ship launched from it)
 * rather than to a ship, because each relaunch makes a new ship; blown anywhere else, it summons that dock's ship.
 */
public final class SummoningHornItem extends Item {
    public static final int COOLDOWN = 1200;
    public SummoningHornItem(Properties properties) { super(properties); }

    /** The dock a horn is bound to, kept in the stack's custom data. */
    public record Binding(ResourceKey<Level> dimension, BlockPos dock) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension.location().toString()); tag.putLong("Dock", dock.asLong());
            return tag;
        }
        public static Optional<Binding> load(CompoundTag tag) {
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
            if (dimension == null || !tag.contains("Dock")) return Optional.empty();
            return Optional.of(new Binding(ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(tag.getLong("Dock"))));
        }
    }
    public static Optional<Binding> binding(ItemStack stack) { return Binding.load(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()); }
    public static void bind(Player player, ItemStack stack, ResourceKey<Level> dimension, BlockPos dock) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(new Binding(dimension, dock.immutable()).save()));
        ShipManager.tell(player, "message.skydock.horn_bound", dock.toShortString());
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof DockBlockEntity dock)) return InteractionResult.PASS;
        Player player = context.getPlayer();
        if (player != null && !context.getLevel().isClientSide) {
            if (dock.canManage(player)) bind(player, context.getItemInHand(), context.getLevel().dimension(), dock.getBlockPos());
            else ShipManager.tell(player, "message.skydock.dock_not_owner");
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer server)) return InteractionResultHolder.consume(stack);
        Binding bound = binding(stack).orElse(null);
        if (bound == null) { ShipManager.tell(player, "message.skydock.horn_unbound"); return InteractionResultHolder.fail(stack); }
        if (!ShipManager.summon(server, bound.dimension(), bound.dock())) return InteractionResultHolder.fail(stack);
        player.getCooldowns().addCooldown(this, COOLDOWN);
        stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        return InteractionResultHolder.success(stack);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(binding(stack).map(b -> Component.translatable("item.skydock.summoning_horn.bound", b.dock().toShortString(), b.dimension().location().toString()))
                .orElseGet(() -> Component.translatable("item.skydock.summoning_horn.unbound")).withStyle(ChatFormatting.GRAY));
    }
}
