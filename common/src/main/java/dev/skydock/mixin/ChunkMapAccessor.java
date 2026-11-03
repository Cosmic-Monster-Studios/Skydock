package dev.skydock.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /** Queues one chunk's write to region storage, whether or not the autosave sweep would reach it. */
    @Invoker("save") boolean skydock$save(ChunkAccess chunk);
}
