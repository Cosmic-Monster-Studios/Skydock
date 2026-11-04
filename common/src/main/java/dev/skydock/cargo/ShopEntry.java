package dev.skydock.cargo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** One line of the cargo shop: the rank it needs, its price in reputation, and either an item stack or a ship pattern (the other empty or null). */
public record ShopEntry(int rank, int price, ItemStack item, ResourceLocation pattern) {
    public ShopEntry { item = item == null ? ItemStack.EMPTY : item; }

    public boolean isPattern() { return pattern != null; }

    /** The entry as synced to the client; the item is kept by registry id and count. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Rank", rank); tag.putInt("Price", price);
        if (pattern != null) tag.putString("Pattern", pattern.toString());
        if (!item.isEmpty()) { tag.putString("Item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString()); tag.putInt("Count", item.getCount()); }
        return tag;
    }
    public static ShopEntry load(CompoundTag tag) {
        ItemStack item = tag.contains("Item") ? new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("Item"))), Math.max(1, tag.getInt("Count"))) : ItemStack.EMPTY;
        return new ShopEntry(tag.getInt("Rank"), tag.getInt("Price"), item, tag.contains("Pattern") ? ResourceLocation.parse(tag.getString("Pattern")) : null);
    }
}
