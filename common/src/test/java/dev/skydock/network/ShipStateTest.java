package dev.skydock.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipStateTest {
    private static ShipState state(UUID pilot, Vec3 anchor, Map<UUID, BlockPos> seated) {
        return new ShipState(UUID.randomUUID(), 123456L, 100.25, 80.5, -300.125, 271.5, new Vec3(.25, -.5, 1.5), 1.25,
                true, 0.75, false, true, pilot, anchor, seated, 640, 700.5, Double.NaN, Double.NaN);
    }
    private static ShipState roundTrip(ShipState state) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ShipState.CODEC.encode(buf, state);
        ShipState decoded = ShipState.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
        return decoded;
    }
    @Test void roundTripsEveryField() {
        Map<UUID, BlockPos> seated = new LinkedHashMap<>();
        seated.put(UUID.randomUUID(), new BlockPos(3, 4, 5)); seated.put(UUID.randomUUID(), new BlockPos(-1, 2, 9));
        ShipState state = state(UUID.randomUUID(), new Vec3(2.5, 1, 4.5), seated);
        assertEquals(state, roundTrip(state));
        ShipState held = new ShipState(state.id(), 7, 1, 2, 3, 4, Vec3.ZERO, 0, false, 0, true, false, null, null, Map.of(), 1, 2, 96.5, 45);
        assertEquals(held, roundTrip(held));
    }
    @Test void roundTripsWithoutPilot() {
        ShipState state = state(null, null, Map.of());
        assertEquals(state, roundTrip(state));
    }
    @Test void encodedSizeIsSmall() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ShipState.CODEC.encode(buf, state(UUID.randomUUID(), new Vec3(2.5, 1, 4.5), Map.of()));
        assertTrue(buf.readableBytes() <= 128, "pose packet is " + buf.readableBytes() + " bytes");
    }
    @Test void sameMotionIgnoresOnlyTheTick() {
        ShipState a = state(null, null, Map.of());
        assertTrue(a.sameMotion(new ShipState(a.id(), a.tick() + 20, a.x(), a.y(), a.z(), a.yaw(), a.velocity(), a.yawVelocity(), a.cruise(),
                a.cruiseSpeed(), a.moored(), a.blocked(), null, null, Map.of(), a.mass(), a.lift(), Double.NaN, Double.NaN)));
        assertFalse(a.sameMotion(new ShipState(a.id(), a.tick(), a.x() + 1, a.y(), a.z(), a.yaw(), a.velocity(), a.yawVelocity(), a.cruise(),
                a.cruiseSpeed(), a.moored(), a.blocked(), null, null, Map.of(), a.mass(), a.lift(), Double.NaN, Double.NaN)));
    }
}
