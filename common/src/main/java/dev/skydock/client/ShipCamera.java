package dev.skydock.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipPose;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * Third-person view while at a helm or in a crew seat. It orbits the hull at a distance sized to the hull,
 * and zooming in glides the focus back to the player's eyes. Look direction stays the player's own, so the
 * view follows the deck through turns like first person does.
 */
public final class ShipCamera {
    static final KeyMapping ZOOM_IN = new KeyMapping("key.skydock.zoom_in", InputConstants.KEY_EQUALS, "key.categories.skydock");
    static final KeyMapping ZOOM_OUT = new KeyMapping("key.skydock.zoom_out", InputConstants.KEY_MINUS, "key.categories.skydock");
    private static final double KEY_NOTCHES_PER_TICK = .35;
    private static double zoom = 1;
    private static double wanted, shown;
    private static boolean recovering;
    private static UUID framed;
    private static long lastFrame;
    private ShipCamera() {}

    public static void init() {
        KeyMappingRegistry.register(ZOOM_IN);
        KeyMappingRegistry.register(ZOOM_OUT);
        ClientRawInputEvent.MOUSE_SCROLLED.register((mc, amountX, amountY) -> {
            if (amountY == 0 || !active()) return EventResult.pass();
            zoom = ShipCameraFraming.zoom(zoom, amountY);
            return EventResult.interruptFalse();
        });
    }
    /** Drops the framing of the last ship, for leaving a world; the zoom is the player's preference and stays. */
    static void reset() { framed = null; wanted = shown = 0; recovering = false; lastFrame = 0; }
    static void tick(Minecraft mc) {
        boolean in = ZOOM_IN.isDown(), out = ZOOM_OUT.isDown();
        if (mc.screen == null && in != out && active()) zoom = ShipCameraFraming.zoom(zoom, in ? KEY_NOTCHES_PER_TICK : -KEY_NOTCHES_PER_TICK);
    }
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return !mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player && framedShip() != null;
    }
    private static Ship framedShip() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()) return null;
        Ship ship = SkydockClient.piloted();
        if (ship == null) ship = SkydockClient.seatedShip();
        return ship != null && ship.dimension.equals(mc.level.dimension()) ? ship : null;
    }

    /**
     * Replaces the vanilla detached position, or returns null to keep it. {@code look} is the camera's own
     * forward vector, already mirrored for the front view.
     */
    public static Vec3 position(BlockGetter level, Entity entity, boolean detached, Vec3 eye, Vec3 look, float partialTick) {
        long now = System.nanoTime();
        double seconds = lastFrame == 0 ? 0 : Math.min(.25, (now - lastFrame) / 1e9);
        lastFrame = now;
        Ship ship = detached && entity == Minecraft.getInstance().player ? framedShip() : null;
        if (ship == null) { framed = null; return null; }
        Minecraft mc = Minecraft.getInstance();
        AABB hull = ship.hullBounds();
        double fullFit = ShipCameraFraming.fitDistance(hull.getXsize(), hull.getYsize(), hull.getZsize(), mc.options.fov().get());
        double max = ShipCameraFraming.maxDistance(fullFit, mc.options.getEffectiveRenderDistance()), fit = Math.min(max, fullFit);
        zoom = ShipCameraFraming.clampZoom(zoom, fit, max);
        if (!ship.id.equals(framed)) {
            // Pull back out of the vanilla view instead of cutting to the hull.
            framed = ship.id; wanted = shown = ShipCameraFraming.PLAYER_DISTANCE; recovering = false;
        }
        wanted = ShipCameraFraming.ease(wanted, fit * zoom, seconds, 10);
        ShipPose pose = ship.previousPose.interpolate(ship.pose, partialTick);
        Vec3 hullFocus = pose.toWorld(new Vec3((hull.minX + hull.maxX) / 2, hull.minY + hull.getYsize() * .6, (hull.minZ + hull.maxZ) / 2));
        Vec3 focus = eye.lerp(hullFocus, ShipCameraFraming.focusBlend(wanted, fit));
        double allowed = clearance(level, entity, focus, look, wanted);
        // Terrain pulls the camera in at once; once it clears, ease back out rather than popping.
        if (allowed < shown) { shown = allowed; recovering = allowed < wanted - 1e-3; }
        else if (recovering) {
            shown = ShipCameraFraming.ease(shown, allowed, seconds, 5);
            if (allowed - shown < .05) { shown = allowed; recovering = false; }
        } else shown = allowed;
        return focus.subtract(look.scale(shown));
    }
    /** Vanilla's eight offset rays. The hull is not in the client level, so only terrain can block the view. */
    private static double clearance(BlockGetter level, Entity entity, Vec3 focus, Vec3 look, double distance) {
        for (int i = 0; i < 8; i++) {
            Vec3 start = focus.add(((i & 1) * 2 - 1) * .1, ((i >> 1 & 1) * 2 - 1) * .1, ((i >> 2 & 1) * 2 - 1) * .1);
            HitResult hit = level.clip(new ClipContext(start, start.subtract(look.scale(distance)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.MISS) distance = Math.min(distance, hit.getLocation().distanceTo(focus));
        }
        return Math.max(0, distance);
    }
}
