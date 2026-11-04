package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import java.util.List;
import java.util.Optional;

/** A sealed cargo pod; its contract tag, cargo and destination live in the stack's custom data and follow it into the block entity and back. */
public final class CargoPodItem extends BlockItem {
    public CargoPodItem(net.minecraft.world.level.block.Block block, Properties properties) { super(block, properties); }

    public static ItemStack create(PodTag tag, String cargoKey, GlobalPos destination) {
        ItemStack stack = new ItemStack(SkydockBlocks.CARGO_POD_ITEM.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(new PodCargo(tag, cargoKey, destination).save()));
        return stack;
    }
    public static Optional<PodCargo> cargo(ItemStack stack) { return PodCargo.load(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()); }
    public static Optional<PodTag> tag(ItemStack stack) { return cargo(stack).map(PodCargo::tag); }

    /** The lang key for a cargo name: contracts carry full keys from {@link CargoNames}, older pods a bare name. */
    public static String nameKey(String cargoKey) { return cargoKey.startsWith("cargo.") ? cargoKey : "cargo.skydock.name." + cargoKey; }

    public static List<Component> describe(PodCargo cargo) {
        var pos = cargo.destination().pos();
        var lines = new java.util.ArrayList<Component>();
        lines.add(Component.translatable(nameKey(cargo.cargoKey())).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("item.skydock.cargo_pod.destination", pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), cargo.destination().dimension().location().toString()).withStyle(ChatFormatting.GRAY));
        if (cargo.tag().ejected()) lines.add(Component.translatable("item.skydock.cargo_pod.ejected").withStyle(ChatFormatting.RED));
        return lines;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) { cargo(stack).ifPresent(c -> lines.addAll(describe(c))); }
}
