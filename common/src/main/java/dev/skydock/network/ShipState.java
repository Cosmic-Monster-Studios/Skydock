package dev.skydock.network;

import dev.skydock.Skydock;
import dev.skydock.ship.Ship;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * The per-tick pose of a launched ship as a binary {@code skydock:pose} payload. Positions and yaw are doubles; everything the
 * client only extrapolates or displays is a float. {@code holdAltitude} and {@code headingLock} are NaN when the hold is off.
 * {@code pilotAnchor} is the pilot's local standing point (null unless piloted), which the client needs to carry the pilot.
 */
public record ShipState(UUID id, long tick, double x, double y, double z, double yaw, Vec3 velocity, double yawVelocity,
                        boolean cruise, double cruiseSpeed, boolean moored, boolean blocked, UUID pilot, Vec3 pilotAnchor,
                        Map<UUID, BlockPos> seated, double mass, double lift, double holdAltitude, double headingLock)
        implements CustomPacketPayload {
    public static final Type<ShipState> TYPE = new Type<>(Skydock.id("pose"));
    private static final int CRUISE = 1, MOORED = 2, BLOCKED = 4, PILOT = 8, ANCHOR = 16;
    public static final StreamCodec<FriendlyByteBuf, ShipState> CODEC = StreamCodec.of(ShipState::write, ShipState::read);

    public static ShipState of(Ship ship, long tick) {
        return new ShipState(ship.id, tick, ship.pose.x(), ship.pose.y(), ship.pose.z(), ship.pose.yaw(), ship.velocity, ship.yawVelocity,
                ship.cruise, ship.cruiseSpeed, ship.moored, ship.blocked, ship.pilot, ship.pilot == null ? null : ship.pilotAnchor,
                new LinkedHashMap<>(ship.seated), ship.mass, ship.lift, ship.holdAltitude, ship.headingLock);
    }
    /** Copies the motion and crew fields onto the client's ship; the timeline sample is fed by the caller. */
    public void apply(Ship ship) {
        ship.velocity = velocity; ship.yawVelocity = yawVelocity; ship.cruise = cruise; ship.cruiseSpeed = cruiseSpeed;
        ship.pilot = pilot; ship.pilotAnchor = pilotAnchor;
        ship.lift = lift; ship.mass = mass; ship.blocked = blocked; ship.moored = moored;
        ship.holdAltitude = holdAltitude; ship.headingLock = headingLock;
        ship.seated.clear(); ship.seated.putAll(seated);
    }
    /** Every field except the tick; an unchanged ship only sends a heartbeat. */
    public boolean sameMotion(ShipState o) {
        return new ShipState(id, 0, x, y, z, yaw, velocity, yawVelocity, cruise, cruiseSpeed, moored, blocked, pilot, pilotAnchor, seated, mass, lift, holdAltitude, headingLock)
                .equals(new ShipState(o.id, 0, o.x, o.y, o.z, o.yaw, o.velocity, o.yawVelocity, o.cruise, o.cruiseSpeed, o.moored, o.blocked, o.pilot, o.pilotAnchor, o.seated, o.mass, o.lift, o.holdAltitude, o.headingLock));
    }
    private static void write(FriendlyByteBuf b, ShipState s) {
        b.writeUUID(s.id); b.writeVarLong(s.tick);
        b.writeDouble(s.x); b.writeDouble(s.y); b.writeDouble(s.z); b.writeDouble(s.yaw);
        b.writeFloat((float) s.velocity.x); b.writeFloat((float) s.velocity.y); b.writeFloat((float) s.velocity.z); b.writeFloat((float) s.yawVelocity);
        b.writeFloat((float) s.cruiseSpeed); b.writeFloat((float) s.mass); b.writeFloat((float) s.lift);
        b.writeFloat((float) s.holdAltitude); b.writeFloat((float) s.headingLock);
        boolean anchored = s.pilot != null && s.pilotAnchor != null;
        b.writeByte((s.cruise ? CRUISE : 0) | (s.moored ? MOORED : 0) | (s.blocked ? BLOCKED : 0) | (s.pilot != null ? PILOT : 0) | (anchored ? ANCHOR : 0));
        if (s.pilot != null) b.writeUUID(s.pilot);
        if (anchored) { b.writeFloat((float) s.pilotAnchor.x); b.writeFloat((float) s.pilotAnchor.y); b.writeFloat((float) s.pilotAnchor.z); }
        b.writeVarInt(s.seated.size());
        s.seated.forEach((player, pos) -> { b.writeUUID(player); b.writeBlockPos(pos); });
    }
    private static ShipState read(FriendlyByteBuf b) {
        UUID id = b.readUUID(); long tick = b.readVarLong();
        double x = b.readDouble(), y = b.readDouble(), z = b.readDouble(), yaw = b.readDouble();
        Vec3 velocity = new Vec3(b.readFloat(), b.readFloat(), b.readFloat());
        double yawVelocity = b.readFloat(), cruiseSpeed = b.readFloat(), mass = b.readFloat(), lift = b.readFloat();
        double holdAltitude = b.readFloat(), headingLock = b.readFloat();
        int flags = b.readUnsignedByte();
        UUID pilot = (flags & PILOT) != 0 ? b.readUUID() : null;
        Vec3 anchor = (flags & ANCHOR) != 0 ? new Vec3(b.readFloat(), b.readFloat(), b.readFloat()) : null;
        int count = b.readVarInt(); Map<UUID, BlockPos> seated = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) seated.put(b.readUUID(), b.readBlockPos());
        return new ShipState(id, tick, x, y, z, yaw, velocity, yawVelocity, (flags & CRUISE) != 0, cruiseSpeed, (flags & MOORED) != 0,
                (flags & BLOCKED) != 0, pilot, anchor, seated, mass, lift, holdAltitude, headingLock);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
