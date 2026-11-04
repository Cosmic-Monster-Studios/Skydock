package dev.skydock.fuzz;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Damages datapack JSON the way hand-written files go wrong: missing keys, wrong types, extreme or fractional numbers, odd strings. */
public final class JsonFuzz {
    private JsonFuzz() {}

    /** A deep copy of {@code json} with about {@code rate} of its nodes damaged, always at least one. */
    public static JsonElement mutate(Random r, JsonElement json, double rate, StringBuilder log) {
        JsonElement copy = json.deepCopy();
        if (copy.isJsonObject() || copy.isJsonArray()) { if (!walk(r, copy, rate, log, "$") ) damageOne(r, copy, log); }
        else { copy = randomJson(r, 0); log.append("root=").append(copy).append("; "); }
        return copy;
    }

    private static boolean walk(Random r, JsonElement node, double rate, StringBuilder log, String path) {
        boolean any = false;
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            for (String key : new ArrayList<>(o.keySet())) {
                if (r.nextDouble() < rate) { damageMember(r, o, key, log, path); any = true; }
                else if (o.has(key)) any |= walk(r, o.get(key), rate, log, path + "." + key);
            }
            if (r.nextDouble() < rate / 3) { String k = Fuzz.pick(r, "extra", "", ".", "level", "rank", "unlock"); o.add(k, randomJson(r, 1)); log.append("add ").append(path).append('.').append(k).append("; "); any = true; }
        } else if (node.isJsonArray()) {
            JsonArray a = node.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (r.nextDouble() < rate) { JsonElement next = damaged(r, a.get(i)); a.set(i, next); log.append("set ").append(path).append('[').append(i).append("]=").append(abbreviate(next)).append("; "); any = true; }
                else any |= walk(r, a.get(i), rate, log, path + "[" + i + "]");
            }
            if (r.nextDouble() < rate / 3 && !a.isEmpty()) { a.add(a.get(r.nextInt(a.size())).deepCopy()); log.append("dup in ").append(path).append("; "); any = true; }
            if (r.nextDouble() < rate / 3 && !a.isEmpty()) { a.remove(r.nextInt(a.size())); log.append("drop in ").append(path).append("; "); any = true; }
        }
        return any;
    }
    private static void damageOne(Random r, JsonElement root, StringBuilder log) {
        if (root.isJsonObject() && !root.getAsJsonObject().isEmpty()) damageMember(r, root.getAsJsonObject(), Fuzz.pick(r, new ArrayList<>(root.getAsJsonObject().keySet())), log, "$");
        else if (root.isJsonArray() && !root.getAsJsonArray().isEmpty()) root.getAsJsonArray().set(0, damaged(r, root.getAsJsonArray().get(0)));
    }
    private static void damageMember(Random r, JsonObject o, String key, StringBuilder log, String path) {
        if (r.nextInt(4) == 0) { o.remove(key); log.append("remove ").append(path).append('.').append(key).append("; "); return; }
        JsonElement next = damaged(r, o.get(key)); o.add(key, next); log.append("set ").append(path).append('.').append(key).append('=').append(abbreviate(next)).append("; ");
    }
    private static String abbreviate(JsonElement e) { String s = String.valueOf(e); return s.length() > 100 ? s.substring(0, 100) + "..." : s; }

    /** A nearby wrong value: same kind but extreme, or a different kind altogether. */
    static JsonElement damaged(Random r, JsonElement e) {
        if (r.nextBoolean()) return randomJson(r, 1);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return number(r);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            return new JsonPrimitive(switch (r.nextInt(8)) {
                case 0 -> ""; case 1 -> s.toUpperCase(java.util.Locale.ROOT); case 2 -> s + Fuzz.pick(r, "x", ".", " ", "😀", "\u0000", "Ω");
                case 3 -> s.isEmpty() ? "?" : s.substring(0, r.nextInt(s.length())); case 4 -> s.repeat(2 + r.nextInt(40));
                case 5 -> Fuzz.pick(r, "minecraft:air", "minecraft:not_a_block", "Not:Valid Id", "minecraft:oak_stairs[facing=up]", "minecraft:stone{a:1}", "#minecraft:logs");
                default -> new StringBuilder(s).reverse().toString();
            });
        }
        if (e.isJsonArray()) { JsonArray a = new JsonArray(); for (int i = r.nextInt(3); i > 0; i--) a.add(randomJson(r, 2)); return a; }
        return randomJson(r, 1);
    }
    public static JsonElement number(Random r) {
        return new JsonPrimitive(switch (r.nextInt(9)) {
            case 0 -> 0; case 1 -> -1; case 2 -> Integer.MAX_VALUE; case 3 -> Integer.MIN_VALUE; case 4 -> 4294967297L;
            case 5 -> 1.5; case 6 -> new BigDecimal("1e20"); case 7 -> 2.9999; default -> Fuzz.edgyInt(r);
        });
    }
    public static JsonElement randomJson(Random r, int depth) {
        return switch (r.nextInt(depth > 2 ? 4 : 7)) {
            case 0 -> JsonNull.INSTANCE;
            case 1 -> number(r);
            case 2 -> new JsonPrimitive(Fuzz.pick(r, "", "a", "x", "shop", "tin", "TIN", "scout", "minecraft:stone", "skydock:none", "1", "true"));
            case 3 -> new JsonPrimitive(r.nextBoolean());
            case 4 -> { JsonArray a = new JsonArray(); for (int i = r.nextInt(3); i > 0; i--) a.add(randomJson(r, depth + 1)); yield a; }
            default -> { JsonObject o = new JsonObject(); for (int i = r.nextInt(3); i > 0; i--) o.add(Fuzz.pick(r, "a", "rank", "item", "count", "level"), randomJson(r, depth + 1)); yield o; }
        };
    }
    /** Every primitive member name in a tree, for targeted edits. */
    public static List<String> keys(JsonObject o) { List<String> out = new ArrayList<>(); for (Map.Entry<String, JsonElement> e : o.entrySet()) out.add(e.getKey()); return out; }
}
