package dev.skydock.cargo;

import com.google.gson.*;
import dev.skydock.Skydock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Loads {@code data/<ns>/cargo/shop.json}; an invalid file is logged and the defaults kept. */
public final class ShopLoader extends SimpleJsonResourceReloadListener {
    public ShopLoader() { super(new Gson(), "cargo"); }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<ShopEntry> next = null;
        JsonElement file = files.get(Skydock.id("shop"));
        if (file != null) {
            try { next = parse(file.getAsJsonObject()); }
            catch (RuntimeException ex) { Skydock.LOGGER.error("Invalid Skydock cargo shop", ex); }
        }
        Shop.setData(next);
        Shop.resyncTerminals();
    }

    /** Each entry has a {@code rank} (1 or more), a {@code price} (0 or more) and exactly one of {@code item} (with an optional {@code count}) or {@code pattern}. */
    static List<ShopEntry> parse(JsonObject json) {
        List<ShopEntry> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            int rank = o.get("rank").getAsInt(), price = o.get("price").getAsInt();
            if (rank < 1) throw new IllegalArgumentException("Shop entry rank must be 1 or more: " + o);
            if (price < 0) throw new IllegalArgumentException("Shop entry price must not be negative: " + o);
            if (o.has("item") == o.has("pattern")) throw new IllegalArgumentException("Shop entry needs exactly one of item or pattern: " + o);
            if (o.has("pattern")) { out.add(new ShopEntry(rank, price, ItemStack.EMPTY, ResourceLocation.parse(o.get("pattern").getAsString()))); continue; }
            ResourceLocation id = ResourceLocation.parse(o.get("item").getAsString());
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new IllegalArgumentException("Unknown shop item " + id));
            int count = o.has("count") ? o.get("count").getAsInt() : 1;
            ItemStack stack = new ItemStack(item, Math.max(1, count));
            if (count < 1 || count > stack.getMaxStackSize()) throw new IllegalArgumentException("Shop entry count for " + id + " must be 1 to " + stack.getMaxStackSize());
            out.add(new ShopEntry(rank, price, stack, null));
        }
        return List.copyOf(out);
    }
}
