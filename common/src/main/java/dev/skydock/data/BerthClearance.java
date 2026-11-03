package dev.skydock.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.function.Function;

/**
 * Whether a new dock's berth is free to build in. Placement is refused unless every cell is empty or clearable vegetation,
 * the berth fits the build height, and it overlaps no other dock's berth or launched hull. {@code error} is a translation
 * key (empty when clear) and {@code args} its arguments; {@code clearable} lists the vegetation to break once the controller is placed.
 */
public record BerthClearance(String error, List<Object> args, List<BlockPos> clearable) {
    /** What a world cell holds, as far as placing a berth is concerned. */
    public enum Cell { EMPTY, CLEARABLE, SOLID, FLUID }

    public boolean clear() { return error.isEmpty(); }

    public static BerthClearance check(DockFrame frame, int minBuildHeight, int maxBuildHeight, Function<BlockPos, Cell> world,
                                       Collection<AABB> otherBerths, Collection<AABB> hulls) {
        DockTier tier = frame.tier(); BlockPos origin = frame.origin();
        if (origin.getY() < minBuildHeight || origin.getY() + tier.height > maxBuildHeight) return refuse("error.skydock.berth_height");
        // Shrunk so berths that only share a face do not count as overlapping.
        AABB envelope = frame.envelope().deflate(1e-4);
        for (AABB berth : otherBerths) if (berth.intersects(envelope)) return refuse("error.skydock.berth_overlaps_dock");
        for (AABB hull : hulls) if (hull.intersects(envelope)) return refuse("error.skydock.berth_overlaps_ship");
        List<BlockPos> clearable = new ArrayList<>();
        int blocked = 0; BlockPos first = null; boolean fluid = false;
        for (BlockPos local : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(tier.width - 1, tier.height - 1, tier.length - 1))) {
            BlockPos at = frame.toWorld(local);
            Cell cell = world.apply(at);
            if (cell == Cell.CLEARABLE) clearable.add(at);
            else if (cell != Cell.EMPTY) { if (first == null) first = at; blocked++; fluid |= cell == Cell.FLUID; }
        }
        if (blocked > 0) return new BerthClearance(fluid ? "error.skydock.berth_fluid" : "error.skydock.berth_not_clear", List.of(blocked, first.toShortString()), List.of());
        return new BerthClearance("", List.of(), List.copyOf(clearable));
    }
    private static BerthClearance refuse(String key) { return new BerthClearance(key, List.of(), List.of()); }
}
