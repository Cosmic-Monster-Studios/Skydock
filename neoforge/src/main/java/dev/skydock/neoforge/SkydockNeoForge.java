package dev.skydock.neoforge;

import dev.skydock.Skydock;
import net.neoforged.fml.common.Mod;

@Mod(Skydock.ID)
public final class SkydockNeoForge {
    public SkydockNeoForge() {
        Skydock.init();
        // Drain NeoForge's queued SavedData writes first so none can land after the journal, then use its durable atomic write.
        dev.skydock.ship.ShipTransfer.journalWriter = (tag, file) -> {
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed(tag, file);
        };
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) SkydockNeoForgeClient.init();
    }
}
