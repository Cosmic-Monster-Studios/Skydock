package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import java.util.*;
import java.util.function.Supplier;

/** Remembers site walks per terminal and search radius for up to one in-game day, so a board refilled on every open does not walk again. In memory only. */
public final class SiteCache {
    private record Key(GlobalPos at, int min, int max) {}
    private record Entry(List<GlobalPos> sites, long walkedAt) {}
    private final Map<Key, Entry> entries = new HashMap<>();

    /** The cached sites for this terminal and radius if walked less than a day before {@code now}, else the result of {@code walk}, remembered. */
    public List<GlobalPos> get(GlobalPos at, int min, int max, long now, Supplier<List<GlobalPos>> walk) {
        Key key = new Key(at, min, max); Entry e = entries.get(key);
        if (e == null || now - e.walkedAt() >= Offers.DAY_TICKS || now < e.walkedAt()) { e = new Entry(List.copyOf(walk.get()), now); entries.put(key, e); }
        return new ArrayList<>(e.sites());
    }
    public void clear() { entries.clear(); }
}
