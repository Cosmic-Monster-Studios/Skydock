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
        dev.skydock.cargo.CargoNetwork.init();
        dev.architectury.event.events.common.PlayerEvent.PLAYER_JOIN.register(dev.skydock.cargo.CargoService::sync);
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new MassTable(), id("mass"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new PatternLoader(), id("patterns"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new dev.skydock.cargo.CargoRankLoader(), id("cargo_ranks"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new dev.skydock.cargo.CargoNames(), id("cargo_names"));
        ReloadListenerRegistry.register(PackType.SERVER_DATA, new dev.skydock.cargo.ShopLoader(), id("cargo_shop"));
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> SkydockCommands.register(dispatcher));
        LifecycleEvent.SERVER_STARTED.register(ShipManager::start);
        LifecycleEvent.SERVER_STARTED.register(dev.skydock.cargo.Shop::started);
        LifecycleEvent.SERVER_STOPPED.register(dev.skydock.cargo.Shop::stopped);
        LifecycleEvent.SERVER_STOPPED.register(dev.skydock.cargo.TerminalSites::stopped);
        LifecycleEvent.SERVER_STOPPED.register(ShipManager::stop);
        LifecycleEvent.SERVER_STOPPED.register(AssemblyManager::stop);
        TickEvent.SERVER_PRE.register(ShipManager::tick);
        TickEvent.SERVER_PRE.register(dev.skydock.cargo.CargoSavedData::tickWind);
        TickEvent.SERVER_PRE.register(dev.skydock.cargo.CargoService::tickExpiry);
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            if (level.dimension().equals(ShipManager.SHIPYARD)) return EventResult.interruptFalse();
            if (guarded(level, pos)) return EventResult.interruptFalse();
            // A pod in a redocked hull belongs to its contract holder, as it does aboard a ship.
            if (state.is(SkydockBlocks.CARGO_POD.get()) && player != null
                    && !dev.skydock.cargo.PodLoading.breakable(player, level.getBlockEntity(pos) instanceof dev.skydock.cargo.CargoPodBlockEntity pod ? pod : null)) {
                ShipManager.tell(player, "message.skydock.pod_not_yours");
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
        BlockEvent.PLACE.register((level, pos, state, placer) -> {
            if (dev.skydock.ship.ShipTransfer.changing) return EventResult.pass();
            if (level.dimension().equals(ShipManager.SHIPYARD)
                    || guarded(level, pos)
                    // The occupied hull, not the whole dock envelope, so players can still build beside a moored ship.
                    || ShipManager.ships(level).stream().anyMatch(s -> s.hullWorldBounds().intersects(new net.minecraft.world.phys.AABB(pos))))
                return EventResult.interruptFalse();
            return EventResult.pass();
        });
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (guarded(player.level(), pos)) return EventResult.interruptFalse();
            return EventResult.pass();
        });
    }
    /** Cells of an assembly in progress, and the berth of a paused transfer, which no player may break, place in or use. */
    private static boolean guarded(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        return level instanceof net.minecraft.server.level.ServerLevel serverLevel && (AssemblyManager.protects(serverLevel, pos) || ShipManager.transferProtects(serverLevel, pos));
    }
}
