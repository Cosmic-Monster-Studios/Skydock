package dev.skydock;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.EventResult;
import dev.architectury.registry.ReloadListenerRegistry;
import dev.skydock.block.SkydockBlocks;
import dev.skydock.command.SkydockCommands;
import dev.skydock.data.MassTable;
import dev.skydock.data.PatternLoader;
import dev.skydock.network.ShipNetwork;
import dev.skydock.network.DockNetwork;
import dev.skydock.assembly.AssemblyManager;
import dev.skydock.ship.ShipManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Skydock {
    public static final String ID = "skydock";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }

    public static void init() {
        SkydockBlocks.init();
        dev.skydock.ship.ShipDamage.init();
        ShipNetwork.init();
        DockNetwork.init();
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new MassTable(), id("mass"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new PatternLoader(), id("patterns"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new dev.skydock.cargo.CargoRankLoader(), id("cargo_ranks"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new dev.skydock.cargo.CargoNames(), id("cargo_names"));
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> SkydockCommands.register(dispatcher));
        LifecycleEvent.SERVER_STARTED.register(ShipManager::start);
        LifecycleEvent.SERVER_STOPPED.register(ShipManager::stop);
        LifecycleEvent.SERVER_STOPPED.register(AssemblyManager::stop);
        TickEvent.SERVER_PRE.register(ShipManager::tick);
        TickEvent.SERVER_PRE.register(dev.skydock.cargo.CargoSavedData::tickWind);
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            if (level.dimension().equals(ShipManager.SHIPYARD)) return EventResult.interruptFalse();
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel && AssemblyManager.protects(serverLevel, pos))
                return EventResult.interruptFalse();
            return EventResult.pass();
        });
        BlockEvent.PLACE.register((level, pos, state, placer) -> {
            if (dev.skydock.ship.ShipTransfer.changing) return EventResult.pass();
            if (level.dimension().equals(ShipManager.SHIPYARD)
                    || level instanceof net.minecraft.server.level.ServerLevel serverLevel && AssemblyManager.protects(serverLevel, pos)
                    // The occupied hull, not the whole dock envelope, so players can still build beside a moored ship.
                    || ShipManager.ships(level).stream().anyMatch(s -> s.hullWorldBounds().intersects(new net.minecraft.world.phys.AABB(pos))))
                return EventResult.interruptFalse();
            return EventResult.pass();
        });
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel && AssemblyManager.protects(serverLevel, pos))
                return EventResult.interruptFalse();
            return EventResult.pass();
        });
    }
}
