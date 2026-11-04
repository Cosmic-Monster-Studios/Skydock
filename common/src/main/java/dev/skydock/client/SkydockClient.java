package dev.skydock.client;

import dev.architectury.event.events.client.*;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.architectury.registry.menu.MenuRegistry;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.network.DockNetwork;
import dev.skydock.network.ShipNetwork;
import dev.skydock.network.ShipState;
import dev.skydock.ship.*;
import net.minecraft.client.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class SkydockClient {
    public static final Map<UUID, Ship> SHIPS = new LinkedHashMap<>();
    private static final Map<UUID, Ship> pending = new HashMap<>();
    /** Hulls being assembled from a {@code blocks_delta}, built on a copy of the ship at the delta's From revision. */
    private static final Map<UUID, Ship> pendingDeltas = new HashMap<>();
    /** Ships whose copy missed a revision; a full resync is requested until their whole hull arrives. */
    private static final Set<UUID> stale = new HashSet<>();
    private static final Map<UUID, ShipMotion> motions = new HashMap<>();
    private static final KeyMapping RELEASE = new KeyMapping("key.skydock.release", 82, "key.categories.skydock");
    private static final KeyMapping CRUISE = new KeyMapping("key.skydock.cruise", 67, "key.categories.skydock");
    private static final KeyMapping ALTITUDE_HOLD = new KeyMapping("key.skydock.altitude_hold", 72, "key.categories.skydock");
    private static final KeyMapping HEADING_LOCK = new KeyMapping("key.skydock.heading_lock", 74, "key.categories.skydock");
    private static boolean inWorld;
    /** Entity collision asks for the level's ships on every move; rebuilt only when the ship map changes. */
    private static List<Ship> levelShips;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelShipsDimension;
    private static long tick;
    private static float carriedYaw;
    public static void init() {
        dev.architectury.registry.ReloadListenerRegistry.register(net.minecraft.server.packs.PackType.CLIENT_RESOURCES,
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> Minecraft.getInstance().execute(ShipRenderer::clear), dev.skydock.Skydock.id("hull_meshes"));
        ShipNetwork.clientReceiver = SkydockClient::receive;
        ShipNetwork.poseReceiver = SkydockClient::receivePose;
        DockNetwork.clientReceiver = DockView::receive;
        dev.skydock.cargo.CargoNetwork.clientReceiver = ClientCargo::receive;
        ShipManager.ClientShipAccess.provider = level -> {
            if (levelShips == null || levelShipsDimension != level.dimension()) {
                levelShips = SHIPS.values().stream().filter(s -> s.dimension.equals(level.dimension())).toList();
                levelShipsDimension = level.dimension();
            }
            return levelShips;
        };
        SkydockBlocks.DOCK_MENU.listen(type -> MenuRegistry.registerScreenFactory(type, DockScreen::new));
        SkydockBlocks.DEVICE_MENU.listen(type -> MenuRegistry.registerScreenFactory(type, DeviceScreen::new));
        SkydockBlocks.ENGINE_MENU.listen(type -> MenuRegistry.registerScreenFactory(type, EngineScreen::new));
        SkydockBlocks.TERMINAL_MENU.listen(type -> MenuRegistry.registerScreenFactory(type, TerminalScreen::new));
        dev.architectury.registry.client.level.entity.EntityRendererRegistry.register(SkydockBlocks.POD, PodEntityRenderer::new);
        SkydockBlocks.CRATE_ENTITY.listen(type -> dev.architectury.registry.client.rendering.BlockEntityRendererRegistry.register(type, CrateRenderer::new));
        KeyMappingRegistry.register(RELEASE);
        KeyMappingRegistry.register(CRUISE);
        KeyMappingRegistry.register(ALTITUDE_HOLD);
        KeyMappingRegistry.register(HEADING_LOCK);
        ShipCamera.init();
        ClientTickEvent.CLIENT_PRE.register(SkydockClient::tick);
        ClientTickEvent.CLIENT_POST.register(SkydockClient::moveShips);
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
            Ship piloted = piloted(), seated = seatedShip(), ship = piloted != null ? piloted : seated != null ? seated : aboard();
            if (ship == null) return;
            List<String> status = new ArrayList<>(List.of(I18n.get("hud.skydock.title"), I18n.get("hud.skydock.speed", String.format(Locale.ROOT, "%.1f", ship.velocity.length() * 20)),
                    I18n.get("hud.skydock.altitude", String.format(Locale.ROOT, "%.0f", ship.pose.y()))));
            double heading = Math.floorMod(Math.round(ship.pose.yaw()), 360);
            status.add(I18n.get("hud.skydock.heading", String.format(Locale.ROOT, "%.0f", heading), Navigation.compass(heading)));
            if (!Double.isNaN(ship.holdAltitude)) status.add(I18n.get("hud.skydock.alt_hold", String.format(Locale.ROOT, "%.0f", ship.holdAltitude)));
            if (!Double.isNaN(ship.headingLock)) status.add(I18n.get("hud.skydock.heading_lock", String.format(Locale.ROOT, "%.0f", ship.headingLock)));
            if (ship == piloted || ship == seated) {
                // The client knows the ship's reserved berth from its header; show the line only to those flying or seated aboard.
                ShipPose home = ship.homePose();
                Navigation.Berth berth = Navigation.berth(ship.pose, new Vec3(home.x(), home.y(), home.z()), home.yaw());
                status.add(I18n.get("hud.skydock.berth", (Object[]) Navigation.berthText(berth)));
                if (Navigation.redockReady(berth, ship.velocity.length())) status.add(I18n.get("hud.skydock.redock_ready"));
                Optional<BlockPos> pad = Navigation.nearest(ship.pose, ClientCargo.destinations().stream().filter(d -> d.dimension().equals(mc.level.dimension())).map(dev.skydock.cargo.CargoNetwork.Destination::pad).toList());
                if (pad.isPresent()) {
                    Navigation.Berth cargo = Navigation.berth(ship.pose, Vec3.atCenterOf(pad.get()), 0);
                    String[] text = Navigation.berthText(cargo);
                    status.add(I18n.get("hud.skydock.cargo", text[0], text[1], Navigation.compass(cargo.bearing()), String.valueOf(Math.abs(Math.round(cargo.dy()))), I18n.get(cargo.dy() < 0 ? "hud.skydock.cargo_below" : "hud.skydock.cargo_above")));
                }
            }
            if (ship.cruise) status.add(I18n.get("hud.skydock.cruise", String.format(Locale.ROOT, "%.1f", ship.cruiseSpeed * 20)));
            if (ship.blocked) status.add(I18n.get("hud.skydock.obstructed"));
            String view = ShipCamera.active() ? I18n.get("hud.skydock.zoom", keyLabel(ShipCamera.ZOOM_IN), keyLabel(ShipCamera.ZOOM_OUT))
                    : I18n.get("hud.skydock.ship_view", keyLabel(mc.options.keyTogglePerspective));
            List<String> controls = piloted != null
                    ? List.of(I18n.get("hud.skydock.ctl_speed", thrustKeys()), I18n.get("hud.skydock.ctl_turn", turnKeys()), I18n.get("hud.skydock.ctl_altitude", altitudeKeys()), I18n.get("hud.skydock.ctl_cruise", cruiseKey()), I18n.get("hud.skydock.ctl_release", releaseKey()), view)
                    : seated != null ? List.of(I18n.get("hud.skydock.ctl_stand", releaseKey()), I18n.get("hud.skydock.ctl_cruise", cruiseKey()), view)
                    : List.of(I18n.get("hud.skydock.ctl_walk"), I18n.get("hud.skydock.ctl_cruise", cruiseKey()));
            // Wrap between hints rather than running off narrow windows.
            int maxWidth = graphics.guiWidth() - 28;
            List<String> statusLines = wrap(mc.font, status, "  |  ", maxWidth), controlLines = wrap(mc.font, controls, "   ", maxWidth);
            int width = 0, lines = statusLines.size() + controlLines.size();
            for (String line : statusLines) width = Math.max(width, mc.font.width(line));
            for (String line : controlLines) width = Math.max(width, mc.font.width(line));
            graphics.fill(8, 8, width + 20, 14 + lines * 13, 0xCC101E28);
            int y = 14;
            for (String line : statusLines) { graphics.drawString(mc.font, line, 14, y, 0xFF9AE4D2); y += 13; }
            for (String line : controlLines) { graphics.drawString(mc.font, line, 14, y, 0xFFE3E6E8); y += 13; }
        });
    }
    private static List<String> wrap(net.minecraft.client.gui.Font font, List<String> parts, String separator, int width) {
        List<String> lines = new ArrayList<>(); String line = "";
        for (String part : parts) {
            String joined = line.isEmpty() ? part : line + separator + part;
            if (!line.isEmpty() && font.width(joined) > width) { lines.add(line); line = part; }
            else line = joined;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
    /** Resolved name of a binding, so help text follows the player's own controls instead of the defaults. */
    public static String keyLabel(KeyMapping mapping) { return mapping.getTranslatedKeyMessage().getString(); }
    public static String thrustKeys() { var options = Minecraft.getInstance().options; return keyLabel(options.keyUp) + "/" + keyLabel(options.keyDown); }
    public static String turnKeys() { var options = Minecraft.getInstance().options; return keyLabel(options.keyLeft) + "/" + keyLabel(options.keyRight); }
    public static String altitudeKeys() { var options = Minecraft.getInstance().options; return keyLabel(options.keyJump) + "/" + keyLabel(options.keyShift); }
    public static String cruiseKey() { return keyLabel(CRUISE); }
    public static String releaseKey() { return keyLabel(RELEASE); }
    public static Ship piloted() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return SHIPS.values().stream().filter(s -> player.getUUID().equals(s.pilot)).findFirst().orElse(null);
    }
    public static boolean seated() { return seatedShip() != null; }
    public static Ship seatedShip() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return SHIPS.values().stream().filter(s -> s.seated.containsKey(player.getUUID())).findFirst().orElse(null);
    }
    public static float carriedYaw() { return carriedYaw; }
    public static ShipMovementFrame.Frame movementFrame(LocalPlayer player) {
        Ship ship = ShipManager.attachedShip(player);
        if (ship == null) {
            ship = SHIPS.values().stream().filter(candidate -> candidate.dimension.equals(player.level().dimension())
                    && ShipCollision.supported(candidate, candidate.pose, player.getBoundingBox())).findFirst().orElse(null);
            if (ship != null) ShipManager.attach(player, ship);
        }
        ShipMotion motion = ship == null ? null : motions.get(ship.id);
        return motion == null ? ShipMovementFrame.Frame.none(0) : ShipManager.movementFrame(player, motion.time());
    }
    public static boolean useViewedShip() {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        ShipInteractions.Hit hit = ShipInteractions.pick(player);
        if (hit == null) return false;
        Ship ship = hit.ship(); ShipMotion motion = motions.get(ship.id);
        if (motion != null) ShipNetwork.use(ship.id, motion.time(), ship.worldToBlock(player.getEyePosition()),
                ShipPose.rotate(player.getLookAngle().scale(player.blockInteractionRange()), -ship.pose.yaw()));
        return true;
    }
    private static Ship aboard() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        return SHIPS.values().stream().filter(s -> s.dimension.equals(player.level().dimension()) && ShipManager.permitted(player, s)
                && s.pose.toWorld(s.hullBounds()).inflate(1).contains(player.position())).findFirst().orElse(null);
    }
    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (inWorld) { SHIPS.clear(); levelShips = null; pending.clear(); pendingDeltas.clear(); stale.clear(); motions.clear(); ShipRenderer.clear(); }
            carriedYaw = 0;
            inWorld = false; return;
        }
        if (!inWorld) { ShipNetwork.input(3, 0, 0, 0); inWorld = true; }
        tick++;
        // The server accepts one hello per 40 ticks, so a resync request that landed too soon is repeated.
        if (!stale.isEmpty() && tick % 60 == 0) ShipNetwork.input(3, 0, 0, 0);
        if (mc.screen == null && RELEASE.consumeClick()) ShipNetwork.input(2, 0, 0, 0);
        if (mc.screen == null && CRUISE.consumeClick()) ShipNetwork.input(4, 0, 0, 0);
        boolean piloting = piloted() != null;
        if (mc.screen == null && ALTITUDE_HOLD.consumeClick() && piloting) ShipNetwork.input(5, 0, 0, 0);
        if (mc.screen == null && HEADING_LOCK.consumeClick() && piloting) ShipNetwork.input(6, 0, 0, 0);
        ShipCamera.tick(mc);
        Ship pilot = piloted();
        if (pilot != null && tick % 2 == 0) {
            float thrust = mc.screen == null ? axis(mc.options.keyUp.isDown(), mc.options.keyDown.isDown()) : 0;
            float turn = mc.screen == null ? axis(mc.options.keyRight.isDown(), mc.options.keyLeft.isDown()) : 0;
            float climb = mc.screen == null ? axis(mc.options.keyJump.isDown(), mc.options.keyShift.isDown()) : 0;
            ShipNetwork.input(0, thrust, turn, climb);
        }
        if (tick % 20 == 0) ShipRenderer.findDocks(mc);
    }
    private static void moveShips(Minecraft mc) {
        carriedYaw = 0;
        if (mc.level == null || mc.player == null) return;
        float yawBeforeCarry = mc.player.getYRot();
        // Vanilla has now recorded entity old positions. Deck and rider share the same render interval.
        for (Ship ship : SHIPS.values()) {
            ship.previousPose = ship.pose;
            ShipMotion motion = motions.get(ship.id);
            if (motion != null) {
                ship.pose = motion.advance();
                if (ship.dimension.equals(mc.level.dimension())) ShipManager.carry(mc.level, ship, ship.previousPose);
            }
        }
        carriedYaw = net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yawBeforeCarry);
    }
    private static float axis(boolean positive, boolean negative) { return (positive ? 1 : 0) - (negative ? 1 : 0); }
    private static void receive(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance(); if (mc.level == null) return;
        if (data.getString("Kind").equals("deck_correction")) {
            Ship ship = SHIPS.get(data.getUUID("Id"));
            if (ship != null && mc.player != null) {
                Vec3 local = new Vec3(data.getDouble("LocalX"), data.getDouble("LocalY"), data.getDouble("LocalZ"));
                mc.player.setPos(ship.blockToWorld(local));
                ShipManager.attach(mc.player, ship);
                mc.player.setOldPosAndRot();
            }
            return;
        }
        if (data.getString("Kind").equals("manifest")) {
            Set<UUID> active = new HashSet<>(); for (Tag e : data.getList("Ships", Tag.TAG_COMPOUND)) active.add(((CompoundTag) e).getUUID("Id"));
            SHIPS.keySet().retainAll(active); levelShips = null; pending.keySet().retainAll(active); pendingDeltas.keySet().retainAll(active); stale.retainAll(active); motions.keySet().retainAll(active); ShipRenderer.retain(active); return;
        }
        UUID id = data.getUUID("Id");
        if (data.getString("Kind").equals("blocks")) {
            if (data.getInt("Part") == 0) pending.put(id, Ship.load(data, mc.level.registryAccess()));
            Ship ship = pending.get(id); if (ship == null) return;
            applyCells(ship, data, false);
            if (data.getInt("Part") + 1 == data.getInt("Parts")) { pending.remove(id); stale.remove(id); install(mc, id, ship, data); }
            return;
        }
        if (data.getString("Kind").equals("blocks_delta")) {
            if (data.getInt("Part") == 0) {
                Ship base = SHIPS.get(id);
                if (base == null || base.revision != data.getInt("From")) { pendingDeltas.remove(id); requestResync(id); return; }
                Ship ship = Ship.load(data, mc.level.registryAccess());
                ship.blocks.putAll(base.blocks); ship.blockEntities.putAll(base.blockEntities);
                pendingDeltas.put(id, ship);
            }
            Ship ship = pendingDeltas.get(id); if (ship == null) return;
            applyCells(ship, data, true);
            if (data.getInt("Part") + 1 == data.getInt("Parts")) {
                pendingDeltas.remove(id);
                Ship base = SHIPS.get(id);
                if (base == null || base.revision != data.getInt("From")) { requestResync(id); return; }
                install(mc, id, ship, data);
            }
        }
    }
    private static void requestResync(UUID id) { if (stale.add(id)) ShipNetwork.input(3, 0, 0, 0); }
    /** A delta replaces only the cells it carries, so a cell's old block-entity data goes unless the delta brings new data. */
    private static void applyCells(Ship ship, CompoundTag data, boolean delta) {
        for (Tag e : data.getList("CellsData", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) e; BlockPos pos = BlockPos.of(entry.getLong("Pos"));
            ship.blocks.put(pos, Block.stateById(entry.getInt("State")));
            if (delta) ship.blockEntities.remove(pos);
            if (entry.contains("Data")) ship.blockEntities.put(pos, entry.getCompound("Data").copy());
        }
    }
    /** Puts a fully received hull in place; replacing the ship object is what makes {@code ShipRenderer} rebuild its mesh. */
    private static void install(Minecraft mc, UUID id, Ship ship, CompoundTag data) {
        ShipMotion motion = motions.computeIfAbsent(id, key -> new ShipMotion());
        motion.accept(data.getLong("Tick"), ship.pose, ship.velocity, ship.yawVelocity);
        Ship old = SHIPS.put(id, ship); levelShips = null;
        if (old != null) { ship.pose = old.pose; ship.previousPose = old.previousPose; ship.seated.putAll(old.seated); }
        ShipRenderer.invalidate(id);
        if (data.getBoolean("Boarding") && mc.player != null) {
            mc.player.setPos(ship.blockToWorld(new net.minecraft.world.phys.Vec3(data.getDouble("BoardX"), data.getDouble("BoardY"), data.getDouble("BoardZ"))));
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); mc.player.setOnGround(true); mc.player.fallDistance = 0;
            ShipManager.attach(mc.player, ship);
            mc.player.setOldPosAndRot();
            ShipNetwork.boardReady(id);
        }
    }
    private static void receivePose(ShipState state) {
        if (Minecraft.getInstance().level == null) return;
        Ship ship = SHIPS.get(state.id()); if (ship == null) return;
        state.apply(ship);
        motions.computeIfAbsent(state.id(), key -> new ShipMotion()).accept(state.tick(),
                new ShipPose(state.x(), state.y(), state.z(), state.yaw()), ship.velocity, ship.yawVelocity);
    }
}
