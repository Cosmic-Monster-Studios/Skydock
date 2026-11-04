package dev.skydock.ship;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TransferMenuTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    /** A paused transfer closes a menu showing one of its hull's containers, either half of a double chest included, and leaves the rest open. */
    @Test void menusOnAPausedHullAreFound() {
        Container hull = new SimpleContainer(27), outside = new SimpleContainer(27), inventory = new SimpleContainer(36);
        Set<Container> held = Collections.newSetFromMap(new IdentityHashMap<>()); held.add(hull);
        assertTrue(ShipManager.backedBy(List.of(hull, inventory), held));
        assertTrue(ShipManager.backedBy(List.of(new CompoundContainer(outside, hull), inventory), held), "a double chest with one half in the hull");
        assertFalse(ShipManager.backedBy(List.of(outside, inventory), held));
        assertFalse(ShipManager.backedBy(List.of(new CompoundContainer(outside, new SimpleContainer(27))), held));
    }
}
