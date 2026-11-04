package dev.skydock.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternJsonTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("skydock", "test_hull");
    /** A lectern stands in for the helm (as in the test catalog palette), which needs Skydock's registry. */
    private static final Predicate<BlockState> HELM = state -> state.is(Blocks.LECTERN);
    private static final Function<String, BlockState> STATES = s -> {
        try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), s, false).blockState(); }
        catch (CommandSyntaxException ex) { throw new IllegalArgumentException(ex.getMessage()); }
    };

    @BeforeAll static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static final String VALID = """
            {
              "name": "Little Skiff", "description": "pattern.skydock.saved.description", "tier": "scout",
              "palette": { "p": "minecraft:oak_planks", "h": "minecraft:lectern",
                           "s": "minecraft:oak_stairs[facing=east]", "l": "minecraft:lantern" },
              "decorations": ["l"],
              "layers": [
                ["pp", ".p"],
                ["h ", "sl"]
              ]
            }""";

    private static ShipPattern parse(String text) { return PatternJson.parse(ID, json(text), STATES, HELM); }

    @Test void parsesLayersPaletteAndDecorations() {
        ShipPattern pattern = parse(VALID);
        assertEquals(ID, pattern.id());
        assertEquals("Little Skiff", pattern.name());
        assertEquals("pattern.skydock.saved.description", pattern.description());
        assertEquals(DockTier.SCOUT, pattern.tier());
        assertEquals(6, pattern.blocks().size());
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), at(pattern, 0, 0, 0).state());
        assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), at(pattern, 1, 0, 1).state());
        assertEquals(Blocks.LECTERN.defaultBlockState(), at(pattern, 0, 1, 0).state());
        BlockState stair = at(pattern, 0, 1, 1).state();
        assertTrue(stair.is(Blocks.OAK_STAIRS));
        assertEquals(Direction.EAST, stair.getValue(StairBlock.FACING));
        assertTrue(at(pattern, 1, 1, 1).decoration());
        assertFalse(at(pattern, 0, 0, 0).decoration());
    }

    @Test void spaceAndDotAreAir() {
        ShipPattern pattern = parse(VALID);
        assertTrue(pattern.blocks().stream().noneMatch(cell -> cell.pos().equals(new BlockPos(0, 0, 1))), "dot");
        assertTrue(pattern.blocks().stream().noneMatch(cell -> cell.pos().equals(new BlockPos(1, 1, 0))), "space");
    }

    @Test void invalidFileIsRejectedWhole() {
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"sl\"", "\"sq\"")), "unknown palette character");
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\".p\"", "\"" + "p".repeat(DockTier.SCOUT.width + 1) + "\"")), "row wider than the dock");
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"h \"", "\"p \"")), "no helm");
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"h \"", "\"hh\"")), "two helms");
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("minecraft:lantern", "minecraft:no_such_block")), "unknown block");
        assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"scout\"", "\"galleon\"")), "unknown tier");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(VALID.replace("\"sl\"", "\"sq\"")));
        assertTrue(error.getMessage().contains("'q'"), error.getMessage());
    }

    @Test void writeThenParseRoundTrips() {
        ShipPattern pattern = parse(VALID);
        JsonObject written = PatternJson.write(pattern, BlockStateParser::serialize);
        assertEquals(pattern, PatternJson.parse(ID, written, STATES, HELM));
        for (String key : written.getAsJsonObject("palette").keySet())
            assertTrue(key.length() == 1 && Character.isLetterOrDigit(key.charAt(0)), key);
    }

    @Test void unlockShopRoundTrips() {
        ShipPattern shop = parse(VALID.replace("\"tier\": \"scout\",", "\"tier\": \"scout\", \"unlock\": \"shop\","));
        assertTrue(shop.shopOnly());
        assertFalse(parse(VALID).shopOnly());
        JsonObject written = PatternJson.write(shop, BlockStateParser::serialize);
        assertEquals("shop", written.get("unlock").getAsString());
        assertEquals(shop, PatternJson.parse(ID, written, STATES, HELM));
        assertFalse(PatternJson.write(parse(VALID), BlockStateParser::serialize).has("unlock"), "a free pattern writes no unlock");
    }

    @Test void unknownUnlockValueIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse(VALID.replace("\"tier\": \"scout\",", "\"tier\": \"scout\", \"unlock\": \"quest\",")));
        assertTrue(error.getMessage().contains("quest"), error.getMessage());
    }

    @Test void hullsWithManyDistinctStatesRoundTrip() {
        java.util.List<BlockState> states = new java.util.ArrayList<>();
        for (Block stairs : new Block[] {Blocks.OAK_STAIRS, Blocks.STONE_STAIRS, Blocks.BRICK_STAIRS})
            for (BlockState state : stairs.getStateDefinition().getPossibleStates()) states.add(state);
        for (BlockState state : Blocks.OAK_FENCE.getStateDefinition().getPossibleStates()) states.add(state);
        for (BlockState state : Blocks.BIRCH_FENCE.getStateDefinition().getPossibleStates()) states.add(state);
        states.addAll(BuiltInRegistries.BLOCK.stream().map(Block::defaultBlockState).limit(120).toList());
        java.util.List<BlockState> distinct = states.stream().distinct().limit(250).toList();
        assertTrue(distinct.size() > 200, "need more than 200 distinct states, have " + distinct.size());
        java.util.List<ShipPattern.Block> cells = new java.util.ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) cells.add(new ShipPattern.Block(new BlockPos(i % 16, 0, i / 16), distinct.get(i), false));
        cells.set(0, new ShipPattern.Block(BlockPos.ZERO, Blocks.LECTERN.defaultBlockState(), false));
        ShipPattern pattern = new ShipPattern(ID, "big", "d", DockTier.SCOUT, cells);
        JsonObject written = PatternJson.write(pattern, BlockStateParser::serialize);
        assertTrue(written.getAsJsonObject("palette").size() > 200);
        String text = new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(written);
        ShipPattern parsed = PatternJson.parse(ID, JsonParser.parseString(text).getAsJsonObject(), STATES, HELM);
        assertEquals(Set.copyOf(pattern.blocks()), Set.copyOf(parsed.blocks()));
    }

    @Test void builtInCatalogRoundTrips() {
        for (ShipPattern pattern : ShipPatterns.createTestCatalog()) {
            ShipPattern parsed = PatternJson.parse(pattern.id(), PatternJson.write(pattern, BlockStateParser::serialize), STATES, HELM);
            assertEquals(pattern.name(), parsed.name());
            assertEquals(pattern.tier(), parsed.tier());
            assertEquals(Set.copyOf(pattern.blocks()), Set.copyOf(parsed.blocks()), pattern.id().toString());
        }
    }

    private static ShipPattern.Block at(ShipPattern pattern, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return pattern.blocks().stream().filter(cell -> cell.pos().equals(pos)).findFirst().orElseThrow(() -> new AssertionError("no cell at " + pos));
    }
}
