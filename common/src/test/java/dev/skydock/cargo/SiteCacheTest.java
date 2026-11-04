package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class SiteCacheTest {
    static final GlobalPos HERE = GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0)), THERE = GlobalPos.of(Level.OVERWORLD, new BlockPos(900, 64, 0));
    static int[] calls = {0};
    static Supplier<List<GlobalPos>> walker() { return () -> { calls[0]++; return List.of(THERE); }; }

    @Test void secondRequestWithinADayDoesNotWalkAgain() {
        SiteCache cache = new SiteCache(); calls[0] = 0;
        assertEquals(List.of(THERE), cache.get(HERE, 1, 800, 100, walker()));
        assertEquals(List.of(THERE), cache.get(HERE, 1, 800, 100 + Offers.DAY_TICKS - 1, walker()));
        assertEquals(1, calls[0]);
        cache.get(HERE, 1, 800, 100 + Offers.DAY_TICKS, walker());
        assertEquals(2, calls[0], "a day-old result is walked again");
    }
    @Test void eachTerminalAndRadiusHasItsOwnEntry() {
        SiteCache cache = new SiteCache(); calls[0] = 0;
        cache.get(HERE, 1, 800, 0, walker()); cache.get(HERE, 300, 7000, 0, walker()); cache.get(THERE, 1, 800, 0, walker());
        assertEquals(3, calls[0]);
        cache.get(HERE, 300, 7000, 10, walker());
        assertEquals(3, calls[0]);
    }
    @Test void clearForgetsEveryResult() {
        SiteCache cache = new SiteCache(); calls[0] = 0;
        cache.get(HERE, 1, 800, 0, walker()); cache.clear(); cache.get(HERE, 1, 800, 1, walker());
        assertEquals(2, calls[0]);
    }
    @Test void registeringOrRemovingATerminalClearsTheSiteCache() {
        calls[0] = 0; TerminalSites.CACHE.clear();
        TerminalSites.CACHE.get(HERE, 1, 800, 0, walker());
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(THERE, THERE.pos());
        TerminalSites.CACHE.get(HERE, 1, 800, 1, walker());
        assertEquals(2, calls[0]);
        data.removeTerminal(THERE, true);
        TerminalSites.CACHE.get(HERE, 1, 800, 2, walker());
        assertEquals(3, calls[0]);
        TerminalSites.CACHE.clear();
    }
}
