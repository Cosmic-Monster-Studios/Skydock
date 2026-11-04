package dev.skydock.fuzz;

import net.minecraft.nbt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Corrupts saved tags the way bad saves, hand edits and old versions do: keys missing, wrong types, extreme numbers, unknown names. */
public final class NbtFuzz {
    private NbtFuzz() {}

    /** A deep copy of {@code tag} with roughly {@code rate} of its entries (at any depth) damaged; at least one entry is touched when there is any. */
    public static CompoundTag corrupt(Random r, CompoundTag tag, double rate, StringBuilder log) {
        CompoundTag out = tag.copy();
        if (!corruptInto(r, out, rate, log, "") && !out.getAllKeys().isEmpty()) {
            String key = Fuzz.pick(r, new ArrayList<>(out.getAllKeys())); damage(r, out, key, log, "");
        }
        return out;
    }

    private static boolean corruptInto(Random r, CompoundTag tag, double rate, StringBuilder log, String path) {
        boolean any = false;
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            Tag value = tag.get(key);
            if (r.nextDouble() < rate) { damage(r, tag, key, log, path); any = true; }
            else if (value instanceof CompoundTag c) any |= corruptInto(r, c, rate, log, path + key + ".");
            else if (value instanceof ListTag list) for (int i = 0; i < list.size(); i++) if (list.get(i) instanceof CompoundTag c) any |= corruptInto(r, c, rate, log, path + key + "[" + i + "].");
        }
        if (r.nextDouble() < rate / 4) { tag.put("Junk" + r.nextInt(100), randomTag(r, 0)); any = true; }
        return any;
    }

    private static void damage(Random r, CompoundTag tag, String key, StringBuilder log, String path) {
        Tag before = tag.get(key);
        switch (r.nextInt(5)) {
            case 0 -> { tag.remove(key); log.append("remove ").append(path).append(key).append("; "); return; }
            case 1 -> { // same type, extreme value
                Tag next = extreme(r, before); tag.put(key, next);
            }
            default -> tag.put(key, randomTag(r, 0));
        }
        log.append("set ").append(path).append(key).append('=').append(abbreviate(tag.get(key))).append("; ");
    }

    private static String abbreviate(Tag t) { String s = String.valueOf(t); return s.length() > 80 ? s.substring(0, 80) + "..." : s; }

    private static Tag extreme(Random r, Tag before) {
        return switch (before) {
            case IntTag i -> IntTag.valueOf(Fuzz.pick(r, Integer.MAX_VALUE, Integer.MIN_VALUE, -1, -i.getAsInt(), 1 << 30));
            case LongTag l -> LongTag.valueOf(Fuzz.pick(r, Long.MAX_VALUE, Long.MIN_VALUE, -1L, -l.getAsLong()));
            case DoubleTag d -> DoubleTag.valueOf(Fuzz.pick(r, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -d.getAsDouble(), 1e300));
            case StringTag s -> StringTag.valueOf(Fuzz.pick(r, "", "UNKNOWN", s.getAsString().toLowerCase(java.util.Locale.ROOT), "minecraft:", "Not A:Valid/Id", "\u0000", s.getAsString() + "x"));
            case ByteTag b -> ByteTag.valueOf((byte) (int) Fuzz.pick(r, 2, -1, 127, -128));
            case IntArrayTag a -> new IntArrayTag(new int[]{-1, Integer.MAX_VALUE, Integer.MIN_VALUE, a.size()});
            case LongArrayTag a -> new LongArrayTag(new long[]{-1, Long.MAX_VALUE});
            case ListTag l -> { ListTag out = new ListTag(); for (int i = 0; i < 1 + r.nextInt(3); i++) out.add(StringTag.valueOf("x" + i)); yield out; }
            default -> randomTag(r, 0);
        };
    }

    public static Tag randomTag(Random r, int depth) {
        return switch (r.nextInt(depth > 1 ? 9 : 11)) {
            case 0 -> IntTag.valueOf(Fuzz.edgyInt(r));
            case 1 -> LongTag.valueOf(Fuzz.edgyLong(r));
            case 2 -> DoubleTag.valueOf(Fuzz.edgyDouble(r));
            case 3 -> StringTag.valueOf(Fuzz.pick(r, "", "UNKNOWN", "north", "TIN", "SCOUT", "minecraft:overworld", "Bad Id!", "skydock:x", String.valueOf(r.nextInt())));
            case 4 -> ByteTag.valueOf((byte) r.nextInt(256));
            case 5 -> new IntArrayTag(randomInts(r));
            case 6 -> new LongArrayTag(new long[]{Fuzz.edgyLong(r), Fuzz.edgyLong(r)});
            case 7 -> FloatTag.valueOf((float) Fuzz.edgyDouble(r));
            case 8 -> ShortTag.valueOf((short) r.nextInt());
            case 9 -> { CompoundTag c = new CompoundTag(); for (int i = r.nextInt(3); i > 0; i--) c.put("K" + i, randomTag(r, depth + 1)); yield c; }
            default -> { ListTag l = new ListTag(); Tag first = randomTag(r, depth + 1); l.add(first); yield l; }
        };
    }
    private static int[] randomInts(Random r) { int[] out = new int[r.nextInt(5)]; for (int i = 0; i < out.length; i++) out[i] = Fuzz.edgyInt(r); return out; }

    /** Every compound in {@code tag} (itself included), for targeted damage. */
    public static List<CompoundTag> compounds(CompoundTag tag) {
        List<CompoundTag> out = new ArrayList<>(); out.add(tag);
        for (String key : tag.getAllKeys()) {
            Tag v = tag.get(key);
            if (v instanceof CompoundTag c) out.addAll(compounds(c));
            else if (v instanceof ListTag l) for (Tag t : l) if (t instanceof CompoundTag c) out.addAll(compounds(c));
        }
        return out;
    }
}
