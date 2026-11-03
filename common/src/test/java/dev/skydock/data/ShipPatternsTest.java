package dev.skydock.data;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipPatternsTest {
    private static final Map<DockTier, Dimensions> DIMENSIONS = Map.of(
            DockTier.SCOUT, new Dimensions(11, 9, 19),
            DockTier.BRIG, new Dimensions(17, 12, 27),
            DockTier.CRUISER, new Dimensions(23, 15, 35),
            DockTier.DREADNOUGHT, new Dimensions(29, 18, 43));
    private static final Map<String, Integer> LIFT_CELLS = Map.ofEntries(
            Map.entry("scout_cutter", 112), Map.entry("scout_twinhull", 102), Map.entry("scout_hauler", 92),
            Map.entry("brig_cutter", 317), Map.entry("brig_twinhull", 324), Map.entry("brig_hauler", 312),
            Map.entry("cruiser_cutter", 676), Map.entry("cruiser_twinhull", 590), Map.entry("cruiser_hauler", 440),
            Map.entry("dreadnought_cutter", 1200), Map.entry("dreadnought_twinhull", 1052), Map.entry("dreadnought_hauler", 704));

    @BeforeAll static void bootstrapVanillaRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<ShipPattern> catalog() { return ShipPatterns.createTestCatalog(); }

    @Test void catalogHasThreeStableAndDistinctChoicesPerTier() {
        List<ShipPattern> catalog = catalog();
        assertEquals(12, catalog.size());
        assertEquals(12, catalog.stream().map(ShipPattern::id).distinct().count());
        assertEquals(12, catalog.stream().map(ShipPattern::name).distinct().count());
        for (DockTier tier : DockTier.values()) {
            List<ShipPattern> tierPatterns = catalog.stream().filter(pattern -> pattern.tier() == tier).toList();
            assertEquals(3, tierPatterns.size(), tier.key());
            Set<Set<BlockPos>> silhouettes = tierPatterns.stream()
                    .map(pattern -> pattern.included(false).stream().map(ShipPattern.Block::pos).collect(Collectors.toSet()))
                    .collect(Collectors.toSet());
            assertEquals(3, silhouettes.size(), tier.key() + " silhouettes");
        }
    }

    @Test void dataPatternReplacesBuiltInInPlaceAndNewOnesFollow() {
        List<ShipPattern> builtIns = catalog();
        ShipPattern first = builtIns.getFirst();
        ShipPattern replacement = new ShipPattern(first.id(), "Replaced", "", first.tier(), builtIns.get(1).blocks());
        ShipPattern extra = new ShipPattern(net.minecraft.resources.ResourceLocation.parse("pack:extra"), "Extra", "", DockTier.SCOUT, first.blocks());
        List<ShipPattern> merged = ShipPatterns.merge(builtIns, Map.of(extra.id(), extra, first.id(), replacement));
        assertEquals(13, merged.size());
        assertEquals(replacement, merged.getFirst());
        assertEquals(extra, merged.getLast());
        assertEquals(builtIns.subList(1, 12), merged.subList(1, 12));
        ShipPattern retiered = new ShipPattern(first.id(), "Wrong tier", "", DockTier.BRIG, first.blocks());
        assertEquals(builtIns, ShipPatterns.merge(builtIns, Map.of(first.id(), retiered)), "a replacement must keep the built-in's tier");
    }

    @Test void everyPatternUsesThePromisedCanvasAndHullEnvelope() {
        for (ShipPattern pattern : catalog()) {
            Dimensions expected = DIMENSIONS.get(pattern.tier());
            AABB core = pattern.bounds(false), decorated = pattern.bounds(true);
            assertEquals(expected.width, (int) core.getXsize(), pattern.name());
            assertEquals(expected.height, (int) core.getYsize(), pattern.name());
            assertEquals(expected.length, (int) core.getZsize(), pattern.name());
            assertEquals(core.minX, decorated.minX, 0, pattern.name());
            assertEquals(core.maxX, decorated.maxX, 0, pattern.name());
            assertEquals(core.minZ, decorated.minZ, 0, pattern.name());
            assertEquals(core.maxZ, decorated.maxZ, 0, pattern.name());
            assertEquals(3, (int) core.minZ, pattern.name() + " front margin");
            assertEquals(0, (int) core.minY, pattern.name() + " keel level");
            assertTrue(core.maxX <= pattern.tier().width && core.maxY <= pattern.tier().height
                    && core.maxZ <= pattern.tier().length, pattern.name() + " dock envelope");
        }
    }

    @Test void launchCoresAreFaceConnectedAndEveryDecorationRemainsAttached() {
        for (ShipPattern pattern : catalog()) {
            assertConnected(pattern, false);
            assertConnected(pattern, true);
            Set<BlockPos> core = pattern.included(false).stream().map(ShipPattern.Block::pos).collect(Collectors.toSet());
            for (ShipPattern.Block decoration : pattern.blocks().stream().filter(ShipPattern.Block::decoration).toList()) {
                boolean touchesPattern = neighbors(decoration.pos()).stream().anyMatch(pos ->
                        core.contains(pos) || pattern.blocks().stream().anyMatch(cell -> cell.decoration() && cell.pos().equals(pos)));
                assertTrue(touchesPattern, pattern.name() + " loose decoration at " + decoration.pos());
            }
        }
    }

    @Test void deviceLoadsAndLiftReportsMatchTheAuthoredBills() {
        for (ShipPattern pattern : catalog()) {
            Map<Block, Long> blocks = pattern.included(false).stream().collect(Collectors.groupingBy(
                    cell -> cell.state().getBlock(), HashMap::new, Collectors.counting()));
            int expectedEngines = pattern.id().getPath().endsWith("_hauler") ? 1 : 2;
            Block engine = testEngine(pattern.tier());
            int liftCells = blocks.getOrDefault(Blocks.WHITE_WOOL, 0L).intValue();
            double mass = pattern.included(false).stream().mapToDouble(cell -> MassTable.mass(cell.state())).sum();
            String report = report(pattern, liftCells, mass);
            assertEquals(1L, blocks.getOrDefault(Blocks.LECTERN, 0L).longValue(), report);
            assertEquals((long) expectedEngines, blocks.getOrDefault(engine, 0L).longValue(), report);
            assertTrue(blocks.getOrDefault(Blocks.IRON_BLOCK, 0L) >= 1, report);
            assertTrue(blocks.getOrDefault(Blocks.OAK_STAIRS, 0L) >= 1, report);
            assertTrue(blocks.getOrDefault(Blocks.CHEST, 0L) >= 1, report);
            assertTrue(blocks.getOrDefault(Blocks.FURNACE, 0L) >= 1, report);
            assertEquals(LIFT_CELLS.get(pattern.id().getPath()).intValue(), liftCells, report);
            assertTrue(liftCells * MassTable.liftPerCell() >= mass, report);
            assertTrue(pattern.included(false).size() <= liftCells * MassTable.blocksPerCell(), report);
        }
    }

    @Test void decorationToggleChangesPreviewAndCostWithoutChangingTheStructuralReport() {
        for (ShipPattern pattern : catalog()) {
            int coreCost = pattern.cost(false).values().stream().mapToInt(Integer::intValue).sum();
            int decoratedCost = pattern.cost(true).values().stream().mapToInt(Integer::intValue).sum();
            ShipPattern.Stats core = pattern.stats(false), decorated = pattern.stats(true);
            assertEquals(pattern.included(false).size(), coreCost, pattern.name());
            assertEquals(pattern.included(true).size(), decoratedCost, pattern.name());
            assertTrue(decoratedCost > coreCost, pattern.name());
            assertEquals(core.structuralBlocks(), decorated.structuralBlocks(), pattern.name());
            assertEquals(core.mass(), decorated.mass(), 0, pattern.name());
            assertEquals(core.lift(), decorated.lift(), 0, pattern.name());
            assertEquals(core.cells(), decorated.cells(), pattern.name());
            assertEquals(core.engines(), decorated.engines(), pattern.name());
            assertNotEquals(pattern.cost(false), pattern.cost(true), pattern.name());
            assertFalse(pattern.blocks().stream().filter(ShipPattern.Block::decoration)
                    .map(cell -> Item.byBlock(cell.state().getBlock())).collect(Collectors.toSet()).isEmpty(), pattern.name());
        }
    }

    @Test void eachFamilyKeepsItsDefiningDeckSilhouette() {
        for (ShipPattern pattern : catalog()) {
            AABB bounds = pattern.bounds(false);
            int centerX = (int) bounds.minX + (int) bounds.getXsize() / 2;
            int frontZ = (int) bounds.minZ;
            Set<BlockPos> core = pattern.included(false).stream().map(ShipPattern.Block::pos).collect(Collectors.toSet());
            if (pattern.id().getPath().endsWith("_cutter")) {
                long bowWidth = core.stream().filter(pos -> pos.getY() == 1 && pos.getZ() == frontZ).count();
                assertEquals(1, bowWidth, pattern.name() + " pointed bow");
            } else if (pattern.id().getPath().endsWith("_twinhull")) {
                assertFalse(core.contains(new BlockPos(centerX, 1, frontZ + 2)), pattern.name() + " open center channel");
            } else {
                int middleZ = frontZ + (int) bounds.getZsize() / 2;
                long deckWidth = core.stream().filter(pos -> pos.getY() == 1 && pos.getZ() == middleZ).count();
                assertEquals((int) bounds.getXsize(), deckWidth, pattern.name() + " broad barge deck");
            }
        }
    }

    @Test void deckStairsKeepPlayerHeadroom() {
        for (ShipPattern pattern : catalog()) {
            Set<BlockPos> core = pattern.included(false).stream().map(ShipPattern.Block::pos).collect(Collectors.toSet());
            List<BlockPos> steps = pattern.included(false).stream().filter(cell -> cell.state().getBlock() instanceof StairBlock
                    && cell.state().getValue(StairBlock.FACING) == Direction.SOUTH).map(ShipPattern.Block::pos).toList();
            int lowest = steps.stream().mapToInt(BlockPos::getY).min().orElse(Integer.MAX_VALUE);
            for (BlockPos step : steps) {
                assertFalse(core.contains(step.above()) || core.contains(step.above(2)), pattern.name() + " stair without headroom at " + step);
                // The tile a player steps off from must clear a rising head too, not only the steps themselves.
                if (step.getY() == lowest) for (int dy = 0; dy <= 2; dy++)
                    assertFalse(core.contains(step.north().above(dy)), pattern.name() + " blocked stair approach at " + step.north().above(dy));
            }
        }
    }

    @Test void prefabRailingsFormReciprocalRunsWithRealCornerStates() {
        for (ShipPattern pattern : catalog()) {
            Map<BlockPos, BlockState> states = pattern.included(true).stream().collect(Collectors.toMap(
                    ShipPattern.Block::pos, ShipPattern.Block::state));
            List<ShipPattern.Block> railings = pattern.blocks().stream()
                    .filter(cell -> cell.decoration() && cell.state().getBlock() instanceof FenceBlock).toList();
            assertFalse(railings.isEmpty(), pattern.name());
            for (ShipPattern.Block railing : railings) {
                int arms = 0;
                assertFalse(railing.state().getValue(CrossCollisionBlock.WATERLOGGED), pattern.name());
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    boolean connected = railing.state().getValue(fenceProperty(direction));
                    BlockState neighbor = states.getOrDefault(railing.pos().relative(direction), Blocks.AIR.defaultBlockState());
                    boolean neighborRailing = neighbor.getBlock() instanceof FenceBlock;
                    if (connected) {
                        arms++;
                        assertFalse(neighbor.isAir(), pattern.name() + " phantom arm from " + railing.pos() + " toward " + direction);
                    }
                    if (neighborRailing) {
                        assertTrue(connected, pattern.name() + " missing arm from " + railing.pos() + " toward " + direction);
                        assertTrue(neighbor.getValue(fenceProperty(direction.getOpposite())),
                                pattern.name() + " one-way railing connection at " + railing.pos());
                    }
                }
                assertTrue(arms > 0, pattern.name() + " isolated railing at " + railing.pos());
            }
            if (pattern.id().getPath().endsWith("_cutter"))
                assertTrue(railings.stream().anyMatch(cell -> hasArmOnBothAxes(cell.state())),
                        pattern.name() + " tapered bow has no corner railing state");
        }
    }

    private static void assertConnected(ShipPattern pattern, boolean decorations) {
        Set<BlockPos> occupied = pattern.included(decorations).stream().map(ShipPattern.Block::pos).collect(Collectors.toSet());
        Set<BlockPos> reached = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        BlockPos start = occupied.iterator().next();
        reached.add(start); open.add(start);
        while (!open.isEmpty()) for (BlockPos next : neighbors(open.removeFirst()))
            if (occupied.contains(next) && reached.add(next)) open.addLast(next);
        assertEquals(occupied.size(), reached.size(), report(pattern,
                count(pattern, Blocks.WHITE_WOOL), pattern.included(false).stream().mapToDouble(cell -> MassTable.mass(cell.state())).sum()));
    }

    private static List<BlockPos> neighbors(BlockPos pos) {
        return java.util.Arrays.stream(Direction.values()).map(pos::relative).toList();
    }

    private static int count(ShipPattern pattern, Block block) {
        return (int) pattern.included(false).stream().filter(cell -> cell.state().is(block)).count();
    }

    private static boolean hasArmOnBothAxes(BlockState state) {
        return (state.getValue(CrossCollisionBlock.NORTH) || state.getValue(CrossCollisionBlock.SOUTH))
                && (state.getValue(CrossCollisionBlock.EAST) || state.getValue(CrossCollisionBlock.WEST));
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty fenceProperty(Direction direction) {
        return switch (direction) {
            case NORTH -> CrossCollisionBlock.NORTH;
            case EAST -> CrossCollisionBlock.EAST;
            case SOUTH -> CrossCollisionBlock.SOUTH;
            case WEST -> CrossCollisionBlock.WEST;
            default -> throw new IllegalArgumentException("Not horizontal: " + direction);
        };
    }

    private static Block testEngine(DockTier tier) {
        return switch (tier) {
            case SCOUT -> Blocks.PISTON;
            case BRIG -> Blocks.STICKY_PISTON;
            case CRUISER -> Blocks.BLAST_FURNACE;
            case DREADNOUGHT -> Blocks.SMOKER;
        };
    }

    private static String report(ShipPattern pattern, int cells, double mass) {
        AABB bounds = pattern.bounds(false);
        return String.format(java.util.Locale.ROOT,
                "%s: core=%d decoration=%d bounds=%dx%dx%d cells=%d lift=%.0f mass=%.0f",
                pattern.id(), pattern.included(false).size(), pattern.blocks().size() - pattern.included(false).size(),
                (int) bounds.getXsize(), (int) bounds.getYsize(), (int) bounds.getZsize(),
                cells, cells * MassTable.liftPerCell(), mass);
    }

    private record Dimensions(int width, int height, int length) {}
}
