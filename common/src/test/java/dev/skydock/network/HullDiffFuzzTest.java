package dev.skydock.network;

import dev.skydock.fuzz.Fuzz;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Applying a hull delta to the old hull must give the new one, with "removed" standing for an empty cell. */
class HullDiffFuzzTest {
    static final String AIR = "air";

    static Map<BlockPos, String> hull(Random r, int n) {
        Map<BlockPos, String> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(new BlockPos(r.nextInt(8), r.nextInt(4), r.nextInt(8)), r.nextInt(8) == 0 ? AIR : "s" + r.nextInt(5));
        return m;
    }
    static Map<BlockPos, String> solid(Map<BlockPos, String> m) { Map<BlockPos, String> out = new HashMap<>(m); out.values().removeIf(AIR::equals); return out; }

    @Test void deltasRebuildTheNewHull() {
        Fuzz.check("HullDiff.diff", 10000, (r, log) -> {
            Map<BlockPos, String> before = hull(r, r.nextInt(60)), after = new LinkedHashMap<>(before);
            for (int i = r.nextInt(30); i > 0; i--) switch (r.nextInt(3)) {
                case 0 -> after.remove(Fuzz.pick(r, new ArrayList<>(before.keySet().isEmpty() ? Set.of(BlockPos.ZERO) : before.keySet())));
                case 1 -> after.put(new BlockPos(r.nextInt(8), r.nextInt(4), r.nextInt(8)), r.nextInt(6) == 0 ? AIR : "s" + r.nextInt(5));
                default -> {}
            }
            log.append(before).append(" -> ").append(after);
            Map<BlockPos, String> delta = HullDiff.diff(before, after, AIR);
            Map<BlockPos, String> rebuilt = new HashMap<>(before); rebuilt.putAll(delta);
            assertEquals(solid(after), solid(rebuilt), "old hull + delta = new hull");
            delta.forEach((p, s) -> assertNotEquals(before.get(p), s, "a delta only carries changed cells: " + p));
            assertEquals(Map.of(), HullDiff.diff(after, after, AIR), "no change, no delta");
        });
    }

    @Test void sendPlansFollowTheRevisionRules() {
        Fuzz.check("HullDiff.plan", 20000, (r, log) -> {
            int current = Fuzz.edgyInt(r), previous = r.nextBoolean() ? current - 1 : Fuzz.edgyInt(r);
            Integer seen = r.nextInt(5) == 0 ? null : Fuzz.pick(r, current, previous, Fuzz.edgyInt(r));
            int hull = Math.abs(Fuzz.edgyInt(r) % 100_000), changed = r.nextInt(hull + 2);
            HullDiff.Send plan = HullDiff.plan(seen, previous, current, changed, hull);
            log.append(seen).append(' ').append(previous).append(' ').append(current).append(' ').append(changed).append('/').append(hull);
            HullDiff.Send expected = seen != null && seen == current ? HullDiff.Send.NONE : seen != null && seen == previous && 4L * changed <= hull ? HullDiff.Send.DELTA : HullDiff.Send.FULL;
            assertEquals(expected, plan);
        });
    }
}
