package dev.skydock.data;

import com.google.gson.*;
import dev.skydock.fuzz.Fuzz;
import dev.skydock.fuzz.JsonFuzz;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Ship pattern files and mass rules: random valid files round trip, damaged files are rejected with the loader's exception type. */
class DataParsingFuzzTest {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("skydock", "fuzz_hull");
    static final Predicate<BlockState> HELM = s -> s.is(Blocks.LECTERN);
    static List<Block> buildable;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        buildable = BuiltInRegistries.BLOCK.stream().filter(b -> b != Blocks.LECTERN && !b.defaultBlockState().isAir() && Item.byBlock(b) != Items.AIR).toList();
    }
    @AfterAll static void restoreMassRules() { new MassTable().apply(Map.of(), null, InactiveProfiler.INSTANCE); }

    static ShipPattern pattern(Random r) {
        DockTier tier = Fuzz.pick(r, DockTier.values()); Set<BlockPos> used = new HashSet<>(); List<ShipPattern.Block> cells = new ArrayList<>();
        int kinds = 1 + r.nextInt(r.nextInt(10) == 0 ? 300 : 12); List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < kinds; i++) { Block b = Fuzz.pick(r, buildable); palette.add(Fuzz.pick(r, b.getStateDefinition().getPossibleStates())); }
        BlockPos helm = new BlockPos(r.nextInt(tier.width), r.nextInt(tier.height), r.nextInt(tier.length)); used.add(helm);
        cells.add(new ShipPattern.Block(helm, Blocks.LECTERN.defaultBlockState(), false));
        for (int i = r.nextInt(r.nextInt(8) == 0 ? 3000 : 120); i > 0; i--) {
            BlockPos p = r.nextInt(30) == 0 ? new BlockPos(tier.width - 1, tier.height - 1, tier.length - 1) : new BlockPos(r.nextInt(tier.width), r.nextInt(tier.height), r.nextInt(tier.length));
            if (used.add(p)) cells.add(new ShipPattern.Block(p, Fuzz.pick(r, palette), r.nextInt(5) == 0));
        }
        Collections.shuffle(cells, r);
        String name = Fuzz.pick(r, "ship.skydock.fuzz", "Plain Name", "Ünïcødé \"quoted\" \\ name", "x".repeat(1 + r.nextInt(200)));
        return new ShipPattern(ID, name, Fuzz.pick(r, "", "desc", "multi\nline"), tier, cells, r.nextBoolean());
    }

    static void assertSamePattern(ShipPattern a, ShipPattern b) {
        assertEquals(a.id(), b.id()); assertEquals(a.name(), b.name()); assertEquals(a.description(), b.description());
        assertEquals(a.tier(), b.tier()); assertEquals(a.shopOnly(), b.shopOnly());
        assertEquals(new HashSet<>(a.blocks()), new HashSet<>(b.blocks()), "same cells, states and decoration flags");
        assertEquals(a.blocks().size(), b.blocks().size());
    }

    @Test void writtenPatternsParseBackExactly() {
        Fuzz.check("PatternJson round trip", 1500, (r, log) -> {
            ShipPattern p = pattern(r); log.append(p.tier()).append(" cells=").append(p.blocks().size());
            JsonObject json = PatternJson.write(p, BlockStateParser::serialize);
            // through text too, as a datapack file would be
            JsonObject reread = JsonParser.parseString(new Gson().toJson(json)).getAsJsonObject();
            ShipPattern back = PatternJson.parse(ID, reread, PatternJson::state, HELM);
            assertSamePattern(p, back);
            assertEquals(p.cost(true), back.cost(true)); assertEquals(p.stats(false), back.stats(false));
            assertSamePattern(p, PatternJson.parse(ID, PatternJson.write(back, BlockStateParser::serialize), PatternJson::state, HELM));
        });
    }

    @Test void damagedPatternFilesAreRejectedWithIllegalArgumentException() {
        Map<String, Integer> outcomes = new TreeMap<>();
        Fuzz.check("PatternJson damaged", 6000, (r, log) -> {
            ShipPattern p = pattern(r);
            JsonElement bad = JsonFuzz.mutate(r, PatternJson.write(p, BlockStateParser::serialize), Fuzz.pick(r, .01, .05, .2), log);
            if (!bad.isJsonObject()) return; // the loader refuses non-objects itself
            try {
                ShipPattern back = PatternJson.parse(ID, bad.getAsJsonObject(), PatternJson::state, HELM);
                outcomes.merge("parsed", 1, Integer::sum);
                assertFalse(back.name().isBlank()); assertFalse(back.blocks().isEmpty());
                assertEquals(1, back.blocks().stream().filter(c -> HELM.test(c.state())).count(), "exactly one helm");
                for (ShipPattern.Block c : back.blocks()) {
                    BlockPos q = c.pos(); DockTier t = back.tier();
                    assertTrue(q.getX() >= 0 && q.getY() >= 0 && q.getZ() >= 0 && q.getX() < t.width && q.getY() < t.height && q.getZ() < t.length, "cell inside the dock");
                    assertFalse(c.state().isAir());
                }
                back.stats(true); back.cost(false);
            } catch (IllegalArgumentException expected) { outcomes.merge("rejected", 1, Integer::sum); }
        });
        assertTrue(outcomes.getOrDefault("rejected", 0) > 1000 && outcomes.getOrDefault("parsed", 0) > 0, outcomes.toString());
    }

    @Test void randomJsonTreesAreRejectedWithIllegalArgumentException() {
        Fuzz.check("PatternJson random trees", 6000, (r, log) -> {
            JsonObject json = new JsonObject();
            for (String key : List.of("name", "description", "tier", "unlock", "palette", "decorations", "layers")) if (r.nextInt(4) != 0) json.add(key, JsonFuzz.randomJson(r, 0));
            if (r.nextBoolean()) json.addProperty("tier", Fuzz.pick(r, "scout", "brig", "SCOUT", ""));
            if (r.nextBoolean()) { JsonObject palette = new JsonObject(); palette.addProperty("h", "minecraft:lectern"); palette.add(Fuzz.pick(r, "s", "..", " ", "", "\uD83D"), JsonFuzz.randomJson(r, 1)); json.add("palette", palette); }
            log.append(json);
            try { PatternJson.parse(ID, json, PatternJson::state, HELM); } catch (IllegalArgumentException expected) {}
        });
    }

    // ---------------------------------------------------------------- mass rules

    static JsonObject massFile(Random r) {
        JsonObject o = new JsonObject();
        if (r.nextBoolean()) o.addProperty("default_mass", 1 + r.nextInt(100));
        if (r.nextBoolean()) o.addProperty("lift_per_cell", 100 + r.nextInt(2000));
        if (r.nextBoolean()) o.addProperty("blocks_per_cell", 1 + r.nextInt(500));
        JsonObject blocks = new JsonObject(); for (int i = r.nextInt(4); i > 0; i--) blocks.addProperty(BuiltInRegistries.BLOCK.getKey(Fuzz.pick(r, buildable)).toString(), 1 + r.nextInt(400)); o.add("blocks", blocks);
        JsonObject caps = new JsonObject(); for (DockTier t : DockTier.values()) if (r.nextBoolean()) caps.addProperty(t.key(), 1 + r.nextInt(30000)); o.add("dock_caps", caps);
        JsonObject collision = new JsonObject(); collision.addProperty("min_speed", 1 + r.nextInt(5)); collision.addProperty("max_blocks", r.nextInt(100)); collision.addProperty("damage_other_ships", r.nextBoolean()); o.add("collision", collision);
        return o;
    }

    static void assertSaneMassRules() {
        assertTrue(MassTable.blocksPerCell() >= 1 && MassTable.blocksPerCell() <= 24576, "blocks per cell " + MassTable.blocksPerCell());
        assertTrue(Double.isFinite(MassTable.liftPerCell()) && MassTable.liftPerCell() > 0);
        for (DockTier t : DockTier.values()) assertTrue(MassTable.cap(t) >= 1 && MassTable.cap(t) <= 24576, "cap " + t + " " + MassTable.cap(t));
        for (Block b : List.of(Blocks.STONE, Blocks.OAK_PLANKS, Blocks.IRON_BLOCK)) { double m = MassTable.mass(b.defaultBlockState()); assertTrue(Double.isFinite(m) && m > 0); }
        CollisionRules c = MassTable.collision();
        assertTrue(c.minSpeed() > 0 && c.speedPerHardness() > 0 && c.blocksPerSpeed() >= 0 && c.maxBlocks() >= 0 && c.maxBlocks() <= 4096 && c.entityDamagePerSpeed() >= 0);
    }

    @Test void damagedMassRulesNeverBreakAReload() {
        MassTable table = new MassTable();
        Fuzz.check("MassTable damaged", 4000, (r, log) -> {
            Map<ResourceLocation, JsonElement> files = new HashMap<>();
            for (int i = 1 + r.nextInt(3); i > 0; i--) {
                JsonElement f = massFile(r); if (r.nextInt(3) != 0) f = JsonFuzz.mutate(r, f, Fuzz.pick(r, .05, .2, .5), log);
                files.put(ResourceLocation.fromNamespaceAndPath(Fuzz.pick(r, "a", "skydock", "z"), "f" + i), f);
            }
            table.apply(files, null, InactiveProfiler.INSTANCE);
            assertSaneMassRules();
        });
    }

    @Test void collisionOverridesAreValidatedOrRejected() {
        Fuzz.check("CollisionRules.parse", 6000, (r, log) -> {
            JsonObject json = new JsonObject();
            for (String k : List.of("min_speed", "speed_per_hardness", "blocks_per_speed", "max_blocks", "entity_damage_per_speed", "damage_other_ships")) if (r.nextBoolean()) json.add(k, r.nextBoolean() ? JsonFuzz.number(r) : JsonFuzz.randomJson(r, 1));
            log.append(json);
            CollisionRules c;
            try { c = CollisionRules.parse(json, CollisionRules.DEFAULT); } catch (RuntimeException rejected) { return; }
            assertTrue(c.minSpeed() > 0 && c.minSpeed() <= 1e6 && c.speedPerHardness() > 0 && c.blocksPerSpeed() >= 0 && c.maxBlocks() >= 0 && c.maxBlocks() <= 4096 && c.entityDamagePerSpeed() >= 0, c.toString());
        });
    }
}
