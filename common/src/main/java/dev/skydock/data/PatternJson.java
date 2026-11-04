package dev.skydock.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.skydock.block.SkydockBlocks;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The datapack ship pattern file format. {@code layers} hold one list of rows per y level (bottom-up), each row a
 * string with one character per x and rows ordered from low z; {@code .} and space are air. Every other character
 * must be a {@code palette} key naming a block state; {@code decorations} lists the palette keys that are decorations.
 * The optional {@code "unlock": "shop"} makes the pattern shop-only.
 */
public final class PatternJson {
    /** Writer palette: ASCII letters and digits first, then every BMP letter in U+00C0-U+1FFF (about 4,000), each a single UTF-16 unit. */
    private static final String CHARACTERS = characters();
    private static String characters() {
        StringBuilder text = new StringBuilder("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        for (char c = 0xC0; c <= 0x1FFF; c++) if (Character.isLetter(c)) text.append(c);
        return text.toString();
    }
    private PatternJson() {}

    /** Parses against Skydock's helm; production passes {@link #state} as {@code states}. */
    public static ShipPattern parse(ResourceLocation id, JsonObject json, Function<String, BlockState> states) {
        return parse(id, json, states, state -> state.is(SkydockBlocks.HELM.get()));
    }

    /** Throws {@link IllegalArgumentException} with a readable message on any error, so a bad file is rejected whole. */
    public static ShipPattern parse(ResourceLocation id, JsonObject json, Function<String, BlockState> states, Predicate<BlockState> helm) {
        String name = string(json, "name"), description = json.has("description") ? string(json, "description") : "";
        String tierKey = string(json, "tier");
        DockTier tier = null;
        for (DockTier candidate : DockTier.values()) if (candidate.key().equals(tierKey)) tier = candidate;
        if (tier == null) throw new IllegalArgumentException("Unknown tier '" + tierKey + "'");
        boolean shopOnly = false;
        if (json.has("unlock")) {
            String unlock = json.get("unlock").isJsonPrimitive() ? json.get("unlock").getAsString() : json.get("unlock").toString();
            if (!unlock.equals("shop")) throw new IllegalArgumentException("Unknown unlock '" + unlock + "'; the only value is 'shop'");
            shopOnly = true;
        }
        if (!json.has("palette") || !json.get("palette").isJsonObject()) throw new IllegalArgumentException("Missing palette object");
        Map<Character, BlockState> palette = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("palette").entrySet()) {
            String key = entry.getKey();
            if (key.length() != 1 || key.charAt(0) == '.' || key.charAt(0) == ' ')
                throw new IllegalArgumentException("Palette key '" + key + "' must be one character other than '.' and space");
            if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("Palette entry '" + key + "' must be a block state string");
            String text = entry.getValue().getAsString();
            BlockState state;
            try { state = states.apply(text); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Palette entry '" + key + "': " + ex.getMessage(), ex); }
            if (state == null) throw new IllegalArgumentException("Palette entry '" + key + "': unknown block state " + text);
            palette.put(key.charAt(0), state);
        }
        Set<Character> decorations = new HashSet<>();
        if (json.has("decorations")) for (JsonElement element : array(json, "decorations")) {
            String key = element.isJsonPrimitive() ? element.getAsString() : "";
            if (key.length() != 1 || !palette.containsKey(key.charAt(0)))
                throw new IllegalArgumentException("Decoration '" + key + "' is not a palette key");
            decorations.add(key.charAt(0));
        }
        JsonArray layers = array(json, "layers");
        if (layers.size() > tier.height) throw new IllegalArgumentException(layers.size() + " layers exceed the " + tier.key() + " dock height " + tier.height);
        List<ShipPattern.Block> blocks = new ArrayList<>();
        for (int y = 0; y < layers.size(); y++) {
            if (!layers.get(y).isJsonArray()) throw new IllegalArgumentException("Layer " + y + " must be a list of rows");
            JsonArray rows = layers.get(y).getAsJsonArray();
            if (rows.size() > tier.length) throw new IllegalArgumentException("Layer " + y + " has " + rows.size() + " rows; the " + tier.key() + " dock is " + tier.length + " long");
            for (int z = 0; z < rows.size(); z++) {
                if (!rows.get(z).isJsonPrimitive()) throw new IllegalArgumentException("Layer " + y + " row " + z + " must be a string");
                String row = rows.get(z).getAsString();
                if (row.length() > tier.width) throw new IllegalArgumentException("Layer " + y + " row " + z + " is " + row.length() + " wide; the " + tier.key() + " dock is " + tier.width + " wide");
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == '.' || c == ' ') continue;
                    BlockState state = palette.get(c);
                    if (state == null) throw new IllegalArgumentException("Unknown palette character '" + c + "' at layer " + y + " row " + z + " column " + x);
                    blocks.add(new ShipPattern.Block(new BlockPos(x, y, z), state, decorations.contains(c)));
                }
            }
        }
        long helms = blocks.stream().filter(cell -> helm.test(cell.state())).count();
        if (helms != 1) throw new IllegalArgumentException("A pattern needs exactly one helm, found " + helms);
        return new ShipPattern(id, name, description, tier, blocks, shopOnly);
    }

    /** Palette characters come from {@link #CHARACTERS}; a block that appears both as hull and decoration gets two. */
    public static JsonObject write(ShipPattern pattern, Function<BlockState, String> states) {
        Map<ShipPattern.Block, Character> keys = new LinkedHashMap<>(); // keyed by (state, decoration) with pos zeroed
        int maxX = 0, maxY = 0, maxZ = 0;
        for (ShipPattern.Block cell : pattern.blocks()) {
            keys.computeIfAbsent(new ShipPattern.Block(BlockPos.ZERO, cell.state(), cell.decoration()), ignored -> {
                if (keys.size() >= CHARACTERS.length()) throw new IllegalArgumentException("A pattern file holds at most " + CHARACTERS.length() + " distinct blocks");
                return CHARACTERS.charAt(keys.size());
            });
            maxX = Math.max(maxX, cell.pos().getX()); maxY = Math.max(maxY, cell.pos().getY()); maxZ = Math.max(maxZ, cell.pos().getZ());
        }
        char[][][] grid = new char[maxY + 1][maxZ + 1][maxX + 1];
        for (char[][] layer : grid) for (char[] row : layer) Arrays.fill(row, '.');
        for (ShipPattern.Block cell : pattern.blocks())
            grid[cell.pos().getY()][cell.pos().getZ()][cell.pos().getX()] = keys.get(new ShipPattern.Block(BlockPos.ZERO, cell.state(), cell.decoration()));
        JsonObject json = new JsonObject();
        json.addProperty("name", pattern.name());
        json.addProperty("description", pattern.description());
        json.addProperty("tier", pattern.tier().key());
        if (pattern.shopOnly()) json.addProperty("unlock", "shop");
        JsonObject palette = new JsonObject();
        JsonArray decorations = new JsonArray();
        for (Map.Entry<ShipPattern.Block, Character> entry : keys.entrySet()) {
            palette.addProperty(entry.getValue().toString(), states.apply(entry.getKey().state()));
            if (entry.getKey().decoration()) decorations.add(new JsonPrimitive(entry.getValue().toString()));
        }
        json.add("palette", palette);
        if (!decorations.isEmpty()) json.add("decorations", decorations);
        JsonArray layers = new JsonArray();
        for (char[][] layer : grid) {
            JsonArray rows = new JsonArray();
            for (char[] row : layer) rows.add(new String(row));
            layers.add(rows);
        }
        json.add("layers", layers);
        return json;
    }

    /** The production block-state reader: the same syntax as {@code /setblock}, without NBT or tags. */
    public static BlockState state(String text) {
        try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState(); }
        catch (CommandSyntaxException ex) { throw new IllegalArgumentException(ex.getMessage(), ex); }
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing string '" + key + "'");
        return json.get(key).getAsString();
    }

    private static JsonArray array(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) throw new IllegalArgumentException("Missing list '" + key + "'");
        return json.getAsJsonArray(key);
    }
}
