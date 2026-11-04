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
    @ModifyVariable(method = "handleMovePlayer", argsOnly = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private ServerboundMovePlayerPacket skydock$rebaseDeckMove(ServerboundMovePlayerPacket packet) {
        return ShipManager.rebaseMovement(player, packet);
    }
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void skydock$deckIsGround(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ShipManager.finishMovement(player, awaitingPositionFromClient != null);
        var ship = ShipManager.attachedShip(player);
        if (ship != null && ShipCollision.aboveHull(ship, ship.pose, player.getBoundingBox())) clientIsFloating = false;
    }
}
