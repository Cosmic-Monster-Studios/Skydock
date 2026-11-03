package dev.skydock.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HullDiffTest {
    private static final BlockPos A = new BlockPos(0, 0, 0), B = new BlockPos(1, 0, 0), C = new BlockPos(0, 1, 0);

    @Test void diffFindsChangedAddedAndRemovedCells() {
        Map<BlockPos, String> before = new LinkedHashMap<>(); before.put(A, "oak"); before.put(B, "lift");
        Map<BlockPos, String> after = new LinkedHashMap<>(); after.put(A, "oak"); after.put(B, "air"); after.put(C, "glass");
        assertEquals(Map.of(B, "air", C, "glass"), HullDiff.diff(before, after, "air"));
        // A cell missing from the new hull is sent as the removed state.
        assertEquals(Map.of(B, "air"), HullDiff.diff(before, Map.of(A, "oak"), "air"));
        assertEquals(Map.of(), HullDiff.diff(before, before, "air"));
    }
    @Test void unchangedRevisionSendsNothing() {
        assertEquals(HullDiff.Send.NONE, HullDiff.plan(7, 6, 7, 3, 100));
    }
    @Test void previousRevisionWithSmallChangeSendsDelta() {
        assertEquals(HullDiff.Send.DELTA, HullDiff.plan(6, 6, 7, 3, 100));
        assertEquals(HullDiff.Send.DELTA, HullDiff.plan(6, 6, 7, 25, 100));
    }
    @Test void staleCopySendsFull() {
        assertEquals(HullDiff.Send.FULL, HullDiff.plan(5, 6, 7, 3, 100));
    }
    @Test void largeChangeSendsFull() {
        assertEquals(HullDiff.Send.FULL, HullDiff.plan(6, 6, 7, 100 / 4 + 1, 100));
    }
    @Test void unknownPlayerSendsFull() {
        assertEquals(HullDiff.Send.FULL, HullDiff.plan(null, 6, 7, 3, 100));
    }
}
