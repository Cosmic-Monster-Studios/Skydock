package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import dev.skydock.Skydock;
import java.util.*;

/** Finds terminals to deliver to: those already known, plus outposts the structure placement predicts. */
public final class TerminalSites {
    public static final ResourceKey<Structure> STRUCTURE = ResourceKey.create(Registries.STRUCTURE, Skydock.id("cargo_outpost"));
    public static final ResourceKey<StructureSet> STRUCTURE_SET = ResourceKey.create(Registries.STRUCTURE_SET, Skydock.id("cargo_outposts"));
    private static final int MAX_REGIONS = 4096;
    /** Walk results, cleared when a terminal is registered or removed and when the server stops. */
    public static final SiteCache CACHE = new SiteCache();
    private TerminalSites() {}

    /**
     * Terminals whose horizontal distance from {@code from} is within {@code [min, max]}; predicted sites are recorded as ungenerated terminals.
     * A predicted site within {@link CargoService#SITE_MATCH} of a generated terminal is that terminal's own outpost, so it is never recorded or returned.
     * The walk is remembered in {@link #CACHE} for up to a day.
     */
    public static List<GlobalPos> near(ServerLevel level, BlockPos from, int min, int max) {
        return CACHE.get(GlobalPos.of(level.dimension(), from), min, max, level.getServer().overworld().getGameTime(), () -> walk(level, from, min, max));
    }
    public static void stopped(MinecraftServer server) { CACHE.clear(); }

    private static List<GlobalPos> walk(ServerLevel level, BlockPos from, int min, int max) {
        CargoSavedData data = CargoSavedData.get(level.getServer());
        Map<GlobalPos, CargoSavedData.Terminal> found = new LinkedHashMap<>();
        for (CargoSavedData.Terminal t : data.terminals.values())
            if (t.pos().dimension().equals(level.dimension()) && inBand(from, t.pos().pos(), min, max) && (t.generated() || !shadowed(data.terminals.values(), t.pos()))) found.put(t.pos(), t);
        Optional<Holder.Reference<StructureSet>> set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getHolder(STRUCTURE_SET);
        Optional<Holder.Reference<Structure>> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(STRUCTURE);
        if (set.isPresent() && structure.isPresent() && set.get().value().placement() instanceof RandomSpreadStructurePlacement placement) {
            boolean added = false;
            for (ChunkPos chunk : predicted(level, placement, from, max)) {
                BlockPos centre = new BlockPos(chunk.getMiddleBlockX(), level.getSeaLevel(), chunk.getMiddleBlockZ());
                GlobalPos pos = GlobalPos.of(level.dimension(), centre);
                if (found.containsKey(pos) || data.terminals.containsKey(pos) || !inBand(from, centre, min, max) || shadowed(data.terminals.values(), pos)) continue;
                if (level.structureManager().checkStructurePresence(chunk, structure.get().value(), placement, false) == StructureCheckResult.START_NOT_PRESENT) continue;
                CargoSavedData.Terminal site = new CargoSavedData.Terminal(pos, centre, false);
                data.terminals.put(pos, site); found.put(pos, site); added = true;
            }
            if (added) data.setDirty();
        }
        return new ArrayList<>(found.keySet());
    }

    private static List<ChunkPos> predicted(ServerLevel level, RandomSpreadStructurePlacement placement, BlockPos from, int max) {
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        int spacing = placement.spacing();
        int minRx = Math.floorDiv((from.getX() - max) >> 4, spacing), maxRx = Math.floorDiv((from.getX() + max) >> 4, spacing);
        int minRz = Math.floorDiv((from.getZ() - max) >> 4, spacing), maxRz = Math.floorDiv((from.getZ() + max) >> 4, spacing);
        List<ChunkPos> out = new ArrayList<>(); int regions = 0;
        for (int rx = minRx; rx <= maxRx; rx++)
            for (int rz = minRz; rz <= maxRz; rz++) {
                if (++regions > MAX_REGIONS) return out;
                out.add(placement.getPotentialStructureChunk(seed, rx, rz));
            }
        return out;
    }

    /** Whether a generated terminal stands within {@link CargoService#SITE_MATCH} blocks horizontally of {@code site} in its dimension. */
    static boolean shadowed(Collection<CargoSavedData.Terminal> terminals, GlobalPos site) {
        for (CargoSavedData.Terminal t : terminals) if (t.generated() && t.pos().dimension().equals(site.dimension()) && Offers.distance(t.pos().pos(), site.pos()) <= CargoService.SITE_MATCH) return true;
        return false;
    }

    private static boolean inBand(BlockPos from, BlockPos to, int min, int max) { double d = Offers.distance(from, to); return d >= min && d <= max; }
}
