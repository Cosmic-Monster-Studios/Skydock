package dev.skydock.ship;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipAttachmentTest {
    private static final AABB HULL = new AABB(0, 60, 0, 10, 66, 20);

    @Test void crewOnOrBesideTheHullStaysAttached() {
        assertFalse(ShipAttachment.strayed(HULL, new Vec3(5, 66, 10)), "standing on deck");
        assertFalse(ShipAttachment.strayed(HULL, new Vec3(5, 63, 10)), "inside the hull box");
        assertFalse(ShipAttachment.strayed(HULL, new Vec3(18, 66, 10)), "exactly eight blocks off the side");
        assertFalse(ShipAttachment.strayed(HULL, new Vec3(5, 74, 10)), "eight blocks above the deck");
    }
    @Test void anEntityFartherThanEightBlocksFromTheHullHasStrayed() {
        assertTrue(ShipAttachment.strayed(HULL, new Vec3(18.01, 66, 10)));
        assertTrue(ShipAttachment.strayed(HULL, new Vec3(5, 51.9, 10)), "fell or teleported below");
        assertTrue(ShipAttachment.strayed(HULL, new Vec3(16, 66, 26)), "diagonal distance counts, not each axis");
        assertTrue(ShipAttachment.strayed(HULL, new Vec3(500, 70, -300)), "teleported away");
    }
    @Test void theDeckIsRestoredOnlyForAMoveThatMissedItsTargetWithNoTeleportPendingBefore() {
        Vec3 target = new Vec3(5, 66, 10), shortOf = new Vec3(5, 66, 9.5);
        assertTrue(ShipManager.correctsDeck(target, false, shortOf), "a rejected move vanilla rubber-banded (its own teleport is set only after the packet)");
        assertFalse(ShipManager.correctsDeck(target, true, shortOf), "a /tp was pending before the packet: vanilla ignored the move");
        assertFalse(ShipManager.correctsDeck(target, false, target), "the move landed");
        assertFalse(ShipManager.correctsDeck(null, false, shortOf), "no accepted deck target");
    }
}
