package dev.skydock.ship;

import dev.skydock.Skydock;
import dev.skydock.block.*;
import dev.skydock.data.*;
import dev.skydock.network.ShipNetwork;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Mth;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

public final class ShipManager {
    public static final ResourceKey<Level> SHIPYARD = ResourceKey.create(Registries.DIMENSION, Skydock.id("shipyard"));
    private static final TicketType<UUID> TICKET = TicketType.create("skydock_ship", UUID::compareTo, 300);
    private static final Map<MinecraftServer, ShipSavedData> SERVERS = new IdentityHashMap<>();
    private static final Map<UUID, Set<ChunkPos>> WORLD_TICKETS = new HashMap<>();
    public record Boarding(UUID ship, Vec3 localFeet) {}
    private static final Map<UUID, Boarding> BOARDING = new HashMap<>();
    private static final Set<UUID> JOINED = new HashSet<>();
    /** Active ships per level. Entity collision asks for these on every move, so the list is kept until the fleet changes. */
    private static final Map<Level, List<Ship>> ACTIVE = new IdentityHashMap<>();
    private static final Map<GlobalPos, Long> LAST_TRANSFER = new HashMap<>();
    public static Collection<Ship> ships(Level level) {
        if (level.isClientSide) return ClientShipAccess.ships(level);
        ShipSavedData data = SERVERS.get(level.getServer());
        if (data == null) return List.of();
        return ACTIVE.computeIfAbsent(level, key -> data.ships.values().stream().filter(s -> s.phase.equals("active") && s.dimension.equals(key.dimension())).toList());
    }
    /** Call whenever a ship is added, removed, or changes phase. */
    public static void fleetChanged() { ACTIVE.clear(); }
    public static Ship get(MinecraftServer server, UUID id) { return ShipSavedData.get(server).ships.get(id); }
    public static boolean berthReserved(Level level, BlockPos dock) {
        if (level.isClientSide || level.getServer() == null) return false;
        return ShipSavedData.get(level.getServer()).reserves(level.dimension(), dock, null);
    }
    /** Whether an unfinished transfer targets {@code dock} or overlaps its berth, so no new ship may be built or launched there. */
    public static boolean transferPending(Level level, DockBlockEntity dock) {
        if (level.isClientSide || level.getServer() == null) return false;
        return ShipSavedData.get(level.getServer()).transferHolds(level.dimension(), dock.getBlockPos(), dock.frame().envelope());
    }
    public static void start(MinecraftServer server) {
        ShipSavedData data = ShipSavedData.get(server); SERVERS.put(server, data); fleetChanged();
        for (Ship ship : List.copyOf(data.ships.values())) {
            if (ship.phase.equals("active")) continue;
            if (ship.transferHeld) { logHeld(ship); continue; }
            try { retry(server, ship); }
            catch (Exception e) { Skydock.LOGGER.error("Ship {} transfer remains in its recovery journal", ship.id, e); failed(server, ship); }
        }
    }
    private static void logHeld(Ship ship) {
        String dock = ship.transferDock == null ? "(unknown)" : ship.transferDock.toShortString(), dimension = ship.transferDimension == null ? "(unknown)" : ship.transferDimension.location().toString();
        if (ship.transferQuarantined) Skydock.LOGGER.error("Ship {} was quarantined in the middle of its {} phase and has loaded again; it is not replayed automatically. Its berth at {} in {} stays guarded and reserved until an operator checks it and runs /skydock resume-transfer {}", ship.id, ship.phase, dock, dimension, ship.id);
        else Skydock.LOGGER.error("Ship {} {} phase was held after {} failed retries in a row and is not replayed automatically. Its berth at {} in {} stays guarded and reserved until an operator fixes the cause logged with those failures and runs /skydock resume-transfer {}", ship.id, ship.phase, GIVE_UP, dock, dimension, ship.id);
    }
    /** How often, in server ticks, a paused transfer is tried again. */
    static final int RESUME_TICKS = 200;
    /** Failed retries in a row after which a transfer is held for an operator, about five minutes at {@link #RESUME_TICKS}, so a failure that repeats cannot loop forever. */
    static final int GIVE_UP = 30;
    /** Counts a failed retry of {@code ship}'s transfer; the {@link #GIVE_UP}th in a row holds it, and starts the count again for its release. Returns whether this one held it. */
    static boolean failedRetry(Ship ship) {
        if (++ship.transferFailures < GIVE_UP) return false;
        ship.transferHeld = true; ship.transferFailures = 0; return true;
    }
    /** Counts a failed retry, and once that holds the transfer, saves the hold and tells the log and every online operator. */
    private static void failed(MinecraftServer server, Ship ship) {
        if (!failedRetry(ship)) return;
        ShipSavedData.get(server).setDirty(); logHeld(ship);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.hasPermissions(2)) tell(player, "message.skydock.transfer_held", ship.id.toString(), GIVE_UP);
    }
    /**
     * Retries every paused transfer: the journal is written first (a pause may have come before it reached disk), then the phase is
     * replayed. Each failure is logged and tried again {@link #RESUME_TICKS} later; a finished one updates its dock's status.
     */
    private static void resume(MinecraftServer server, ShipSavedData data) {
        boolean finished = false;
        for (Ship ship : List.copyOf(data.ships.values())) {
            if (ship.phase.equals("active") || ship.transferHeld) continue;
            try { retry(server, ship); }
            catch (Exception e) { Skydock.LOGGER.error("Ship {} transfer is still paused; retrying in {} ticks", ship.id, RESUME_TICKS, e); failed(server, ship); continue; }
            finished = true; Skydock.LOGGER.info("Ship {} paused transfer resumed and finished", ship.id);
        }
        if (finished) ShipNetwork.resync(server);
    }
    /**
     * Replays an unfinished transfer: re-reads what it moves from the live world, writes the journal (a pause may have come before it
     * reached disk), then completes the phase and updates the dock's status. Throws while the transfer is still paused.
     */
    private static void retry(MinecraftServer server, Ship ship) {
        boolean launching = ship.phase.equals("launching"); ResourceKey<Level> where = ship.transferDimension; BlockPos dock = ship.transferDock;
        ShipTransfer.resnapshot(server, ship); ShipTransfer.flush(server); ShipTransfer.complete(server, ship);
        ServerLevel level = where == null ? null : server.getLevel(where);
        if (level != null && dock != null && level.getBlockEntity(dock) instanceof DockBlockEntity entity) {
            if (launching) entity.clearHullMarker("status.skydock.launched"); else entity.markManualHull(ship.blocks.size());
        }
    }
    /** Releases a held transfer (see {@link Ship#transferHeld}) and replays it now: true when it finished, false when it paused again and will retry on its own, up to {@link #GIVE_UP} times. */
    public static boolean releaseHeld(MinecraftServer server, Ship ship) {
        ship.transferHeld = false; ship.transferFailures = 0; ShipSavedData.get(server).setDirty();
        try { retry(server, ship); }
        catch (Exception e) { Skydock.LOGGER.error("Ship {} released transfer is paused; retrying in {} ticks", ship.id, RESUME_TICKS, e); closeMenus(server, ship); failed(server, ship); return false; }
        ShipNetwork.resync(server); return true;
    }
    /** Whether {@code pos} is in the berth of a transfer that has not finished, quarantined records included; like an assembly's cells, no one may break, place or use it until the transfer resumes. */
    public static boolean transferProtects(ServerLevel level, BlockPos pos) {
        ShipSavedData data = SERVERS.get(level.getServer());
        return data != null && data.transferProtects(level.dimension(), pos);
    }
    /**
     * Closes every open menu backed by a container in either copy of a paused transfer's hull. New menus there are refused while it is
     * paused, but one opened before would stay valid, and whatever it took would come back when the retry pastes the hull.
     */
    static void closeMenus(MinecraftServer server, Ship ship) {
        Set<Container> held = Collections.newSetFromMap(new IdentityHashMap<>());
        ServerLevel yard = server.getLevel(SHIPYARD), berth = ship.transferDimension == null ? null : server.getLevel(ship.transferDimension);
        for (BlockPos p : ship.blocks.keySet()) {
            if (yard != null) heldContainer(yard, ship.yard.offset(p), held);
            if (berth != null && ship.transferOrigin != null) heldContainer(berth, DockFrame.toWorld(ship.transferOrigin, DockFrame.rotation(ship.transferFacing), p), held);
        }
        if (held.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu != player.inventoryMenu && backedBy(player.containerMenu.slots.stream().map(slot -> slot.container).toList(), held)) player.closeContainer();
    }
    private static void heldContainer(ServerLevel level, BlockPos at, Set<Container> held) {
        if (level.isLoaded(at) && level.getBlockEntity(at) instanceof Container container) held.add(container);
    }
    /** Whether any of a menu's slot containers is one of {@code held}, or a double chest with a half among them. */
    static boolean backedBy(Collection<Container> slots, Set<Container> held) {
        for (Container container : slots)
            if (held.contains(container) || container instanceof CompoundContainer both && held.stream().anyMatch(both::contains)) return true;
        return false;
    }
    public static void stop(MinecraftServer server) { SERVERS.remove(server); WORLD_TICKETS.clear(); BOARDING.clear(); JOINED.clear(); LAST_TRANSFER.clear(); fleetChanged(); ShipDamage.clear(); ShipInteractions.clear(); }
    /** Launch and redock each flush the journal and chunks to disk, so one berth cannot be cycled every tick. */
    private static boolean settling(ServerPlayer player, BlockPos pos) {
        Long last = LAST_TRANSFER.get(GlobalPos.of(player.level().dimension(), pos));
        if (last != null && player.level().getGameTime() - last < 100) { tell(player, "message.skydock.berth_settling"); return true; }
        return false;
    }
    private static void transferred(ServerPlayer player, BlockPos pos) { LAST_TRANSFER.put(GlobalPos.of(player.level().dimension(), pos.immutable()), player.level().getGameTime()); }
    public static Boarding boarding(Player player) { return BOARDING.get(player.getUUID()); }
    public static void prepareBoarding(ServerPlayer player) {
        if (!JOINED.add(player.getUUID()) || !player.isAlive() || player.isSpectator()) return;
        // A newly joined client may not have received the hull before vanilla starts gravity.
        for (Ship ship : ships(player.level())) {
            AABB feet = player.getBoundingBox().inflate(.05, 1, .05);
            for (var shape : ShipCollision.shapes(ship, ship.pose, feet)) {
                AABB surface = shape.bounds();
                if (Math.abs(surface.maxY - player.getY()) > .6 || surface.maxY > player.getY() + .3) continue;
                Vec3 at = new Vec3(player.getX(), surface.maxY, player.getZ());
                BOARDING.put(player.getUUID(), new Boarding(ship.id, ship.worldToBlock(at)));
                wake(player.getServer(), ship);
                return;
            }
        }
    }
    public static void completeBoarding(ServerPlayer player, UUID id) {
        Boarding boarding = BOARDING.get(player.getUUID());
        if (boarding == null || !boarding.ship.equals(id)) return;
        Ship ship = get(player.getServer(), id);
        BOARDING.remove(player.getUUID());
        if (ship == null || !ship.dimension.equals(player.level().dimension())) return;
        Vec3 feet = ship.blockToWorld(boarding.localFeet);
        player.connection.teleport(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true); player.fallDistance = 0;
        attachment(player).attach(ship.id);
    }

    private static ShipAttachment attachment(Entity entity) {
        return ((ShipAttachmentAccess) entity).skydock$attachment();
    }
    private static boolean attachable(Entity entity) {
        return entity.isAlive() && !entity.isSpectator() && !entity.noPhysics && !entity.isPassenger() && !(entity instanceof dev.skydock.cargo.PodEntity)
                && (!(entity instanceof Player player) || !player.getAbilities().flying);
    }
    private static Ship findShip(Level level, UUID id) {
        if (id == null) return null;
        return ships(level).stream().filter(ship -> ship.id.equals(id)).findFirst().orElse(null);
    }
    public static void attach(Entity entity, Ship ship) { attachment(entity).attach(ship.id); }
    public static Ship attachedShip(Entity entity) {
        ShipAttachment attachment = attachment(entity);
        Ship ship = findShip(entity.level(), attachment.ship());
        if (ship == null || !attachable(entity) || !entity.level().isClientSide && ShipAttachment.strayed(ship.hullWorldBounds(), entity.position())) {
            attachment.detach(entity, false);
            return null;
        }
        return ship;
    }
    /** Called immediately before LocalPlayer emits its vanilla movement packet. */
    public static ShipMovementFrame.Frame movementFrame(Entity entity, double shipTime) {
        ShipAttachment attachment = attachment(entity);
        Ship ship = attachedShip(entity);
        if (ship == null) {
            ship = ships(entity.level()).stream()
                    .filter(candidate -> ShipCollision.supported(candidate, candidate.pose, entity.getBoundingBox()))
                    .findFirst().orElse(null);
            if (ship == null) return ShipMovementFrame.Frame.none(0);
            attachment.attach(ship.id);
        }
        Vec3 localFeet = ship.worldToBlock(entity.position());
        boolean departing = !ShipCollision.aboveHull(ship, ship.pose, entity.getBoundingBox());
        ShipMovementFrame.Frame frame = new ShipMovementFrame.Frame(0, ship.id, shipTime, localFeet, departing);
        if (departing) attachment.detach(entity, true);
        return frame;
    }
    public static void receiveMovementFrame(ServerPlayer player, ShipMovementFrame.Frame frame) {
        attachment(player).receive(frame);
    }
    /**
     * Re-expresses a verified client-frame target in the server's current ship pose. {@code teleportPending} is whether a teleport
     * was awaiting the client's acceptance before vanilla handled this packet; vanilla then ignores the move, so it cannot attach the
     * player to a ship (fresh or switching) either.
     */
    public static ServerboundMovePlayerPacket rebaseMovement(ServerPlayer player, ServerboundMovePlayerPacket packet, boolean teleportPending) {
        ShipAttachment attachment = attachment(player);
        if (!packet.hasPosition()) return packet;
        ShipMovementFrame.Frame frame = attachment.consume();
        if (frame == null || frame.ship() == null || !attachable(player)) return packet;
        Ship ship = findShip(player.level(), frame.ship());
        if (ship == null) return packet;
        Vec3 packetPosition = new Vec3(packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()));
        UUID attached = attachment.ship();
        Vec3 rebased;
        // A frame naming another ship means the client left the attached one without the server seeing it go.
        boolean switching = attached != null && !attached.equals(frame.ship());
        if (attached == null || switching) {
            if (teleportPending) return packet;
            rebased = ShipMovementFrame.rebase(frame, frame.ship(), attachment.lastAcceptedSequence(),
                    ship.history, ship.pose, ship.center(), packetPosition);
            if (rebased == null) return packet;
            ShipPose historical = ship.history.sample(frame.shipTime());
            AABB historicalBox = player.getBoundingBox().move(packetPosition.subtract(player.position()));
            AABB currentBox = player.getBoundingBox().move(rebased.subtract(player.position()));
            if (!ShipCollision.supported(ship, historical, historicalBox)
                    || !ShipCollision.supported(ship, ship.pose, currentBox)) return packet;
            if (switching) attachment.detach(player, false);
            attachment.attach(ship.id);
        } else {
            rebased = ShipMovementFrame.rebase(frame, attached, attachment.lastAcceptedSequence(),
                    ship.history, ship.pose, ship.center(), packetPosition);
            if (rebased == null) return packet;
        }
        attachment.accepted(frame.sequence());
        attachment.detachAfterMovement(frame.departing());
        attachment.acceptedMovementTarget(rebased);
        if (packet instanceof ServerboundMovePlayerPacket.PosRot)
            return new ServerboundMovePlayerPacket.PosRot(rebased.x, rebased.y, rebased.z,
                    packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()), packet.isOnGround());
        return new ServerboundMovePlayerPacket.Pos(rebased.x, rebased.y, rebased.z, packet.isOnGround());
    }
    /**
     * {@code teleportPending} is whether a teleport was awaiting the client's acceptance before vanilla handled the packet (not after:
     * vanilla's own rubber-band teleport for a rejected move is set during it). With one pending, vanilla ignored the move, and a deck
     * correction would be built from the teleport's destination; without, a player left short of the accepted target is put back on deck.
     */
    public static void finishMovement(ServerPlayer player, boolean teleportPending) {
        ShipAttachment attachment = attachment(player);
        Vec3 target = attachment.consumeAcceptedMovementTarget();
        boolean departing = attachment.consumeDetachAfterMovement();
        if (correctsDeck(target, teleportPending, player.position())) {
            Ship ship = findShip(player.level(), attachment.ship());
            if (ship != null) ShipNetwork.correctDeck(player, ship, ship.worldToBlock(player.position()));
            return;
        }
        if (departing) attachment.detach(player, true);
    }
    /** Whether a move left the player away from its accepted deck target with no teleport pending before it, so the deck must be restored. */
    static boolean correctsDeck(Vec3 target, boolean teleportPending, Vec3 position) { return target != null && !teleportPending && position.distanceToSqr(target) > 1e-6; }
    public static void tell(Player player, String key, Object... args) { player.displayClientMessage(Component.translatable(key, args), false); }
    public static void tell(Player player, Component text) { player.displayClientMessage(text, false); }
    private static DockBlockEntity dock(ServerPlayer player, BlockPos pos) {
        if (player.level().dimension().equals(SHIPYARD)) { tell(player, "message.skydock.no_docks_in_shipyard"); return null; }
        if (!(player.level().getBlockEntity(pos) instanceof DockBlockEntity dock)) { tell(player, "message.skydock.no_dock"); return null; }
        if (!canReachDock(player, dock)) { tell(player, "message.skydock.dock_out_of_reach"); return null; }
        if (dock.owner == null) dock.claim(player);
        if (!dock.canManage(player)) { tell(player, "message.skydock.dock_not_owner"); return null; }
        return dock;
    }
    public static boolean permitted(Player player, Ship ship) { return shares(player, ship.owner, ship.team); }
    /**
     * Whether {@code player} may use what {@code owner} owns: the owner, or a member of the owner's scoreboard team as it is now.
     * {@code storedTeam}, recorded at claim time, is used only where the owner's name cannot be looked up (on a client).
     */
    public static boolean shares(Player player, UUID owner, String storedTeam) {
        if (owner == null) return false;
        if (owner.equals(player.getUUID())) return true;
        if (player.getTeam() == null) return false;
        MinecraftServer server = player.getServer();
        var cache = server == null ? null : server.getProfileCache();
        ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(owner);
        String name = online != null ? online.getScoreboardName() : cache == null ? null : cache.get(owner).map(com.mojang.authlib.GameProfile::getName).orElse(null);
        String team = sharedTeam(online != null || cache != null, name, n -> {
            var found = player.level().getScoreboard().getPlayersTeam(n); return found == null ? null : found.getName();
        }, storedTeam);
        return !team.isEmpty() && team.equals(player.getTeam().getName());
    }
    /**
     * The team that shares an owner's ships and docks, or "" for nobody but the owner. With a lookup, it is the owner's current team
     * ({@code teamOf} their name), and an unknown owner shares with no one; without one it falls back to {@code storedTeam}.
     */
    static String sharedTeam(boolean lookedUp, String ownerName, java.util.function.Function<String, String> teamOf, String storedTeam) {
        if (!lookedUp) return storedTeam == null ? "" : storedTeam;
        String team = ownerName == null ? null : teamOf.apply(ownerName);
        return team == null ? "" : team;
    }
    public static void inspect(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        DockValidation result = DockValidation.scan(player.serverLevel(), dock);
        if (!result.blocks().isEmpty()) dock.markManualHull(result.blocks().size());
        else if (dock.manualHull() || dock.assemblyComplete()) dock.clearHullMarker("status.skydock.ready");
        tell(player, result.summary());
        DockFrame frame = dock.frame();
        tell(player, "message.skydock.dock_interior", frame.origin().toShortString(), frame.toWorld(new BlockPos(frame.tier().width - 1, frame.tier().height - 1, frame.tier().length - 1)).toShortString());
    }
    public static void launch(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        if (dock.assemblyJob() != null) { tell(player, "message.skydock.wait_assembly"); return; }
        if (settling(player, pos)) return;
        MinecraftServer server = player.getServer(); ShipSavedData data = ShipSavedData.get(server);
        if (data.reserves(player.level().dimension(), pos, null)) { tell(player, "message.skydock.berth_reserved_launched"); return; }
        if (data.transferHolds(player.level().dimension(), pos, dock.frame().envelope())) { tell(player, "message.skydock.berth_transfer_pending"); return; }
        DockValidation result = DockValidation.scan(player.serverLevel(), dock);
        if (!result.valid()) { tell(player, result.summary()); return; }
        if (server.getLevel(SHIPYARD) == null) { tell(player, "message.skydock.shipyard_missing"); return; }
        Ship ship = new Ship(); ship.owner = dock.owner; ship.team = dock.team; ship.tier = dock.tier();
        ship.dimension = player.level().dimension(); ship.dock = pos.immutable();
        DockFrame frame = dock.frame(); ship.dockFacing = frame.facing();
        BlockPos origin = frame.origin();
        ship.blocks.putAll(result.blocks()); result.blocks().forEach((p, state) -> { if (!state.isAir()) ship.blueprint.put(p, state); });
        ship.mass = result.mass(); ship.lift = result.lift(); ship.cells = result.cells(); ship.engines = result.engines();
        ship.initializePivot();
        ship.pose = Ship.berthPose(frame, BlockPos.ZERO, ship.center());
        ship.previousPose = ship.pose;
        if (ships(player.level()).stream().anyMatch(s -> s.bounds().intersects(ship.bounds()))) { tell(player, "message.skydock.envelope_occupied"); return; }
        int region = data.nextRegion++;
        if (region >= 1_000_000) { tell(player, "message.skydock.shipyard_full"); return; }
        ship.yard = ShipSavedData.yardOf(region);
        ShipTransfer.snapshot(ship, player.serverLevel(), origin, frame.rotation());
        ship.phase = "launching"; ship.transferStep = Ship.Step.STARTED; ship.transferOrigin = origin; ship.transferFacing = frame.facing(); ship.transferDock = pos; ship.transferDimension = ship.dimension;
        data.ships.put(ship.id, ship);
        try {
            ShipTransfer.flush(server); forceTickets(server, ship); ShipTransfer.complete(server, ship); transferred(player, pos); ShipNetwork.resync(server);
            dock.clearHullMarker("status.skydock.launched");
            tell(player, "message.skydock.launched", ship.blocks.size());
        } catch (Exception ex) {
            Skydock.LOGGER.error("Launch retained in journal for {}", ship.id, ex);
            closeMenus(server, ship); tell(player, "message.skydock.transfer_paused");
        }
    }
    public static void redockNearest(ServerPlayer player, BlockPos pos) {
        DockBlockEntity dock = dock(player, pos); if (dock == null) return;
        if (dock.assemblyJob() != null) { tell(player, "message.skydock.cancel_before_redock"); return; }
        if (settling(player, pos)) return;
        Ship ship = ships(player.level()).stream().filter(s -> permitted(player, s))
                .min(Comparator.comparingDouble(s -> s.bounds().getCenter().distanceToSqr(Vec3.atCenterOf(pos)))).orElse(null);
        if (ship == null) { tell(player, "message.skydock.no_owned_ship"); return; }
        wake(player.getServer(), ship);
        if (dock.tier().ordinal() < ship.tier.ordinal()) { tell(player, "message.skydock.berth_too_small"); return; }
        ShipSavedData data = ShipSavedData.get(player.getServer());
        if (data.reserves(ship.dimension, pos, ship.id)) { tell(player, "message.skydock.berth_reserved_other"); return; }
        if (data.transferHolds(ship.dimension, pos, dock.frame().envelope())) { tell(player, "message.skydock.berth_transfer_pending"); return; }
        // A smaller hull is centred in the berth's local frame, then the whole berth is turned to the dock's facing.
        DockFrame frame = dock.frame();
        BlockPos offset = new BlockPos((frame.tier().width - ship.tier.width) / 2, 0, (frame.tier().length - ship.tier.length) / 2);
        BlockPos origin = frame.toWorld(offset);
        ShipPose berth = Ship.berthPose(frame, offset, ship.center());
        if (!Navigation.redockReady(Navigation.berth(ship.pose, new Vec3(berth.x(), berth.y(), berth.z()), frame.yaw()), ship.velocity.length())) {
            tell(player, "message.skydock.redock_alignment"); return;
        }
        ServerLevel world = player.serverLevel();
        Set<BlockPos> solid = new HashSet<>();
        for (var entry : ship.blocks.entrySet()) {
            BlockPos at = DockFrame.toWorld(origin, frame.rotation(), entry.getKey());
            if (!world.getBlockState(at).isAir()) { tell(player, "message.skydock.berth_obstructed", at.toShortString()); return; }
            if (!entry.getValue().isAir()) solid.add(at);
        }
        // The hull's blocks would land on anything alive standing where they go; its own riders move with it.
        Entity standing = world.getEntities((Entity) null, frame.envelope(), e -> e instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()
                && !living.isSpectator() && !rides(e, ship) && inCells(solid, e.getBoundingBox())).stream().findFirst().orElse(null);
        if (standing != null) { tell(player, "message.skydock.berth_occupied", standing.getDisplayName(), standing.blockPosition().toShortString()); return; }
        refresh(player.getServer(), ship); ShipTransfer.snapshot(ship, player.getServer().getLevel(SHIPYARD), ship.yard, net.minecraft.world.level.block.Rotation.NONE);
        ShipPose old = ship.pose; ship.pose = berth;
        carry(world, ship, old);
        ship.velocity = Vec3.ZERO; ship.pilot = null; ship.seated.clear();
        ship.phase = "redocking"; ship.transferStep = Ship.Step.STARTED; ship.transferOrigin = origin; ship.transferFacing = frame.facing(); ship.transferDock = pos; ship.transferDimension = world.dimension();
        fleetChanged();
        try {
            ShipTransfer.flush(player.getServer()); ShipTransfer.complete(player.getServer(), ship); transferred(player, pos); ShipNetwork.resync(player.getServer());
            dock.markManualHull(ship.blocks.size());
            tell(player, "message.skydock.redocked");
        }
        catch (Exception ex) { Skydock.LOGGER.error("Redock retained in journal for {}", ship.id, ex); closeMenus(player.getServer(), ship); tell(player, "message.skydock.transfer_paused"); }
    }
    /** Whether {@code box} overlaps any of {@code cells}; touching a cell's face does not count. */
    static boolean inCells(Set<BlockPos> cells, AABB box) {
        AABB inner = box.deflate(1e-7);
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(inner.minX, inner.minY, inner.minZ), BlockPos.containing(inner.maxX, inner.maxY, inner.maxZ)))
            if (cells.contains(p)) return true;
        return false;
    }
    /** Whether {@code entity} travels with {@code ship}: its pilot, seated or boarding crew, or attached to its deck. */
    private static boolean rides(Entity entity, Ship ship) {
        Boarding boarding = BOARDING.get(entity.getUUID());
        return entity.getUUID().equals(ship.pilot) || ship.seated.containsKey(entity.getUUID()) || boarding != null && boarding.ship.equals(ship.id) || attachment(entity).is(ship.id);
    }
    /** The launched ship whose home berth is {@code dock} in {@code dimension}: the one an anchor bound to that dock summons. */
    static boolean summonable(Ship ship, ResourceKey<Level> dimension, BlockPos dock) {
        return ship.phase.equals("active") && ship.dimension.equals(dimension) && dock.equals(ship.dock);
    }
    /** Brings the ship launched from {@code dock} to hover beside the player, stopped and moored; true when it came. */
    public static boolean summon(ServerPlayer player, ResourceKey<Level> dimension, BlockPos dock) {
        if (!player.level().dimension().equals(dimension)) { tell(player, "message.skydock.summon_dimension"); return false; }
        MinecraftServer server = player.getServer(); ServerLevel level = player.serverLevel();
        Ship ship = ShipSavedData.get(server).ships.values().stream().filter(s -> summonable(s, dimension, dock)).findFirst().orElse(null);
        if (ship == null) { tell(player, "message.skydock.summon_no_ship"); return false; }
        if (!permitted(player, ship)) { tell(player, "message.skydock.summon_not_permitted"); return false; }
        // A dormant ship's chunks, and whoever is on it, may be unloaded: wake it and ask again once they load. No use or cooldown is spent.
        // It keeps stirring for a second after waking and until the entities under it load, so a held use cannot outrun them.
        if (!ship.yardLost && (ship.dormant || ShipSleep.stirring(server.overworld().getGameTime(), ship.wokeAt, entitiesLoaded(level, ship)))) {
            wake(server, ship); tell(player, "message.skydock.summon_stirring"); return false;
        }
        if (aboard(level, ship)) { tell(player, "message.skydock.summon_aboard"); return false; }
        ShipPose spot = null;
        for (ShipPose pose : SummonPlacement.candidates(player.position(), player.getYRot(), ship.hullBounds()))
            if (roomFor(level, ship, pose)) { spot = pose; break; }
        if (spot == null) { tell(player, "message.skydock.summon_no_room"); return false; }
        wake(server, ship);
        Vec3 from = ship.hullWorldBounds().getCenter();
        // Items, orbs, boats and minecarts on the hull turn and move with it, about the old pivot into the new pose, and are left at rest.
        ShipPose old = ship.pose;
        List<Entity> cargo = level.getEntities((Entity) null, old.toWorld(ship.hullBounds()).inflate(ShipCollision.ATTACHMENT_HEIGHT + 1), e -> carriedWith(ship, e));
        ship.pose = spot; ship.previousPose = spot;
        float turn = (float) Mth.wrapDegrees(spot.yaw() - old.yaw());
        for (Entity e : cargo) { e.setPos(spot.toWorld(old.toLocal(e.position()))); e.setYRot(e.getYRot() + turn); e.setDeltaMovement(Vec3.ZERO); e.fallDistance = 0; attachment(e).attach(ship.id); }
        ship.velocity = Vec3.ZERO; ship.yawVelocity = 0; ship.appliedThrottle = ship.appliedClimb = 0;
        ship.throttle = ship.steering = ship.climb = 0; ship.cruise = false; ship.cruiseSpeed = 0;
        ship.holdAltitude = Double.NaN; ship.headingLock = Double.NaN; ship.moored = true; ship.blocked = false; ship.idleTicks = 0;
        long tick = server.overworld().getGameTime();
        ship.history.accept(tick, ship.pose, ship.velocity, ship.yawVelocity);
        fleetChanged(); forceTickets(server, ship);
        ShipNetwork.syncPose(server, ship); ShipNetwork.syncPlayers(server);
        ShipSavedData.get(server).setDirty();
        warpEffect(level, from, ship.hullBounds().getSize(), net.minecraft.core.particles.ParticleTypes.PORTAL);
        warpEffect(level, ship.hullWorldBounds().getCenter(), ship.hullBounds().getSize(), net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL);
        tell(player, "message.skydock.summoned");
        return true;
    }
    /** A deep ender whoosh and a cloud of portal particles the size of the hull, where a warped ship left or arrived. */
    private static void warpEffect(ServerLevel level, Vec3 at, double size, net.minecraft.core.particles.SimpleParticleType particle) {
        level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 8, .5f);
        double spread = Math.max(1, size / 4);
        level.sendParticles(particle, at.x, at.y, at.z, (int) Math.min(400, 40 * spread), spread, spread / 2, spread, .5);
    }
    /** Whether every chunk under the hull (and a block round it) has its entities loaded, so anyone aboard is in the level to be seen. */
    private static boolean entitiesLoaded(ServerLevel level, Ship ship) {
        AABB box = ship.hullWorldBounds().inflate(1);
        for (int cx = SectionPos.blockToSectionCoord(Mth.floor(box.minX)); cx <= SectionPos.blockToSectionCoord(Mth.floor(box.maxX)); cx++)
            for (int cz = SectionPos.blockToSectionCoord(Mth.floor(box.minZ)); cz <= SectionPos.blockToSectionCoord(Mth.floor(box.maxZ)); cz++)
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return false;
        return true;
    }
    /** The hull box raised 2 blocks: where to look for living things a warp would leave behind. */
    static AABB deckBox(AABB hull) { return hull.expandTowards(0, 2, 0); }
    /** Whether a box stands on the hull's blocks or overlaps one of them: a creature there is aboard, unlike one in the air beside a diagonal hull or a mast. */
    static boolean onHull(Ship ship, ShipPose pose, AABB box) { return ShipCollision.supported(ship, pose, box) || !ShipCollision.shapes(ship, pose, box.deflate(1e-3)).isEmpty(); }
    /** Whether the hull cell a point is in, or the cell below or above it, holds a hull block: a fish in a tank, a bat under a ceiling or a mob mid-hop is aboard. */
    static boolean inHullCell(Ship ship, ShipPose pose, Vec3 feet) {
        BlockPos cell = BlockPos.containing(pose.toLocal(feet).add(ship.center()));
        for (BlockPos p : new BlockPos[]{cell, cell.below(), cell.above()}) { BlockState state = ship.blocks.get(p); if (state != null && !state.isAir()) return true; }
        return false;
    }
    /** Whether an entity stops a summon: only a living, awake creature does, when it is attached to the hull or stands on, in or beside a block of it. Items, orbs, boats and minecarts ride along instead. */
    static boolean blocksSummon(Class<?> type, boolean alive, boolean spectator, boolean attached, boolean onHullOrInCell) {
        return net.minecraft.world.entity.LivingEntity.class.isAssignableFrom(type) && alive && !spectator && (attached || onHullOrInCell);
    }
    /** Whether a type is never carried by a warp: hanging things (frames, paintings, leash knots) pop off, pods fall through hulls, and display, marker and interaction entities are not cargo. */
    static boolean neverCarried(Class<?> type) {
        return net.minecraft.world.entity.decoration.BlockAttachedEntity.class.isAssignableFrom(type) || dev.skydock.cargo.PodEntity.class.isAssignableFrom(type)
                || net.minecraft.world.entity.Display.class.isAssignableFrom(type) || net.minecraft.world.entity.Marker.class.isAssignableFrom(type)
                || net.minecraft.world.entity.Interaction.class.isAssignableFrom(type);
    }
    /**
     * Whether an entity goes with the hull when it warps: a non-living, attachable one of a carriable type that is attached to this ship, or
     * unattached and resting on its blocks. One attached to another ship stays with that ship, and one only beside or under the hull stays put.
     */
    static boolean carriedWith(Class<?> type, boolean attachable, boolean attachedHere, boolean attachedElsewhere, boolean supported) {
        return !net.minecraft.world.entity.LivingEntity.class.isAssignableFrom(type) && !neverCarried(type) && attachable && !attachedElsewhere && (attachedHere || supported);
    }
    private static boolean onHullOrInCell(Ship ship, Entity e) { return onHull(ship, ship.pose, e.getBoundingBox()) || inHullCell(ship, ship.pose, e.position()); }
    private static boolean attachedTo(Ship ship, Entity e) { Ship on = attachedShip(e); return on != null && on.id.equals(ship.id); }
    private static boolean carriedWith(Ship ship, Entity e) {
        Ship on = attachedShip(e); boolean here = on != null && on.id.equals(ship.id);
        return carriedWith(e.getClass(), attachable(e), here, on != null && !here, ShipCollision.supported(ship, ship.pose, e.getBoundingBox()));
    }
    private static boolean aboard(ServerLevel level, Ship ship) {
        if (ship.pilot != null || !ship.seated.isEmpty() || BOARDING.values().stream().anyMatch(b -> b.ship().equals(ship.id))) return true;
        // Players, mobs, pets and villagers count whether or not they are attached, when they stand on or in the hull's blocks or in, on or under a hull cell; loose items and the like do not.
        return !level.getEntities((Entity) null, deckBox(ship.hullWorldBounds()).minmax(ship.hullWorldBounds().inflate(2)),
                e -> blocksSummon(e.getClass(), e.isAlive(), e.isSpectator(), attachedTo(ship, e), onHullOrInCell(ship, e))).isEmpty();
    }
    /** A summoned hull must land in loaded chunks, clear of terrain, fluids, other ships and every entity but what rides along with it (and loose items and orbs), the summoner included. */
    private static boolean roomFor(ServerLevel level, Ship ship, ShipPose pose) {
        AABB box = pose.toWorld(ship.hullBounds());
        if (!level.hasChunksAt(Mth.floor(box.minX), Mth.floor(box.minZ), Mth.floor(box.maxX), Mth.floor(box.maxZ))) return false;
        if (!level.getEntities((Entity) null, box, e -> !e.isSpectator() && !looseInCell(e.getClass()) && !carriedWith(ship, e)).isEmpty()) return false;
        List<Ship> others = ships(level).stream().filter(s -> s != ship).toList();
        if (ShipPhysics.collides(level, ship, pose, true, others, false)) return false;
        // Surface boxes only see the shell; this also catches terrain or water inside the hull.
        for (var entry : ship.blocks.entrySet()) if (!entry.getValue().isAir() && groundAt(level, ship, pose, entry.getKey())) return false;
        return true;
    }
    /** Terrain or fluid in the world block at a hull cell's centre. */
    private static boolean groundAt(ServerLevel level, Ship ship, ShipPose pose, BlockPos cell) {
        BlockPos at = BlockPos.containing(pose.toWorld(Vec3.atCenterOf(cell).subtract(ship.center())));
        BlockState there = level.getBlockState(at);
        return !there.getFluidState().isEmpty() || !there.getCollisionShape(level, at).isEmpty();
    }
    /** Whether one hull cell at {@code pose} is free in the world: no terrain or fluid, no other ship's hull and no entity but spectators and loose items or orbs, much as {@link #roomFor} asks of a whole hull. */
    public static boolean cellClear(ServerLevel level, Ship ship, ShipPose pose, BlockPos cell) {
        AABB box = pose.transform().toWorld(new AABB(cell).move(ship.center().scale(-1))).deflate(1e-3);
        if (groundAt(level, ship, pose, cell) || level.getBlockCollisions(null, box).iterator().hasNext() || level.containsAnyLiquid(box)) return false;
        for (Ship other : ships(level)) if (other != ship && other.hullWorldBounds().intersects(box) && !ShipCollision.shapes(other, other.pose, box).isEmpty()) return false;
        return level.getEntities((Entity) null, box, e -> !e.isSpectator() && !looseInCell(e.getClass())).isEmpty();
    }
    /** The shipyard box of a hull, 2 blocks wider all round: loose items and orbs in it came from the ship. */
    static AABB yardBox(Ship ship) { Vec3 c = ship.center(); return ship.hullBounds().move(c.x + ship.yard.getX(), c.y + ship.yard.getY(), c.z + ship.yard.getZ()).inflate(2); }
    /** Where a point of the ship's shipyard region is in the world now. */
    static Vec3 yardToWorld(Ship ship, Vec3 yardPos) { return ship.blockToWorld(yardPos.subtract(Vec3.atLowerCornerOf(ship.yard))); }
    /** A loose entity's world motion: its motion in the north-frame shipyard turned with the hull, plus the hull's own. */
    static Vec3 worldMotion(Vec3 yardMotion, double yaw, Vec3 shipVelocity) { return ShipPose.rotate(yardMotion, yaw).add(shipVelocity); }
    /** Ticks a dropped item lies in the shipyard before it is moved out; a hopper aboard takes one within its 8-tick cooldown. */
    static final int LOOSE_ITEM_AGE = 40;
    /** Ticks an item in or on a hopper aboard waits for it; after that it goes out anyway, so a locked or full hopper never lets it despawn in the shipyard. */
    static final int COLLECTOR_WAIT = 200;
    /**
     * Whether a loose entity in the shipyard goes out to the world: every orb, an item at least {@link #COLLECTOR_WAIT} ticks old,
     * and an item at least {@link #LOOSE_ITEM_AGE} ticks old that is not in or directly above a collector cell (a hopper aboard takes it).
     */
    static boolean relocates(boolean item, int age, BlockPos cell, java.util.function.Predicate<BlockPos> collector) {
        return !item || age >= COLLECTOR_WAIT || age >= LOOSE_ITEM_AGE && !collector.test(cell) && !collector.test(cell.below());
    }
    /** Whether a block entity pulls in items lying in or on it: a hopper does; a chest, jukebox or dispenser holds items but never collects them. */
    static boolean collects(net.minecraft.world.level.block.entity.BlockEntity be) { return be instanceof net.minecraft.world.level.block.entity.Hopper; }
    /** Items and orbs made aboard (jukebox discs, composters, dispensers, popped attachments) land in the shipyard; this moves them out beside the hull. */
    private static void returnLoose(ServerLevel yard, ServerLevel world, Ship ship) {
        java.util.function.Predicate<BlockPos> collector = p -> collects(yard.getBlockEntity(p));
        for (Entity loose : yard.getEntities((Entity) null, yardBox(ship), e -> e.isAlive() && looseInCell(e.getClass())
                && relocates(e instanceof net.minecraft.world.entity.item.ItemEntity, e.tickCount, e.blockPosition(), collector)))
            loose.changeDimension(new net.minecraft.world.level.portal.DimensionTransition(world, yardToWorld(ship, loose.position()),
                    worldMotion(loose.getDeltaMovement(), ship.pose.yaw(), ship.velocity), loose.getYRot() + (float) ship.pose.yaw(), loose.getXRot(),
                    net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
    }
    /** Dropped items and experience orbs lying in a cell do not keep a block out of it; they are pushed aside like any block placement does. */
    static boolean looseInCell(Class<?> type) { return net.minecraft.world.entity.item.ItemEntity.class.isAssignableFrom(type) || net.minecraft.world.entity.ExperienceOrb.class.isAssignableFrom(type); }
    public static boolean canReachDock(Player player, DockBlockEntity dock) {
        if (player.distanceToSqr(Vec3.atCenterOf(dock.getBlockPos())) <= 64) return true;
        HitResult hit = player.pick(player.blockInteractionRange(), 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && player.level().getBlockState(block.getBlockPos()).is(SkydockBlocks.HELM.get())
                && dock.frame().envelope().contains(Vec3.atCenterOf(block.getBlockPos()));
    }
    public static void deviceOnGround(ServerPlayer player, DeviceBlock.Kind kind, BlockPos pos) {
        if (kind != DeviceBlock.Kind.HELM) { tell(player, "message.skydock.device_aboard"); return; }
        if (player.level().dimension().equals(SHIPYARD)) return;
        List<DockBlockEntity> candidates = new ArrayList<>();
        ChunkPos center = new ChunkPos(pos);
        for (int x = center.x - 7; x <= center.x + 7; x++) for (int z = center.z - 7; z <= center.z + 7; z++) {
            var chunk = player.serverLevel().getChunkSource().getChunkNow(x, z);
            if (chunk != null) for (var entity : chunk.getBlockEntities().values())
                if (entity instanceof DockBlockEntity dock && dock.frame().envelope().contains(Vec3.atCenterOf(pos))) candidates.add(dock);
        }
        if (candidates.isEmpty()) { tell(player, "message.skydock.helm_no_dock"); return; }
        if (candidates.size() > 1) { tell(player, "message.skydock.helm_many_docks"); return; }
        DockBlockEntity dock = dock(player, candidates.getFirst().getBlockPos());
        if (dock != null) dev.architectury.registry.menu.MenuRegistry.openExtendedMenu(player, dock, buffer -> buffer.writeBlockPos(dock.getBlockPos()));
    }
    public static void control(ServerPlayer player, float throttle, float steering, float climb) {
        for (Ship ship : ships(player.level())) if (player.getUUID().equals(ship.pilot)) {
            if (!Float.isFinite(throttle) || !Float.isFinite(steering) || !Float.isFinite(climb)) return;
            ship.throttle = Math.clamp(throttle, -1, 1); ship.steering = Math.clamp(steering, -1, 1); ship.climb = Math.clamp(climb, -1, 1);
            ship.lastInputTick = player.level().getGameTime();
        }
    }
    public static void release(ServerPlayer player) {
        for (Ship ship : ships(player.level())) {
            if (player.getUUID().equals(ship.pilot)) {
                ship.pilot = null; ship.pilotAnchor = null; ship.throttle = ship.steering = ship.climb = 0;
                if (ship.cruise) tell(player, Component.translatable("message.skydock.helm_released_cruise", Component.keybind("key.skydock.cruise")));
                else tell(player, "message.skydock.helm_released");
            }
            ship.seated.remove(player.getUUID());
        }
    }
    public static void toggleCruise(ServerPlayer player) {
        Ship ship = ships(player.level()).stream().filter(s -> permitted(player, s))
                .filter(s -> player.getUUID().equals(s.pilot) || s.pose.toWorld(s.hullBounds()).inflate(1).contains(player.position()))
                .min(Comparator.comparingDouble(s -> player.getUUID().equals(s.pilot) ? -1 : s.pose.toWorld(s.hullBounds()).distanceToSqr(player.position()))).orElse(null);
        if (ship == null) { tell(player, "message.skydock.cruise_board"); return; }
        if (ship.cruise) { ship.cruise = false; tell(player, "message.skydock.cruise_off"); }
        else {
            if (ship.moored) { tell(player, "message.skydock.cruise_unmoor"); return; }
            if (ship.engines == 0) { tell(player, "message.skydock.cruise_no_engine"); return; }
            double speed = ship.velocity.dot(ShipPose.rotate(new Vec3(0, 0, -1), ship.pose.yaw()));
            double speedLimit = ShipPhysics.installedMaxSpeed(ship);
            ship.cruiseSpeed = Math.abs(speed) < .03 ? .18 : Math.clamp(speed, -speedLimit, speedLimit);
            ship.cruise = true;
            tell(player, Component.translatable("message.skydock.cruise_engaged", Component.keybind("key.forward"), Component.keybind("key.back"),
                    Component.keybind("key.skydock.release"), Component.keybind("key.skydock.cruise")));
        }
        ShipNetwork.syncPose(player.getServer(), ship);
    }
    private static Ship piloting(ServerPlayer player) { return ships(player.level()).stream().filter(s -> player.getUUID().equals(s.pilot)).findFirst().orElse(null); }
    public static void toggleAltitudeHold(ServerPlayer player) {
        Ship ship = piloting(player); if (ship == null) { tell(player, "message.skydock.hold_helm"); return; }
        if (Double.isNaN(ship.holdAltitude)) { ship.holdAltitude = ship.pose.y(); tell(player, "message.skydock.altitude_hold_on", String.format(java.util.Locale.ROOT, "%.0f", ship.holdAltitude)); }
        else { ship.holdAltitude = Double.NaN; tell(player, "message.skydock.altitude_hold_off"); }
        ShipNetwork.syncPose(player.getServer(), ship);
    }
    public static void toggleHeadingLock(ServerPlayer player) {
        Ship ship = piloting(player); if (ship == null) { tell(player, "message.skydock.hold_helm"); return; }
        if (Double.isNaN(ship.headingLock)) { ship.headingLock = Navigation.snapHeading(ship.pose.yaw()); tell(player, "message.skydock.heading_lock_on", String.format(java.util.Locale.ROOT, "%.0f", ship.headingLock)); }
        else { ship.headingLock = Double.NaN; tell(player, "message.skydock.heading_lock_off"); }
        ShipNetwork.syncPose(player.getServer(), ship);
    }
    public static void tick(MinecraftServer server) {
        ShipSavedData data = SERVERS.get(server); if (data == null) return;
        JOINED.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        BOARDING.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) prepareBoarding(player);
        ServerLevel yard = server.getLevel(SHIPYARD);
        long tick = server.overworld().getGameTime();
        boolean awake = false, changed = false;
        if (tick % RESUME_TICKS == 0) resume(server, data);
        for (Ship ship : List.copyOf(data.ships.values())) {
            if (!ship.phase.equals("active")) continue;
            ServerLevel level = server.getLevel(ship.dimension); if (level == null) continue;
            if (ship.yardLost) { if (!ship.dormant) { releaseTickets(server, ship); ship.dormant = true; } continue; }
            // Spread the once-a-second work across ticks so a large fleet does not rescan all at once.
            if ((tick + Math.floorMod(ship.id.hashCode(), 20)) % 20 == 0) {
                boolean near = playerWithin(server, ship.dimension, ship.bounds(), ShipSleep.WAKE_RANGE);
                if (ship.dormant && near) wake(server, ship);
                if (!ship.dormant) {
                    forceTickets(server, ship); refresh(server, ship);
                    // An unattended cruising ship would fly on for ever, loading chunks as it goes.
                    ship.unattendedTicks = ShipSleep.nextUnattended(playerWithin(server, ship.dimension, ship.hullWorldBounds(), ShipSleep.ATTEND_RANGE), ship.unattendedTicks);
                    if (ship.cruise && ShipSleep.cruiseLapses(false, false, ship.unattendedTicks)) ship.cruise = false;
                    boolean crewed = ship.pilot != null || !ship.seated.isEmpty() || BOARDING.values().stream().anyMatch(b -> b.ship.equals(ship.id));
                    // A sunk hull lying on the ground can fall no further. Only a ship that would otherwise sink is probed.
                    boolean grounded = !ship.moored && ship.lift < ship.mass && ShipPhysics.grounded(level, ship);
                    boolean restful = ShipSleep.restful(crewed, ship.cruise, ship.velocity.lengthSqr(), ship.moored, ship.lift, ship.mass, grounded);
                    ship.idleTicks = ShipSleep.nextIdle(restful, near, ship.idleTicks);
                    if (ShipSleep.sleeps(restful, near, ship.idleTicks)) { releaseTickets(server, ship); ship.dormant = true; }
                }
            }
            if (ship.dormant) continue;
            awake = true;
            ship.previousPose = ship.pose;
            if (ship.pilot != null) {
                ServerPlayer pilot = server.getPlayerList().getPlayer(ship.pilot);
                if (pilot == null && ship.cruise && ShipSleep.cruiseLapses(true, crewAboard(server, ship, null), ship.unattendedTicks)) ship.cruise = false;
                if (pilot == null || !pilot.isAlive() || !permitted(pilot, ship) || pilot.level() != level || ship.pilotHelm == null ||
                        pilot.position().distanceToSqr(ship.blockToWorld(Vec3.atCenterOf(ship.pilotHelm))) > 36) ship.pilot = null;
            }
            if (ship.pilot == null) ship.pilotAnchor = null;
            if (ship.pilot == null || tick - ship.lastInputTick > 10) ship.throttle = ship.steering = ship.climb = 0;
            ShipPhysics.step(server, level, ship);
            ship.history.accept(tick, ship.pose, ship.velocity, ship.yawVelocity);
            carry(level, ship, ship.previousPose);
            ShipDamage.ram(level, ship);
            if (yard != null && tick % 10 == 0) returnLoose(yard, level, ship);
            if (tick % 2 == 0) ShipNetwork.syncPose(server, ship, false);
            SavedState state = new SavedState(ship.pose, ship.velocity, ship.phase, ship.moored, ship.cruise, ship.pilot, ship.seated.size(), ship.revision, ship.mass, ship.lift, ship.holdAltitude, ship.headingLock, ship.cruiseSpeed);
            if (!state.equals(ship.savedState)) { ship.savedState = state; changed = true; }
        }
        // Region tickets load chunks, but an empty dimension otherwise stops BE ticks after 300 ticks.
        if (yard != null && awake) yard.resetEmptyTime();
        ShipInteractions.tick(server);
        if (tick % 20 == 0) ShipNetwork.syncPlayers(server);
        if (changed) data.setDirty();
    }
    private record SavedState(ShipPose pose, Vec3 velocity, String phase, boolean moored, boolean cruise, UUID pilot, int seated, int revision, double mass, double lift, double holdAltitude, double headingLock, double cruiseSpeed) {}
    private static boolean playerWithin(MinecraftServer server, ResourceKey<Level> dimension, AABB box, double range) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.level().dimension().equals(dimension) && box.distanceToSqr(player.position()) <= range * range) return true;
        return false;
    }
    /** Whether an online player other than {@code except} (null for none) rides the ship: seated, boarding, or standing on its deck. */
    private static boolean crewAboard(MinecraftServer server, Ship ship, UUID except) {
        if (BOARDING.entrySet().stream().anyMatch(b -> b.getValue().ship.equals(ship.id) && !b.getKey().equals(except))) return true;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (!player.getUUID().equals(except) && player.level().dimension().equals(ship.dimension) && (ship.seated.containsKey(player.getUUID()) || attachment(player).is(ship.id))) return true;
        return false;
    }
    /**
     * A player leaving the game who piloted, sat on or stood on a cruising ship turns its cruise off at once when no other crew is
     * aboard, so a pilot who released the helm before logging out does not leave the ship flying on. Players removed by a server
     * shutdown are not leaving the ship: cruise is saved as it was.
     */
    public static void playerLeft(ServerPlayer player) {
        MinecraftServer server = player.getServer(); ShipSavedData data = server == null || !server.isRunning() ? null : SERVERS.get(server); if (data == null) return;
        ShipAttachment attached = attachment(player); UUID id = player.getUUID();
        for (Ship ship : data.ships.values()) {
            boolean rode = id.equals(ship.pilot) || ship.seated.containsKey(id) || attached.is(ship.id);
            if (ship.cruise && ShipSleep.cruiseLapses(rode, crewAboard(server, ship, id), 0)) ship.cruise = false;
        }
    }
    /** Call before touching a ship's shipyard blocks: a dormant ship holds no chunk tickets, so reading them would load chunks synchronously. */
    public static void wake(MinecraftServer server, Ship ship) {
        // Cheap no-op for an awake ship; its idle count is left alone because a nearby player already prevents sleep.
        if (!ship.dormant) return;
        ship.dormant = false; ship.idleTicks = 0; ship.wokeAt = server.overworld().getGameTime(); forceTickets(server, ship);
    }
    /** Whether every cell that held a block at the last refresh now reads air: the yard was deleted, not edited. False for a hull with no blocks. */
    static boolean hullVanished(Map<BlockPos, BlockState> hull, java.util.function.Function<BlockPos, BlockState> now) {
        boolean any = false;
        for (var entry : hull.entrySet()) if (!entry.getValue().isAir()) { if (!now.apply(entry.getKey()).isAir()) return false; any = true; }
        return any;
    }
    /** Re-reads the hull from the shipyard. A hull that vanished whole keeps its stored states, and the ship is held dormant for an operator. */
    public static void refresh(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD); if (yard == null) return;
        if (hullVanished(ship.blocks, p -> yard.getBlockState(ship.yard.offset(p)))) {
            if (!ship.yardLost) {
                Skydock.LOGGER.error("Ship {} from the dock at {} in {} lost every hull block from its shipyard region at {}; it stays dormant until an operator runs /skydock repair-yard {}",
                        ship.id, ship.dock.toShortString(), ship.dimension.location(), ship.yard.toShortString(), ship.id);
                ship.yardLost = true; releaseTickets(server, ship); ship.dormant = true;
            }
            return;
        }
        ship.mass = 0; ship.cells = 0; ship.engines = 0; boolean changed = false;
        for (var entry : ship.blocks.entrySet()) {
            BlockPos worldPos = ship.yard.offset(entry.getKey());
            BlockState now = yard.getBlockState(worldPos);
            if (now.is(SkydockBlocks.TIMBER_RAILING.get())) {
                BlockState connected = FenceConnections.resolve(now, worldPos, yard::getBlockState, yard);
                if (connected != now) {
                    yard.setBlock(worldPos, connected, net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                    now = connected;
                }
            }
            if (now != entry.getValue()) { entry.setValue(now); changed = true; }
            if (now.isAir()) continue;
            ship.mass += MassTable.mass(now);
            if (now.is(SkydockBlocks.LIFT_CELLS) && !now.is(SkydockBlocks.DECORATIONS)) ship.cells++;
            if (now.is(SkydockBlocks.ENGINES)) ship.engines++;
        }
        ship.lift = ship.cells * MassTable.liftPerCell();
        // syncPlayers later this tick resends only hulls whose revision moved; resync would resend every hull.
        if (changed) ship.revision++;
    }
    /** Re-pastes the ship's stored hull states into its shipyard region and wakes it; block-entity contents are not restored. Returns the blocks pasted. */
    public static int repairYard(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD); if (yard == null) return 0;
        ship.yardLost = false; ship.dormant = true; wake(server, ship);
        int pasted = ShipTransfer.pasteStates(ship, yard);
        ship.revision++; refresh(server, ship); fleetChanged();
        ShipSavedData.get(server).setDirty(); ShipNetwork.resync(server);
        return pasted;
    }
    public static void carry(Level level, Ship ship, ShipPose previous) {
        List<Entity> riders = new ArrayList<>(level.getEntities((Entity) null,
                previous.toWorld(ship.hullBounds()).inflate(ShipCollision.ATTACHMENT_HEIGHT + 1)));
        if (!level.isClientSide) BOARDING.forEach((id, boarding) -> {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (boarding.ship.equals(ship.id) && player != null && player.level() == level && !riders.contains(player)) riders.add(player);
        });
        for (Entity entity : riders) {
            ShipAttachment attachment = attachment(entity);
            if (!attachable(entity)) { attachment.detach(entity, false); continue; }
            boolean seated = ship.seated.containsKey(entity.getUUID());
            boolean piloting = entity.getUUID().equals(ship.pilot) && ship.pilotAnchor != null;
            Boarding boarding = level.isClientSide ? null : BOARDING.get(entity.getUUID());
            boolean joining = boarding != null && boarding.ship.equals(ship.id);
            boolean attached = attachment.is(ship.id);
            if (!seated && !piloting && !joining) {
                if (attachment.ship() != null && !attached) {
                    Ship other = findShip(level, attachment.ship());
                    if (other == null || ShipAttachment.strayed(other.hullWorldBounds(), entity.position())) attachment.detach(entity, false);
                }
                if (attachment.ship() != null && !attached) continue;
                if (!attached && ShipCollision.supported(ship, previous, entity.getBoundingBox())) {
                    attachment.attach(ship.id);
                    attached = true;
                }
                if (!attached) continue;
                if (!ShipCollision.aboveHull(ship, previous, entity.getBoundingBox())) {
                    attachment.detach(entity, true);
                    continue;
                }
            } else attachment.attach(ship.id);
            Vec3 local = previous.toLocal(entity.position());
            Vec3 next = ship.pose.toWorld(local);
            if (seated) next = ship.blockToWorld(Vec3.atLowerCornerOf(ship.seated.get(entity.getUUID())).add(.5, .5, .5));
            if (piloting) next = ship.blockToWorld(ship.pilotAnchor);
            if (joining) next = ship.blockToWorld(boarding.localFeet);
            Vec3 carrierVelocity = next.subtract(entity.position());
            entity.setPos(next);
            attachment.carried(carrierVelocity);
            if (attached && !seated && !piloting && !joining) {
                double turn = net.minecraft.util.Mth.wrapDegrees(ship.pose.yaw() - previous.yaw());
                Vec3 velocity = entity.getDeltaMovement();
                Vec3 horizontal = ShipPose.rotate(new Vec3(velocity.x, 0, velocity.z), turn);
                entity.setDeltaMovement(horizontal.x, velocity.y, horizontal.z);
            }
            if (level.isClientSide && entity instanceof Player player && player.isLocalPlayer()) {
                float turn = (float) net.minecraft.util.Mth.wrapDegrees(ship.pose.yaw() - previous.yaw());
                entity.setYRot(entity.getYRot() + turn);
            }
            if (seated || piloting || joining) {
                entity.fallDistance = 0;
                entity.setDeltaMovement(Vec3.ZERO);
                entity.setOnGround(true);
            }
        }
    }
    private static Set<ChunkPos> chunks(AABB box) {
        Set<ChunkPos> result = new HashSet<>();
        for (int x = net.minecraft.util.Mth.floor(box.minX) >> 4; x <= net.minecraft.util.Mth.floor(box.maxX) >> 4; x++)
            for (int z = net.minecraft.util.Mth.floor(box.minZ) >> 4; z <= net.minecraft.util.Mth.floor(box.maxZ) >> 4; z++) result.add(new ChunkPos(x, z));
        return result;
    }
    static void forceTickets(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD), world = server.getLevel(ship.dimension); if (yard == null || world == null) return;
        for (ChunkPos cp : chunks(new AABB(ship.yard).expandTowards(ship.tier.width, ship.tier.height, ship.tier.length))) yard.getChunkSource().addRegionTicket(TICKET, cp, 2, ship.id);
        // Lead along the velocity so a cruising hull finds its path already loaded.
        Set<ChunkPos> next = chunks(ship.bounds().inflate(16).expandTowards(ship.velocity.scale(40)));
        Set<ChunkPos> old = WORLD_TICKETS.getOrDefault(ship.id, Set.of());
        for (ChunkPos cp : old) if (!next.contains(cp)) world.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        for (ChunkPos cp : next) world.getChunkSource().addRegionTicket(TICKET, cp, 2, ship.id);
        WORLD_TICKETS.put(ship.id, next);
    }
    public static void releaseTickets(MinecraftServer server, Ship ship) {
        ServerLevel yard = server.getLevel(SHIPYARD), world = server.getLevel(ship.dimension);
        if (yard != null) for (ChunkPos cp : chunks(new AABB(ship.yard).expandTowards(ship.tier.width, ship.tier.height, ship.tier.length))) yard.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        if (world != null) for (ChunkPos cp : WORLD_TICKETS.getOrDefault(ship.id, Set.of())) world.getChunkSource().removeRegionTicket(TICKET, cp, 2, ship.id);
        WORLD_TICKETS.remove(ship.id);
    }
    // No client class references in the dedicated-server class loader.
    public static final class ClientShipAccess {
        public static java.util.function.Function<Level, Collection<Ship>> provider = l -> List.of();
        static Collection<Ship> ships(Level level) { return provider.apply(level); }
    }
}
