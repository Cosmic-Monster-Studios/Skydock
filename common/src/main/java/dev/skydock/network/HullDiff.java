package dev.skydock.network;

import net.minecraft.core.BlockPos;
import java.util.*;

/** Decides whether a player gets a hull change as a delta or a whole hull, and what that delta holds. */
public final class HullDiff {
    public enum Send { NONE, DELTA, FULL }
    private HullDiff() {}

    /** Cells whose value differs between the two hulls; a cell missing from {@code after} maps to {@code removed}. */
    public static <S> Map<BlockPos, S> diff(Map<BlockPos, S> before, Map<BlockPos, S> after, S removed) {
        Map<BlockPos, S> changed = new LinkedHashMap<>();
        after.forEach((pos, state) -> { if (!Objects.equals(before.get(pos), state)) changed.put(pos, state); });
        before.forEach((pos, state) -> { if (!after.containsKey(pos) && !Objects.equals(state, removed)) changed.put(pos, removed); });
        return changed;
    }
    /** A delta needs the player's copy at the previous revision and at most a quarter of the hull changed. */
    public static Send plan(Integer seenRevision, int previousRevision, int currentRevision, int changed, int hullSize) {
        if (seenRevision != null && seenRevision == currentRevision) return Send.NONE;
        if (seenRevision != null && seenRevision == previousRevision && changed * 4 <= hullSize) return Send.DELTA;
        return Send.FULL;
    }
}
