package dev.skydock.cargo;

import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.skydock.Skydock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Cargo terminal protocol: the server pushes the player's profile, the client asks for actions that the server re-validates. */
public final class CargoNetwork {
    public static final int ACCEPT = 0, DELIVER = 1, ABANDON = 2, BUY = 3;
    public static Consumer<Sync> clientReceiver = ignored -> {};
    private CargoNetwork() {}

    /** Where an active contract's pods go: the destination terminal's dimension and pad. */
    public record Destination(long contract, ResourceKey<Level> dimension, BlockPos pad) {}

    /** The player's profile NBT (plus the rank ladder under {@code Ranks}) and each active contract's destination. */
    public record Sync(CompoundTag profile, List<Destination> destinations) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Skydock.id("cargo_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.of((buffer, packet) -> {
            buffer.writeNbt(packet.profile);
            buffer.writeVarInt(packet.destinations.size());
            for (Destination d : packet.destinations) { buffer.writeVarLong(d.contract); buffer.writeResourceKey(d.dimension); buffer.writeBlockPos(d.pad); }
        }, buffer -> {
            CompoundTag profile = buffer.readNbt(); int count = buffer.readVarInt(); List<Destination> out = new ArrayList<>();
            for (int i = 0; i < count; i++) out.add(new Destination(buffer.readVarLong(), buffer.readResourceKey(Registries.DIMENSION), buffer.readBlockPos()));
            return new Sync(profile == null ? new CompoundTag() : profile, out);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A button on the terminal screen; {@code id} is the offer, the contract or (for {@link #BUY}) the shop entry's {@link Shop#fingerprint}. */
    public record Action(int containerId, int action, long id) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Skydock.id("cargo_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of((buffer, packet) -> {
            buffer.writeVarInt(packet.containerId); buffer.writeVarInt(packet.action); buffer.writeVarLong(packet.id);
        }, buffer -> new Action(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void init() {
        if (Platform.getEnvironment() == Env.CLIENT) {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, Sync.TYPE, Sync.CODEC, (packet, context) -> context.queue(() -> clientReceiver.accept(packet)));
        } else NetworkManager.registerS2CPayloadType(Sync.TYPE, Sync.CODEC);
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, Action.TYPE, Action.CODEC, (packet, context) -> context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) handle(player, packet);
        }));
    }

    public static void send(int containerId, int action, long id) { NetworkManager.sendToServer(new Action(containerId, action, id)); }

    private static void handle(ServerPlayer player, Action action) {
        if (!(player.containerMenu instanceof TerminalMenu menu) || menu.containerId != action.containerId || !menu.stillValid(player)) return;
        GlobalPos terminal = GlobalPos.of(player.level().dimension(), menu.pos);
        switch (action.action) {
            case ACCEPT -> CargoService.accept(player, terminal, action.id);
            case DELIVER -> CargoService.deliver(player, terminal);
            case ABANDON -> CargoService.abandon(player, action.id);
            case BUY -> CargoService.buy(player, action.id);
            default -> { return; }
        }
        CargoService.sync(player);
    }

    public static void sync(ServerPlayer player, CompoundTag profile, List<Destination> destinations) { NetworkManager.sendToPlayer(player, new Sync(profile, destinations)); }
}
