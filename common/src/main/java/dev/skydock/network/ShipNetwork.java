package dev.skydock.network;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.skydock.Skydock;
import dev.skydock.ship.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Consumer;

public final class ShipNetwork {
    public static Consumer<CompoundTag> clientReceiver = tag -> {};
    private static final Map<UUID, Map<UUID, Integer>> sent = new HashMap<>();
    /** Hull payloads are built once per revision and shared by every player that needs them. */
    private record HullParts(int revision, long built, List<ListTag> parts) {}
    private static final Map<UUID, HullParts> hulls = new HashMap<>();
    private record PoseSent(CompoundTag message, long tick) {}
    private static final Map<UUID, PoseSent> poses = new HashMap<>();
    private static final Map<UUID, Long> hellos = new HashMap<>();
    private static final double TRACK_RANGE = 256, UNTRACK_RANGE = 288;
    private static long deckMoveSequence;

    public record Snapshot(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Skydock.id("snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, p) -> b.writeNbt(p.data), b -> new Snapshot(b.readNbt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Input(int action, float thrust, float yaw, float vertical) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(Skydock.id("input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarInt(p.action); b.writeFloat(p.thrust); b.writeFloat(p.yaw); b.writeFloat(p.vertical);
        }, b -> new Input(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Use(UUID ship, double tick, Vec3 eye, Vec3 ray) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Skydock.id("use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Use> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.ship); b.writeDouble(p.tick);
            b.writeDouble(p.eye.x); b.writeDouble(p.eye.y); b.writeDouble(p.eye.z);
            b.writeDouble(p.ray.x); b.writeDouble(p.ray.y); b.writeDouble(p.ray.z);
        }, b -> new Use(b.readUUID(), b.readDouble(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readDouble(), b.readDouble(), b.readDouble())));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record BoardReady(UUID ship) implements CustomPacketPayload {
        public static final Type<BoardReady> TYPE = new Type<>(Skydock.id("board_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BoardReady> CODEC = StreamCodec.of((b, p) -> b.writeUUID(p.ship), b -> new BoardReady(b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record DeckMove(long sequence, UUID ship, double shipTime, Vec3 localFeet, boolean departing) implements CustomPacketPayload {
        public static final Type<DeckMove> TYPE = new Type<>(Skydock.id("deck_move"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeckMove> CODEC = StreamCodec.of((b, p) -> {
            b.writeVarLong(p.sequence); b.writeBoolean(p.ship != null);
            if (p.ship != null) {
                b.writeUUID(p.ship); b.writeDouble(p.shipTime);
                b.writeDouble(p.localFeet.x); b.writeDouble(p.localFeet.y); b.writeDouble(p.localFeet.z);
                b.writeBoolean(p.departing);
            }
        }, b -> {
            long sequence = b.readVarLong();
            if (!b.readBoolean()) return new DeckMove(sequence, null, 0, Vec3.ZERO, false);
            return new DeckMove(sequence, b.readUUID(), b.readDouble(),
                    new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readBoolean());
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) NetworkManager.registerReceiver(NetworkManager.Side.S2C, Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.queue(() -> clientReceiver.accept(packet.data)));
        else NetworkManager.registerS2CPayloadType(Snapshot.TYPE, Snapshot.CODEC);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BoardReady.TYPE, BoardReady.CODEC, (packet, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) ShipManager.completeBoarding(player, packet.ship);
        }));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, DeckMove.TYPE, DeckMove.CODEC, (packet, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player)
                ShipManager.receiveMovementFrame(player, new ShipMovementFrame.Frame(packet.sequence, packet.ship,
                        packet.shipTime, packet.localFeet, packet.departing));
        }));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, Use.TYPE, Use.CODEC, (packet, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator())
                ShipInteractions.useViewed(player, packet.ship, packet.tick, packet.eye, packet.ray);
        }));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, Input.TYPE, Input.CODEC, (packet, context) -> context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player) || !player.isAlive() || player.isSpectator()) return;
            switch (packet.action) {
                case 0 -> ShipManager.control(player, packet.thrust, packet.yaw, packet.vertical);
                case 1 -> ShipInteractions.useFromLook(player);
                case 2 -> ShipManager.release(player);
                case 3 -> {
                    // The client says hello once per world join; a flood of them must not resend every hull.
                    long now = player.serverLevel().getGameTime();
                    Long last = hellos.put(player.getUUID(), now);
                    if (last != null && now - last < 40) return;
                    ShipManager.prepareBoarding(player); sent.remove(player.getUUID()); syncPlayer(player);
                }
                case 4 -> ShipManager.toggleCruise(player);
            }
        }));
    }
    private static void send(ServerPlayer player, CompoundTag data) { NetworkManager.sendToPlayer(player, new Snapshot(data)); }
    public static void input(int action, float thrust, float yaw, float vertical) { NetworkManager.sendToServer(new Input(action, thrust, yaw, vertical)); }
    public static void use(UUID ship, double tick, Vec3 eye, Vec3 ray) { NetworkManager.sendToServer(new Use(ship, tick, eye, ray)); }
    public static void boardReady(UUID ship) { NetworkManager.sendToServer(new BoardReady(ship)); }
    public static void deckMove(ShipMovementFrame.Frame frame) {
        frame = frame.withSequence(++deckMoveSequence);
        NetworkManager.sendToServer(new DeckMove(frame.sequence(), frame.ship(), frame.shipTime(), frame.localFeet(), frame.departing()));
    }
    public static void reset() { sent.clear(); hulls.clear(); poses.clear(); hellos.clear(); }
    public static void correctDeck(ServerPlayer player, Ship ship, Vec3 localFeet) {
        CompoundTag msg = new CompoundTag();
        msg.putString("Kind", "deck_correction"); msg.putUUID("Id", ship.id);
        msg.putDouble("LocalX", localFeet.x); msg.putDouble("LocalY", localFeet.y); msg.putDouble("LocalZ", localFeet.z);
        send(player, msg);
    }
    public static void resync(MinecraftServer server) { sent.clear(); syncPlayers(server); }
    public static void syncPlayers(MinecraftServer server) {
        sent.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        hellos.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        Set<UUID> fleet = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) for (Ship ship : ShipManager.ships(level)) fleet.add(ship.id);
        hulls.keySet().retainAll(fleet); poses.keySet().retainAll(fleet);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) syncPlayer(player);
    }
    private static void syncPlayer(ServerPlayer player) {
        Map<UUID, Integer> seen = sent.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        ListTag manifest = new ListTag(); Set<UUID> active = new HashSet<>();
        for (Ship ship : ShipManager.ships(player.level())) {
            // A wider release range keeps a player on the boundary from receiving the whole hull again and again.
            double range = seen.containsKey(ship.id) ? UNTRACK_RANGE : TRACK_RANGE;
            if (ship.bounds().distanceToSqr(player.position()) > range * range) continue;
            ShipManager.wake(player.getServer(), ship);
            active.add(ship.id); CompoundTag id = new CompoundTag(); id.putUUID("Id", ship.id); manifest.add(id);
            if (!Objects.equals(seen.get(ship.id), ship.revision)) { sendShip(player, ship); seen.put(ship.id, ship.revision); }
        }
        seen.keySet().retainAll(active);
        CompoundTag msg = new CompoundTag(); msg.putString("Kind", "manifest"); msg.put("Ships", manifest); send(player, msg);
    }
    private static void sendShip(ServerPlayer player, Ship ship) {
        List<ListTag> cells = hullParts(player.getServer(), ship);
        int parts = cells.size();
        for (int part = 0; part < parts; part++) {
            CompoundTag msg = ship.save(player.registryAccess(), false);
            ShipManager.Boarding boarding = ShipManager.boarding(player);
            if (boarding != null && boarding.ship().equals(ship.id)) {
                msg.putBoolean("Boarding", true);
                msg.putDouble("BoardX", boarding.localFeet().x); msg.putDouble("BoardY", boarding.localFeet().y); msg.putDouble("BoardZ", boarding.localFeet().z);
            }
            // Hull refreshes can run before this tick's physics; stamp the pose, not the send time.
            msg.putLong("Tick", ship.history.latestTick(player.getServer().overworld().getGameTime()));
            msg.putString("Kind", "blocks"); msg.putInt("Part", part); msg.putInt("Parts", parts);
            msg.put("CellsData", cells.get(part)); send(player, msg);
        }
    }
    /** Cell lists in 256-block parts. Block-entity visuals can change without a revision, so a cached copy also ages out. */
    private static List<ListTag> hullParts(MinecraftServer server, Ship ship) {
        long now = server.overworld().getGameTime();
        HullParts cached = hulls.get(ship.id);
        if (cached != null && cached.revision == ship.revision && now - cached.built < 200) return cached.parts;
        List<Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> blocks = new ArrayList<>(ship.blocks.entrySet());
        var yard = server.getLevel(ShipManager.SHIPYARD);
        List<ListTag> parts = new ArrayList<>();
        for (int part = 0; part < Math.max(1, (blocks.size() + 255) / 256); part++) {
            ListTag list = new ListTag();
            for (int i = part * 256; i < Math.min(blocks.size(), (part + 1) * 256); i++) {
                var e = blocks.get(i); CompoundTag entry = new CompoundTag();
                entry.putLong("Pos", e.getKey().asLong()); entry.putInt("State", Block.getId(e.getValue()));
                var be = yard == null ? null : yard.getBlockEntity(ship.yard.offset(e.getKey()));
                if (be != null) entry.put("Data", be.getUpdateTag(server.registryAccess()));
                list.add(entry);
            }
            parts.add(list);
        }
        hulls.put(ship.id, new HullParts(ship.revision, now, List.copyOf(parts)));
        return parts;
    }
    public static void syncPose(MinecraftServer server, Ship ship) { syncPose(server, ship, true); }
    /** With {@code always} false, an unchanged ship only sends a heartbeat once a second instead of ten poses. */
    public static void syncPose(MinecraftServer server, Ship ship, boolean always) {
        CompoundTag msg = ship.save(server.registryAccess(), false); msg.putString("Kind", "pose");
        ListTag seats = new ListTag(); ship.seated.forEach((id, p) -> { CompoundTag tag = new CompoundTag(); tag.putUUID("Player", id); tag.putLong("Pos", p.asLong()); seats.add(tag); }); msg.put("Seated", seats);
        long now = server.overworld().getGameTime();
        PoseSent last = poses.get(ship.id);
        if (!always && last != null && now - last.tick < 20 && last.message.equals(msg)) return;
        poses.put(ship.id, new PoseSent(msg.copy(), now));
        msg.putLong("Tick", ship.history.latestTick(now));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.level().dimension().equals(ship.dimension) && sent.getOrDefault(player.getUUID(), Map.of()).containsKey(ship.id)) send(player, msg);
    }
}
