package dev.skydock.block;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** LiftCellBlock cannot be constructed once the registries are frozen, so the N/E/S/W permutation is tested as a pure function; the block overrides only wrap it. Index order is N, E, S, W. */
class LiftCellRotationTest {
    private static boolean[] bits(int n) { return new boolean[]{(n & 1) != 0, (n & 2) != 0, (n & 4) != 0, (n & 8) != 0}; }

    @Test void clockwiseTurnsEastSouthIntoSouthWest() {
        assertArrayEquals(new boolean[]{false, false, true, true},
                LiftCellBlock.rotateConnections(new boolean[]{false, true, true, false}, Rotation.CLOCKWISE_90));
    }

    @Test void fourQuarterTurnsAreTheIdentityForEveryCombination() {
        for (int n = 0; n < 16; n++) {
            boolean[] r = bits(n);
            for (int i = 0; i < 4; i++) r = LiftCellBlock.rotateConnections(r, Rotation.CLOCKWISE_90);
            assertArrayEquals(bits(n), r, "bits " + n);
        }
    }

    @Test void clockwiseThenCounterclockwiseIsTheIdentityAndHalfTurnIsTwoQuarters() {
        for (int n = 0; n < 16; n++) {
            boolean[] cw = LiftCellBlock.rotateConnections(bits(n), Rotation.CLOCKWISE_90);
            assertArrayEquals(bits(n), LiftCellBlock.rotateConnections(cw, Rotation.COUNTERCLOCKWISE_90), "bits " + n);
            assertArrayEquals(LiftCellBlock.rotateConnections(bits(n), Rotation.CLOCKWISE_180), LiftCellBlock.rotateConnections(cw, Rotation.CLOCKWISE_90));
            assertArrayEquals(bits(n), LiftCellBlock.rotateConnections(bits(n), Rotation.NONE));
        }
    }

    @Test void mirrorsSwapTheMatchingAxis() {
        boolean[] northEast = {true, true, false, false};
        assertArrayEquals(new boolean[]{false, true, true, false}, LiftCellBlock.mirrorConnections(northEast, Mirror.LEFT_RIGHT));
        assertArrayEquals(new boolean[]{true, false, false, true}, LiftCellBlock.mirrorConnections(northEast, Mirror.FRONT_BACK));
        assertArrayEquals(northEast, LiftCellBlock.mirrorConnections(northEast, Mirror.NONE));
    }
}
