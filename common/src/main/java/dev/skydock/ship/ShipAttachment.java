package dev.skydock.ship;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class ShipAttachment {
    /** How far, in blocks, an attached entity may be from its hull's bounds before the server lets go of it. */
    public static final double STRAY_DISTANCE = 8;
    private UUID ship;
    private Vec3 carrierVelocity = Vec3.ZERO;
    private ShipMovementFrame.Frame pendingFrame;
    private long lastReceivedSequence = -1;
    private long lastAcceptedSequence = -1;
    private boolean detachAfterMovement;
    private Vec3 acceptedMovementTarget;

    /** Whether {@code position} is farther than {@link #STRAY_DISTANCE} from {@code hull}: a teleport or a dropped departing frame left the entity attached. */
    public static boolean strayed(AABB hull, Vec3 position) { return hull.distanceToSqr(position) > STRAY_DISTANCE * STRAY_DISTANCE; }
    public UUID ship() { return ship; }
    public boolean is(UUID id) { return id != null && id.equals(ship); }
    public void attach(UUID id) {
        if (!id.equals(ship)) carrierVelocity = Vec3.ZERO;
        ship = id;
    }
    public void carried(Vec3 velocity) { carrierVelocity = velocity; }
    public void detach(Entity entity, boolean inheritMotion) {
        if (ship != null && inheritMotion && carrierVelocity.lengthSqr() > 0)
            entity.setDeltaMovement(entity.getDeltaMovement().add(carrierVelocity));
        ship = null;
        carrierVelocity = Vec3.ZERO;
        pendingFrame = null;
        detachAfterMovement = false;
        acceptedMovementTarget = null;
    }

    public void receive(ShipMovementFrame.Frame frame) {
        if (frame.sequence() <= lastReceivedSequence) return;
        lastReceivedSequence = frame.sequence();
        pendingFrame = frame;
    }
    public ShipMovementFrame.Frame consume() {
        ShipMovementFrame.Frame frame = pendingFrame;
        pendingFrame = null;
        return frame;
    }
    public long lastAcceptedSequence() { return lastAcceptedSequence; }
    public void accepted(long sequence) { lastAcceptedSequence = sequence; }
    public void detachAfterMovement(boolean value) { detachAfterMovement = value; }
    public void acceptedMovementTarget(Vec3 value) { acceptedMovementTarget = value; }
    public Vec3 consumeAcceptedMovementTarget() {
        Vec3 value = acceptedMovementTarget;
        acceptedMovementTarget = null;
        return value;
    }
    public boolean consumeDetachAfterMovement() {
        boolean value = detachAfterMovement;
        detachAfterMovement = false;
        return value;
    }
}
