package dev.skydock.mixin;

import dev.skydock.ship.ShipCollision;
import dev.skydock.ship.ShipManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerFlightMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Shadow private Vec3 awaitingPositionFromClient;
    /**
     * Whether a teleport was already pending when this packet arrived on the server thread, before vanilla ran. Read at the end
     * instead, it would also be set by the rubber-band teleport vanilla sends for a bad move, which is exactly when the deck needs correcting.
     */
    @Unique private boolean skydock$teleportPending;
    @ModifyVariable(method = "handleMovePlayer", argsOnly = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private ServerboundMovePlayerPacket skydock$rebaseDeckMove(ServerboundMovePlayerPacket packet) {
        skydock$teleportPending = awaitingPositionFromClient != null;
        return ShipManager.rebaseMovement(player, packet, skydock$teleportPending);
    }
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void skydock$deckIsGround(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ShipManager.finishMovement(player, skydock$teleportPending);
        var ship = ShipManager.attachedShip(player);
        if (ship != null && ShipCollision.aboveHull(ship, ship.pose, player.getBoundingBox())) clientIsFloating = false;
    }
}
