package dev.skydock.data;

import dev.skydock.Skydock;
import dev.skydock.block.FenceConnections;
import dev.skydock.block.LiftCellBlock;
import dev.skydock.block.SkydockBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * The built-in shipyard catalog. Coordinates are dock-local: the deck begins at y=1,
 * the bow faces the controller at low z, and every hull starts three blocks behind it.
 *
 * <p>The catalog deliberately authors decorations as a second layer. Omitting that
 * layer changes the preview and bill of materials without changing structural mass,
 * lift demand, or the connected launchable core.</p>
 */
public final class ShipPatterns {
    private static final int FRONT_MARGIN = 3;
    private ShipPatterns() {}

    /** Built-ins merged with datapack patterns; swapped whole by {@link #setData} so readers never see a partial reload. */
    private static volatile Merged merged;

    private record Merged(List<ShipPattern> all, Map<ResourceLocation, ShipPattern> byId, Map<DockTier, List<ShipPattern>> byTier) {
        static Merged of(List<ShipPattern> patterns) { return new Merged(patterns, indexById(patterns), indexByTier(patterns)); }
    }

    private static Merged merged() {
        Merged current = merged;
        if (current == null) merged = current = Merged.of(Catalog.ALL);
        return current;
    }

    public static List<ShipPattern> all() { return merged().all(); }

    public static List<ShipPattern> forTier(DockTier tier) {
        return merged().byTier().getOrDefault(tier, List.of());
    }

    public static Optional<ShipPattern> get(ResourceLocation id) {
        return Optional.ofNullable(merged().byId().get(id));
    }

    /**
     * Replaces the datapack patterns. A data pattern whose id matches a built-in of the same tier takes the
     * built-in's place in the list; the rest follow the built-ins in id order.
     */
    public static void setData(Map<ResourceLocation, ShipPattern> patterns) {
        merged = Merged.of(merge(Catalog.ALL, patterns));
    }

    static List<ShipPattern> merge(List<ShipPattern> builtIns, Map<ResourceLocation, ShipPattern> data) {
        Map<ResourceLocation, ShipPattern> remaining = new java.util.TreeMap<>(data);
        List<ShipPattern> result = new ArrayList<>(builtIns.size() + data.size());
        for (ShipPattern builtIn : builtIns) {
            ShipPattern replacement = remaining.remove(builtIn.id());
            // Keeping the tier keeps every tier stocked, so a dock always has a default pattern.
            if (replacement != null && replacement.tier() != builtIn.tier()) {
                Skydock.LOGGER.error("Ship pattern {} replaces a {} built-in and must keep its tier; ignoring it", builtIn.id(), builtIn.tier().key());
                replacement = null;
            }
            result.add(replacement != null ? replacement : builtIn);
        }
        result.addAll(remaining.values());
        return List.copyOf(result);
    }

    public static ShipPattern defaultFor(DockTier tier) {
        List<ShipPattern> patterns = forTier(tier);
        if (patterns.isEmpty()) throw new IllegalArgumentException("No pattern for " + tier);
        return patterns.getFirst();
    }

    private static final class Catalog {
        private static final List<ShipPattern> ALL = createCatalog(Palette.production());
    }

    /** A loader-free catalog used by unit tests to exercise the exact production geometry. */
    static List<ShipPattern> createTestCatalog() { return createCatalog(Palette.vanillaTestPalette()); }

    private static List<ShipPattern> createCatalog(Palette palette) {
        List<ShipPattern> patterns = new ArrayList<>(12);
        patterns.add(cutter(palette, "scout_cutter", "pattern.skydock.scout_cutter.name", "pattern.skydock.scout_cutter.description", DockTier.SCOUT, new CutterPlan(11, 19, 9, 1, 5, 4, false, 0, 0)));
        patterns.add(twinHull(palette, "scout_twinhull", "pattern.skydock.scout_twinhull.name", "pattern.skydock.scout_twinhull.description", DockTier.SCOUT, new TwinPlan(11, 19, 9, 1, 5, 4, false, 0, 0, 0)));
        patterns.add(hauler(palette, "scout_hauler", "pattern.skydock.scout_hauler.name", "pattern.skydock.scout_hauler.description", DockTier.SCOUT, new HaulerPlan(11, 19, 9, 1, 3, 3, 4, false)));

        patterns.add(cutter(palette, "brig_cutter", "pattern.skydock.brig_cutter.name", "pattern.skydock.brig_cutter.description", DockTier.BRIG, new CutterPlan(17, 27, 12, 2, 9, 5, false, 3, 2)));
        patterns.add(twinHull(palette, "brig_twinhull", "pattern.skydock.brig_twinhull.name", "pattern.skydock.brig_twinhull.description", DockTier.BRIG, new TwinPlan(17, 27, 12, 2, 5, 4, true, 2, 2, 0)));
        patterns.add(hauler(palette, "brig_hauler", "pattern.skydock.brig_hauler.name", "pattern.skydock.brig_hauler.description", DockTier.BRIG, new HaulerPlan(17, 27, 12, 2, 5, 4, 7, true)));

        patterns.add(cutter(palette, "cruiser_cutter", "pattern.skydock.cruiser_cutter.name", "pattern.skydock.cruiser_cutter.description", DockTier.CRUISER, new CutterPlan(23, 35, 15, 3, 13, 7, true, 5, 3)));
        patterns.add(twinHull(palette, "cruiser_twinhull", "pattern.skydock.cruiser_twinhull.name", "pattern.skydock.cruiser_twinhull.description", DockTier.CRUISER, new TwinPlan(23, 35, 15, 2, 7, 5, true, 3, 3, 2)));
        patterns.add(hauler(palette, "cruiser_hauler", "pattern.skydock.cruiser_hauler.name", "pattern.skydock.cruiser_hauler.description", DockTier.CRUISER, new HaulerPlan(23, 35, 15, 2, 5, 5, 8, true)));

        patterns.add(cutter(palette, "dreadnought_cutter", "pattern.skydock.dreadnought_cutter.name", "pattern.skydock.dreadnought_cutter.description", DockTier.DREADNOUGHT, new CutterPlan(29, 43, 18, 3, 17, 10, true, 6, 3)));
        patterns.add(twinHull(palette, "dreadnought_twinhull", "pattern.skydock.dreadnought_twinhull.name", "pattern.skydock.dreadnought_twinhull.description", DockTier.DREADNOUGHT, new TwinPlan(29, 43, 18, 3, 9, 7, true, 5, 3, 2)));
        patterns.add(hauler(palette, "dreadnought_hauler", "pattern.skydock.dreadnought_hauler.name", "pattern.skydock.dreadnought_hauler.description", DockTier.DREADNOUGHT, new HaulerPlan(29, 43, 18, 3, 7, 6, 10, true)));
        return List.copyOf(patterns);
    }

    private static Map<ResourceLocation, ShipPattern> indexById(List<ShipPattern> patterns) {
        Map<ResourceLocation, ShipPattern> result = new LinkedHashMap<>();
        for (ShipPattern pattern : patterns) {
            if (result.put(pattern.id(), pattern) != null) throw new IllegalStateException("Duplicate ship pattern id " + pattern.id());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<DockTier, List<ShipPattern>> indexByTier(List<ShipPattern> patterns) {
        Map<DockTier, List<ShipPattern>> result = new EnumMap<>(DockTier.class);
        for (DockTier tier : DockTier.values())
            result.put(tier, patterns.stream().filter(pattern -> pattern.tier() == tier).toList());
        return Collections.unmodifiableMap(result);
    }

    /** Hull and envelope proportions for one cutter; every other dimension is derived from them. */
    private record CutterPlan(int width, int length, int height, int deckY, int envelopeWidth, int envelopeHeight,
                              boolean holdAndForecastle, int cabinHalf, int cabinWalls) {}

    private static ShipPattern cutter(Palette palette, String id, String name, String description, DockTier tier, CutterPlan plan) {
        Builder b = new Builder(tier, palette);
        int width = plan.width(), length = plan.length(), height = plan.height(), deckY = plan.deckY(), ch = plan.cabinHalf();
        boolean big = plan.holdAndForecastle();
        int x0 = centeredX(tier, width), z0 = FRONT_MARGIN, cx = x0 + width / 2, full = width / 2;
        int top = deckY + 1;                              // waist bulwark course; floor of the raised decks
        int bowRun = Math.max(5, length / 3), sternRun = Math.max(3, length / 8);
        int transom = length - 2;                         // last hull row; the final row carries only the rudder
        int qStart = transom - Math.max(6, length / 4 + 2) + 1, cabinFront = qStart + 3;
        int fcEnd = big ? bowRun - 2 : 0;                 // forecastle rows 1..fcEnd
        int stairX = 3;
        int[] halves = new int[transom + 1];
        for (int z = 0; z <= transom; z++) halves[z] = cutterHalf(z, full, bowRun, sternRun, transom);
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState(), dark = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();

        // Hull. The deck edge is a log wale; below it each layer narrows by one and ends in an inward-facing
        // upside-down stair. Large hulls are hollow under the waist, leaving a two-high cargo hold.
        BlockState wale = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        BlockState keel = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        BlockState hatch = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP);
        int hatchZ = fcEnd + 2;
        for (int z = 0; z <= transom; z++) {
            int h = halves[z], zz = z0 + z;
            for (int x = cx - h; x <= cx + h; x++) {
                int dx = Math.abs(x - cx);
                b.block(x, deckY, zz, h > 0 && dx == h ? wale : big && (z == hatchZ || z == hatchZ + 1) && dx <= 1 ? hatch : plank);
            }
            for (int k = 1; k <= deckY; k++) {
                int y = deckY - k, hk = h - k;
                boolean hold = big && y > 0 && y < deckY && z > fcEnd && z < qStart && hk >= 2;
                if (!hold) {
                    b.block(cx, y, zz, y > 0 ? dark : z == length / 2 ? palette.ballast() : keel);
                    for (int x = cx - hk + 1; x < cx + hk; x++) if (x != cx) b.block(x, y, zz, dark);
                }
                if (hk >= 1) { b.block(cx - hk, y, zz, bilge(Direction.EAST)); b.block(cx + hk, y, zz, bilge(Direction.WEST)); }
            }
        }
        if (big) {
            // A ladder on a post under the hatch, barrels along the hold walls, lanterns on some of them.
            for (int y = 1; y < deckY; y++) {
                b.block(cx, y, z0 + hatchZ + 2, log);
                b.block(cx, y, z0 + hatchZ + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
            }
            for (int z = hatchZ + 4; z < qStart - 1; z += 3)
                for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * (halves[z] - 3), 1, z0 + z, Blocks.BARREL.defaultBlockState());
            for (int z = hatchZ + 4; z < qStart - 1; z += 6)
                for (int sx = -1; sx <= 1; sx += 2) b.decorIfEmpty(cx + sx * (halves[z] - 3), 2, z0 + z, palette.lantern());
        }

        // Bulwarks: a solid course around the waist with boarding ports amidships, and around the raised decks.
        int port = (Math.max(bowRun, fcEnd + 3) + qStart) / 2;
        List<int[]> waist = course(halves, fcEnd + 1, qStart - 1).stream()
                .filter(cell -> !(cell[1] == port && Math.abs(cell[0]) == full)).toList();
        for (int[] cell : waist) b.block(cx + cell[0], top, z0 + cell[1], dark);
        List<int[]> fore = big ? course(halves, 1, fcEnd) : List.of();
        b.block(cx, top, z0, log);                                                   // stem post
        if (big) b.block(cx, top + 1, z0, log);
        List<int[]> stern = course(halves, qStart, transom);
        for (int[] cell : fore) b.coreIfEmpty(cx + cell[0], top, z0 + cell[1], dark);
        for (int[] cell : stern) b.coreIfEmpty(cx + cell[0], top, z0 + cell[1], dark);
        for (int z = 1; z <= transom; z++) if ((big && z <= fcEnd) || z >= qStart)
            for (int x = cx - halves[z]; x <= cx + halves[z]; x++) b.coreIfEmpty(x, top, z0 + z, plank);
        int th = halves[transom], engineX = th - 1;
        for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * engineX, top + 1, z0 + transom, palette.engine(tier));
        List<int[]> sternRail = new ArrayList<>(stern);
        for (int dx = -th + 1; dx < th; dx++) sternRail.add(new int[] {dx, transom});
        for (int[] cell : fore) b.coreIfEmpty(cx + cell[0], top + 1, z0 + cell[1], dark);
        for (int[] cell : sternRail) b.coreIfEmpty(cx + cell[0], top + 1, z0 + cell[1], dark);
        for (int sx = -1; sx <= 1; sx += 2) b.stair(cx + sx * stairX, top, z0 + qStart - 1, Direction.SOUTH);
        if (big) for (int sx = -1; sx <= 1; sx += 2) b.stair(cx + sx * stairX, top, z0 + fcEnd + 1, Direction.NORTH);
        b.fill(cx, 0, z0 + length - 1, cx, top + 1, z0 + length - 1, log);        // rudder post

        // Helm on the quarterdeck with its wheel toward the pilot, crew seats, and a mooring clamp at the bow.
        b.block(cx, top + 1, z0 + qStart + 1, palette.aftHelm());
        for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * 2, top + 1, z0 + qStart + 1, palette.seat());
        b.block(cx, top + (big ? 1 : 0), z0 + 2, palette.clamp());
        if (!big && ch > 0)                               // mid-size hulls stow barrels on deck; large ones in the hold
            for (int z = qStart - 4; z <= qStart - 3; z++)
                for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * (full - 1), top, z0 + z, Blocks.BARREL.defaultBlockState());

        // Stores: open on a small quarterdeck, otherwise in a stern cabin under a gabled roof or a poop deck.
        int roof = -1;
        if (ch == 0) {
            b.block(cx - 2, top + 1, z0 + transom - 1, Blocks.CHEST.defaultBlockState());
            b.block(cx + 2, top + 1, z0 + transom - 1, Blocks.FURNACE.defaultBlockState());
        } else {
            int wallTop = top + plan.cabinWalls(), back = transom - 1;
            BlockState pane = Blocks.GLASS_PANE.defaultBlockState();
            for (int y = top + 1; y <= wallTop; y++) {
                for (int z = cabinFront; z <= back; z++)
                    for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * ch, y, z0 + z, y == top + 2 && z > cabinFront && z < back ? pane : log);
                for (int x = cx - ch + 1; x < cx + ch; x++) {
                    if (x != cx || y > top + 2) b.block(x, y, z0 + cabinFront, plank);            // doorway on the centerline
                    b.block(x, y, z0 + back, y == top + 2 && Math.abs(x - cx) <= 1 ? pane : plank);
                }
            }
            b.block(cx - ch + 1, top + 1, z0 + back - 1, Blocks.CHEST.defaultBlockState());
            b.block(cx + ch - 1, top + 1, z0 + back - 1, Blocks.FURNACE.defaultBlockState());
            if (big) {
                b.block(cx - ch + 1, top + 1, z0 + cabinFront + 1, Blocks.BARREL.defaultBlockState());
                b.block(cx + ch - 1, top + 1, z0 + cabinFront + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
            }
            roof = wallTop + 1;
            if (!big) {
                // A shallow gable: two stair courses and a slab ridge, low enough to see the deck over from astern.
                for (int z = cabinFront; z <= back; z++) {
                    for (int step = 0; step < 2; step++) {
                        int y = roof + step, edge = ch + 1 - step;
                        for (int x = cx - edge + 1; x < cx + edge; x++) b.block(x, y, z0 + z, dark);
                        b.block(cx - edge, y, z0 + z, roofStair(Direction.EAST)); b.block(cx + edge, y, z0 + z, roofStair(Direction.WEST));
                    }
                    for (int x = cx - ch + 2; x <= cx + ch - 2; x++) b.block(x, roof + 2, z0 + z, Blocks.DARK_OAK_SLAB.defaultBlockState());
                }
            } else {
                for (int y = top + 1; y <= roof; y++)
                    b.block(cx + ch - 1, y, z0 + cabinFront - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
                for (int z = cabinFront - 1; z <= back; z++)
                    for (int x = cx - ch - 1; x <= cx + ch + 1; x++) b.coreIfEmpty(x, roof, z0 + z, dark);
            }
        }

        // Envelope: a hollow airship envelope with a blunt nose and a tapered tail; its top row is the height limit.
        int ew = plan.envelopeWidth(), eh = plan.envelopeHeight();
        int ze0 = z0 + Math.max(2, bowRun / 3), ze1 = z0 + (ch == 0 ? qStart - 1 : cabinFront - 2);
        Set<BlockPos> envelope = envelope(cx, ze0, ze1, ew, eh, height - 1);
        for (BlockPos cell : skin(envelope)) b.block(cell.getX(), cell.getY(), cell.getZ(), palette.lift());

        fins(b, envelope, cx, height - eh + eh / 2, Math.max(1, ew / 5));

        // Frames: posts up to the envelope, with a crossbeam only where it clears the pilot's eye line.
        int postX = Math.max(1, ew / 2 - 1), envelopeLength = ze1 - ze0;
        double eye = top + 1 + 1.62;
        for (int fz : new int[] {Math.max(ze0 + envelopeLength / 4, z0 + fcEnd + 3), Math.min(ze1 - envelopeLength / 4, z0 + qStart - 3)})
            frame(b, envelope, cx - postX, cx + postX, fz, top, eye + .3);

        // Decorations: railings along every bulwark course and deck lip, flags, and lanterns.
        for (int[] cell : waist) b.decorIfEmpty(cx + cell[0], top + 1, z0 + cell[1], palette.railing());
        for (int[] cell : fore) b.decorIfEmpty(cx + cell[0], top + 2, z0 + cell[1], palette.railing());
        for (int[] cell : sternRail)
            if (!(cell[1] == transom && Math.abs(cell[0]) == engineX)) b.decorIfEmpty(cx + cell[0], top + 2, z0 + cell[1], palette.railing());
        for (int lip : big ? new int[] {qStart, fcEnd} : new int[] {qStart})
            for (int x = cx - halves[lip] + 1; x < cx + halves[lip]; x++)
                if (Math.abs(x - cx) != stairX) b.decorIfEmpty(x, top + 1, z0 + lip, palette.railing());
        b.decorIfEmpty(cx, top + (big ? 2 : 1), z0, palette.flag());
        b.decorIfEmpty(cx, top + 2, z0 + length - 1, palette.lantern());
        if (big && roof > 0) {
            for (int z = cabinFront - 1; z < transom; z++)
                for (int sx = -1; sx <= 1; sx += 2) b.decorIfEmpty(cx + sx * (ch + 1), roof + 1, z0 + z, palette.railing());
            for (int x = cx - ch; x <= cx + ch; x++) {
                b.decorIfEmpty(x, roof + 1, z0 + transom - 1, palette.railing());
                if (x != cx + ch - 1) b.decorIfEmpty(x, roof + 1, z0 + cabinFront - 1, palette.railing());
            }
            b.decorIfEmpty(cx, roof + 1, z0 + transom - 2, palette.flag());
        }
        return b.pattern(id, name, description);
    }

    /** Hull half-width at row z: a sine-curved bow, a straight waist, and a gently drawn-in stern. */
    private static int cutterHalf(int z, int full, int bowRun, int sternRun, int transom) {
        if (z == 0) return 0;
        if (z < bowRun) return Math.max(1, (int) Math.round(full * Math.sin(Math.PI / 2 * z / bowRun)));
        if (z > transom - sternRun) {
            double t = (z - (transom - sternRun)) / (double) sternRun;
            return full - (int) Math.round(full * .35 * t * t);
        }
        return full;
    }

    /** Bulwark cells as {dx, z}. Where the hull narrows or widens the course steps along the wider row, so no diagonal gap opens. */
    private static List<int[]> course(int[] halves, int from, int to) {
        TreeSet<int[]> cells = new TreeSet<>(Comparator.<int[]>comparingInt(cell -> cell[1]).thenComparingInt(cell -> cell[0]));
        for (int z = from; z <= to; z++) {
            int h = halves[z], low = h;
            if (z > from) {
                int previous = halves[z - 1];
                if (h > previous) low = previous;
                else for (int o = h; o < previous; o++) { cells.add(new int[] {o, z - 1}); cells.add(new int[] {-o, z - 1}); }
            }
            for (int o = low; o <= h; o++) { cells.add(new int[] {o, z}); cells.add(new int[] {-o, z}); }
        }
        return List.copyOf(cells);
    }

    /** The one-cell outer skin of a solid, bridged where it would otherwise meet itself only along an edge. */
    private static List<BlockPos> skin(Set<BlockPos> solid) {
        Comparator<BlockPos> order = Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ());
        Set<BlockPos> skin = new HashSet<>();
        for (BlockPos cell : solid) for (Direction direction : Direction.values())
            if (!solid.contains(cell.relative(direction))) { skin.add(cell); break; }
        List<BlockPos> ordered = new ArrayList<>(skin);
        ordered.sort(order);
        for (BlockPos cell : ordered)
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) for (int k = -1; k <= 1; k++) {
                if (Math.abs(i) + Math.abs(j) + Math.abs(k) != 2 || !skin.contains(cell.offset(i, j, k))) continue;
                List<BlockPos> via = new ArrayList<>(2);
                if (i != 0) via.add(cell.offset(i, 0, 0));
                if (j != 0) via.add(cell.offset(0, j, 0));
                if (k != 0) via.add(cell.offset(0, 0, k));
                if (via.stream().anyMatch(skin::contains)) continue;
                for (BlockPos bridge : via) if (solid.contains(bridge)) { skin.add(bridge); break; }
            }
        List<BlockPos> result = new ArrayList<>(skin);
        result.sort(order);
        return result;
    }

    private static int lowest(Set<BlockPos> cells, int x, int z) {
        return cells.stream().filter(cell -> cell.getX() == x && cell.getZ() == z).mapToInt(BlockPos::getY).min().orElseThrow();
    }

    private static BlockState bilge(Direction inward) {
        return Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, inward)
                .setValue(StairBlock.HALF, Half.TOP).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    private static BlockState roofStair(Direction upslope) {
        return Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, upslope).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
    }

    /** Proportions for one twin-hull: two slender hulls joined by a bridge deck; everything else is derived. */
    private record TwinPlan(int width, int length, int height, int deckY, int envelopeWidth, int envelopeHeight,
                            boolean twinEnvelopes, int cabinHalf, int cabinWalls, int extraSpans) {}

    private static ShipPattern twinHull(Palette palette, String id, String name, String description, DockTier tier, TwinPlan plan) {
        Builder b = new Builder(tier, palette);
        int width = plan.width(), length = plan.length(), height = plan.height(), deckY = plan.deckY(), ch = plan.cabinHalf();
        int x0 = centeredX(tier, width), z0 = FRONT_MARGIN, cx = x0 + width / 2;
        int hullHalf = ((width / 4) | 1) / 2, top = deckY + 1;
        int[] hulls = {x0 + hullHalf, x0 + width - 1 - hullHalf};
        int innerLeft = hulls[0] + hullHalf + 1, innerRight = hulls[1] - hullHalf - 1;
        int bowRun = Math.max(4, length / 4), sternRun = Math.max(2, length / 10), transom = length - 2;
        int bStart = length * 2 / 5, bEnd = length * 3 / 4;
        int[] halves = new int[transom + 1];
        for (int z = 0; z <= transom; z++) halves[z] = cutterHalf(z, hullHalf, bowRun, sternRun, transom);
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState(), dark = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState(), wale = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        BlockState keel = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);

        // Two hulls shaped like small cutters: a sine bow, a log wale, and a keel under a narrowing bilge.
        for (int hull : hulls) for (int z = 0; z <= transom; z++) {
            int h = halves[z], zz = z0 + z;
            for (int x = hull - h; x <= hull + h; x++) b.block(x, deckY, zz, h > 0 && Math.abs(x - hull) == h ? wale : plank);
            for (int k = 1; k <= deckY; k++) {
                int y = deckY - k, hk = h - k;
                b.block(hull, y, zz, y > 0 ? dark : z == length / 2 ? palette.ballast() : keel);
                for (int x = hull - hk + 1; x < hull + hk; x++) if (x != hull) b.block(x, y, zz, dark);
                if (hk >= 1) { b.block(hull - hk, y, zz, bilge(Direction.EAST)); b.block(hull + hk, y, zz, bilge(Direction.WEST)); }
            }
        }

        // The bridge deck, plus gangway spans on the larger ships, join the hulls; beams run under their edges.
        List<int[]> decks = new ArrayList<>();
        decks.add(new int[] {bStart, bEnd});
        if (plan.extraSpans() >= 1) decks.add(new int[] {transom - 4, transom - 2});
        if (plan.extraSpans() >= 2) decks.add(new int[] {bowRun + 1, bowRun + 3});
        BlockState beam = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        for (int[] deck : decks) for (int z = deck[0]; z <= deck[1]; z++) {
            for (int x = innerLeft; x <= innerRight; x++) b.block(x, deckY, z0 + z, plank);
            if (z == deck[0] || z == deck[1]) for (int x = innerLeft; x <= innerRight; x++) b.block(x, deckY - 1, z0 + z, beam);
        }

        // Bulwarks around each hull, open on the inner edge wherever a deck joins it; the decks are walled fore and aft.
        List<int[]> rail = new ArrayList<>();
        int port = (bStart + bEnd) / 2;
        for (int hull : hulls) {
            int inward = hull == hulls[0] ? 1 : -1;
            for (int[] cell : course(halves, 1, transom)) {
                boolean inner = Integer.signum(cell[0]) == inward;
                if (inner && decks.stream().anyMatch(deck -> cell[1] > deck[0] && cell[1] < deck[1])) continue;
                if (!inner && cell[1] == port && Math.abs(cell[0]) == hullHalf) continue;          // boarding port
                rail.add(new int[] {hull + cell[0], cell[1]});
            }
            for (int dx = -halves[transom] + 1; dx < halves[transom]; dx++) if (dx != 0) rail.add(new int[] {hull + dx, transom});
            b.block(hull, top, z0, log);                                                            // stem post
            b.block(hull, top, z0 + transom, palette.engine(tier));
            b.fill(hull, 0, z0 + length - 1, hull, top, z0 + length - 1, log);                      // rudder
        }
        for (int[] deck : decks) for (int x = innerLeft; x <= innerRight; x++) { rail.add(new int[] {x, deck[0]}); rail.add(new int[] {x, deck[1]}); }
        b.block(cx, top, z0 + bStart, palette.clamp());
        for (int[] cell : rail) b.coreIfEmpty(cell[0], top, z0 + cell[1], dark);

        // The helm stands on the bridge, looking down the open channel between the hulls.
        b.block(cx, top, z0 + bStart + 2, palette.aftHelm());
        for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * 2, top, z0 + bStart + 2, palette.seat());
        int roof = -1, cabinFront = bStart + 4, back = bEnd - 1;
        if (ch == 0) {
            b.block(cx - 2, top, z0 + bEnd - 1, Blocks.CHEST.defaultBlockState());
            b.block(cx + 2, top, z0 + bEnd - 1, Blocks.FURNACE.defaultBlockState());
        } else roof = cabin(b, cx, ch, top, plan.cabinWalls(), z0 + cabinFront, z0 + back, plan.cabinWalls() >= 3);

        // Envelopes: one over the bridge on the smallest ship, otherwise a long envelope over each hull.
        int ew = plan.envelopeWidth(), eh = plan.envelopeHeight();
        if (!plan.twinEnvelopes()) {
            Set<BlockPos> envelope = envelope(cx, z0 + bStart - 1, z0 + bEnd, ew, eh, height - 1);
            for (BlockPos cell : skin(envelope)) b.block(cell.getX(), cell.getY(), cell.getZ(), palette.lift());
            fins(b, envelope, cx, height - eh + eh / 2, Math.max(1, ew / 5));
            for (int fz : new int[] {z0 + bStart, z0 + bEnd}) frame(b, envelope, cx - ew / 2, cx + ew / 2, fz, top, top + 2);
        } else for (int hull : hulls) {
            // Flush with the hull's outer side so the envelope overhangs the channel, never the ship's beam.
            int ex = hull == hulls[0] ? x0 + ew / 2 : x0 + width - 1 - ew / 2;
            // Centered on the bridge and a little over half the ship's length, like a pair of pontoons.
            int reach = length * 11 / 40, middle = (bStart + bEnd) / 2;
            int ze0 = z0 + middle - reach, ze1 = z0 + Math.min(transom - 2, middle + reach), span = ze1 - ze0;
            Set<BlockPos> envelope = envelope(ex, ze0, ze1, ew, eh, height - 1);
            for (BlockPos cell : skin(envelope)) b.block(cell.getX(), cell.getY(), cell.getZ(), palette.lift());
            fins(b, envelope, ex, height - eh + eh / 2, Math.max(1, ew / 5));
            List<Integer> frames = new ArrayList<>(List.of(ze0 + span / 4, ze1 - span / 4));
            if (span > 24) frames.add(ze0 + span / 2);
            for (int fz : frames) frame(b, envelope, hull - hullHalf, hull + hullHalf, fz, top, top + 2);
        }

        // Decorations: railings along every bulwark, flags on the stems, lanterns on the rudders.
        for (int hull : hulls) {
            b.decorIfEmpty(hull, top + 1, z0, palette.flag());
            b.decorIfEmpty(hull, top + 1, z0 + length - 1, palette.lantern());
        }
        for (int[] cell : rail) if (!(cell[0] == cx && cell[1] == bStart)) b.decorIfEmpty(cell[0], top + 1, z0 + cell[1], palette.railing());
        if (roof > 0 && plan.cabinWalls() >= 3) roofDeck(b, palette, cx, ch, roof, z0 + cabinFront, z0 + back);
        return b.pattern(id, name, description);
    }

    /** Proportions for one hauler: a broad barge, a long deckhouse, and four envelope pods on pylons. */
    private record HaulerPlan(int width, int length, int height, int deckY, int podWidth, int podHeight, int podLength, boolean upperDeck) {}

    private static ShipPattern hauler(Palette palette, String id, String name, String description, DockTier tier, HaulerPlan plan) {
        Builder b = new Builder(tier, palette);
        int width = plan.width(), length = plan.length(), height = plan.height(), deckY = plan.deckY();
        boolean upper = plan.upperDeck();
        int x0 = centeredX(tier, width), z0 = FRONT_MARGIN, cx = x0 + width / 2, full = width / 2, top = deckY + 1;
        int bowRun = Math.max(3, width / 3), transom = length - 2;
        int[] halves = new int[transom + 1];
        for (int z = 0; z <= transom; z++) {
            double t = (bowRun - z - .5) / bowRun;
            halves[z] = z < bowRun ? Math.max(2, (int) Math.round(full * Math.sqrt(1 - t * t)))
                    : z >= transom - 1 ? full - (z - transom + 2) : full;
        }
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState(), dark = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState(), pane = Blocks.GLASS_PANE.defaultBlockState();
        BlockState wale = log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        BlockState keel = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);

        // A barge hull: a round bow, straight sides down to a chamfered bottom, and a squared stern.
        for (int z = 0; z <= transom; z++) {
            int h = halves[z], zz = z0 + z;
            for (int x = cx - h; x <= cx + h; x++) b.block(x, deckY, zz, Math.abs(x - cx) == h ? wale : plank);
            for (int y = deckY - 1; y >= 0; y--) {
                int hk = y == 0 ? h - 1 : h;
                b.block(cx, y, zz, y > 0 ? dark : z == length / 2 ? palette.ballast() : keel);
                for (int x = cx - hk + 1; x < cx + hk; x++) if (x != cx) b.block(x, y, zz, dark);
                if (y > 0) { b.block(cx - hk, y, zz, dark); b.block(cx + hk, y, zz, dark); }
                else { b.block(cx - hk, y, zz, bilge(Direction.EAST)); b.block(cx + hk, y, zz, bilge(Direction.WEST)); }
            }
        }

        // Bulwark all round, with boarding ports on the bow deck and the engine set into the stern.
        List<int[]> rail = new ArrayList<>();
        int hs = bowRun + 2, he = upper ? transom - 3 : transom - 4, dh = full - 2, port = hs - 2;
        for (int[] cell : course(halves, 0, transom)) if (!(cell[1] == port && Math.abs(cell[0]) == full)) rail.add(cell);
        for (int dx = -halves[0] + 1; dx < halves[0]; dx++) rail.add(new int[] {dx, 0});
        for (int dx = -halves[transom] + 1; dx < halves[transom]; dx++) if (dx != 0) rail.add(new int[] {dx, transom});
        b.block(cx, top, z0 + transom, palette.engine(tier));
        b.fill(cx, 0, z0 + length - 1, cx, top, z0 + length - 1, log);                                  // rudder
        b.block(cx + 2, top, z0 + 2, palette.clamp());
        for (int[] cell : rail) b.coreIfEmpty(cx + cell[0], top, z0 + cell[1], dark);

        // The deckhouse: a glazed hold lined with barrels and an aft cabin under a walkable upper deck, or on the
        // smallest hauler an open-sided workshop bay under a plain roof.
        int partition = upper ? he - Math.max(4, length / 7) : -1;
        for (int z = hs; z <= he; z++) for (int y = top; y <= top + 1; y++) {
            boolean post = z == hs || z == he || z == partition || (z - hs) % 3 == 0;
            for (int sx = -1; sx <= 1; sx += 2) if (upper || post) b.block(cx + sx * dh, y, z0 + z, post ? log : y == top ? plank : pane);
            if (upper && (z == hs || z == he || z == partition))
                for (int x = cx - dh + 1; x < cx + dh; x++) if (x != cx) b.block(x, y, z0 + z, plank);       // doorways on the centerline
        }
        int roofEnd = upper ? he + 1 : he;
        for (int z = hs; z <= roofEnd; z++) for (int x = cx - dh; x <= cx + dh; x++) b.block(x, top + 2, z0 + z, dark);
        if (upper) {
            for (int z = hs + 1; z < partition; z++) for (int sx = -1; sx <= 1; sx += 2)
                b.block(cx + sx * (dh - 1), top, z0 + z, Blocks.BARREL.defaultBlockState());
            for (int z = hs + 2; z < partition; z += 4) for (int sx = -1; sx <= 1; sx += 2) b.decorIfEmpty(cx + sx * (dh - 1), top + 1, z0 + z, palette.lantern());
            b.block(cx - dh + 1, top, z0 + he - 1, Blocks.CHEST.defaultBlockState());
            b.block(cx + dh - 1, top, z0 + he - 1, Blocks.FURNACE.defaultBlockState());
            b.block(cx + dh - 1, top, z0 + partition + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
            // Stairs up to the upper deck beside the forward door; the helm stands at its aft end.
            b.stair(cx - 2, top, z0 + hs - 3, Direction.SOUTH);
            b.block(cx - 2, top, z0 + hs - 2, log); b.stair(cx - 2, top + 1, z0 + hs - 2, Direction.SOUTH);
            b.fill(cx - 2, top, z0 + hs - 1, cx - 2, top + 1, z0 + hs - 1, log); b.stair(cx - 2, top + 2, z0 + hs - 1, Direction.SOUTH);
            b.block(cx, top + 3, z0 + he - 1, palette.aftHelm());
            for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * 2, top + 3, z0 + he - 1, palette.seat());
        } else {
            b.block(cx - 2, top, z0 + he - 1, Blocks.CHEST.defaultBlockState());
            b.block(cx + 2, top, z0 + he - 1, Blocks.FURNACE.defaultBlockState());
            b.block(cx - 2, top, z0 + hs + 1, Blocks.CRAFTING_TABLE.defaultBlockState());
            b.block(cx + 2, top, z0 + hs + 1, Blocks.BARREL.defaultBlockState());
            b.block(cx, top, z0 + transom - 2, palette.aftHelm());
            b.block(cx - 2, top, z0 + transom - 2, palette.seat());
        }

        // Four envelope pods riding low over the side decks, each on a pair of struts from the bulwark, and a signal
        // mast with a yard that carries the ship to its full height.
        int pw = plan.podWidth(), podHalf = pw / 2, pl = Math.min(plan.podLength(), (he - hs) / 2);
        int podBottom = upper ? top + 5 : top + 3, podTop = Math.min(height - 1, podBottom + plan.podHeight() - 1);
        for (int[] rows : new int[][] {{hs, hs + pl - 1}, {he - pl + 1, he}}) for (int sx = -1; sx <= 1; sx += 2) {
            int px = cx + sx * (full - podHalf), mid = z0 + (rows[0] + rows[1]) / 2, strut = cx + sx * full;
            Set<BlockPos> pod = envelope(px, z0 + rows[0], z0 + rows[1], pw, podTop - podBottom + 1, podTop);
            for (BlockPos cell : skin(pod)) b.block(cell.getX(), cell.getY(), cell.getZ(), palette.lift());
            for (int row : new int[] {rows[0] + pl / 3, rows[1] - pl / 3}) {
                int z = z0 + row;
                while (!hasColumn(pod, strut, z) && z != mid) z += Integer.signum(mid - z);
                b.fill(strut, top + 1, z, strut, lowest(pod, strut, z) - 1, z, log);
            }
        }
        int mastZ = z0 + (upper ? hs + 1 : (hs + he) / 2), yard = upper ? 2 : 1;
        b.fill(cx, top + 3, mastZ, cx, height - 1, mastZ, log);
        b.fill(cx - yard, height - 2, mastZ, cx - 1, height - 2, mastZ, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        b.fill(cx + 1, height - 2, mastZ, cx + yard, height - 2, mastZ, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        b.decorIfEmpty(cx, height, mastZ, palette.flag());

        // Decorations: railings on the bulwark and the upper deck's edge, a bow flag, lanterns.
        b.decorIfEmpty(cx, top + 1, z0, palette.flag());
        b.decorIfEmpty(cx, top + 1, z0 + length - 1, palette.lantern());
        for (int[] cell : rail) b.decorIfEmpty(cx + cell[0], top + 1, z0 + cell[1], palette.railing());
        if (upper) {
            for (int z = hs; z <= roofEnd; z++) for (int sx = -1; sx <= 1; sx += 2) b.decorIfEmpty(cx + sx * dh, top + 3, z0 + z, palette.railing());
            for (int x = cx - dh + 1; x < cx + dh; x++) {
                if (x != cx - 2) b.decorIfEmpty(x, top + 3, z0 + hs, palette.railing());                 // stair landing stays open
                b.decorIfEmpty(x, top + 3, z0 + roofEnd, palette.railing());
            }
        }
        return b.pattern(id, name, description);
    }

    /** A cabin with log walls, windows, a centerline doorway, and a flat roof one block wider, optionally reached by a ladder; returns the roof's y. */
    private static int cabin(Builder b, int cx, int ch, int floor, int walls, int front, int back, boolean ladder) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState(), log = Blocks.SPRUCE_LOG.defaultBlockState(), pane = Blocks.GLASS_PANE.defaultBlockState();
        for (int y = floor; y < floor + walls; y++) {
            for (int z = front; z <= back; z++)
                for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * ch, y, z, y == floor + 1 && z > front && z < back ? pane : log);
            for (int x = cx - ch + 1; x < cx + ch; x++) {
                if (x != cx || y > floor + 1) b.block(x, y, front, plank);
                b.block(x, y, back, y == floor + 1 && Math.abs(x - cx) <= 1 ? pane : plank);
            }
        }
        b.block(cx - ch + 1, floor, back - 1, Blocks.CHEST.defaultBlockState());
        b.block(cx + ch - 1, floor, back - 1, Blocks.FURNACE.defaultBlockState());
        int roof = floor + walls;
        if (ladder) for (int y = floor; y <= roof; y++)
            b.block(cx + ch - 1, y, front - 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        for (int z = front - 1; z <= back; z++) for (int x = cx - ch - 1; x <= cx + ch + 1; x++) b.coreIfEmpty(x, roof, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
        return roof;
    }

    /** Railings and a flag that turn a flat cabin roof into a lookout deck; the ladder lands at the front right. */
    private static void roofDeck(Builder b, Palette palette, int cx, int ch, int roof, int front, int back) {
        for (int z = front - 1; z <= back; z++) for (int sx = -1; sx <= 1; sx += 2) b.decorIfEmpty(cx + sx * (ch + 1), roof + 1, z, palette.railing());
        for (int x = cx - ch; x <= cx + ch; x++) {
            b.decorIfEmpty(x, roof + 1, back, palette.railing());
            if (x != cx + ch - 1) b.decorIfEmpty(x, roof + 1, front - 1, palette.railing());
        }
        b.decorIfEmpty(cx, roof + 1, back - 1, palette.flag());
    }

    /** A solid airship envelope along z with a blunt nose, a tapered tail and an elliptical section; its top row is {@code topY}. */
    private static Set<BlockPos> envelope(int cx, int ze0, int ze1, int ew, int eh, int topY) {
        double a = ew / 2.0, c = eh / 2.0, yc = topY + 1 - c, zc = (ze0 + ze1 + 1) / 2.0, halfLength = (ze1 - ze0 + 1) / 2.0;
        Set<BlockPos> envelope = new HashSet<>();
        for (int z = ze0; z <= ze1; z++) {
            double t = (z + .5 - zc) / halfLength;
            double r = Math.sqrt(Math.max(0, 1 - (t < 0 ? Math.pow(Math.abs(t), 3.5) : t * t)));
            if (r <= 0) continue;
            for (int y = topY + 1 - eh; y <= topY; y++) for (int x = cx - ew / 2; x <= cx + ew / 2; x++) {
                double dx = (x - cx) / (a * r), dy = (y + .5 - yc) / (c * r);
                if (dx * dx + dy * dy <= 1) envelope.add(new BlockPos(x, y, z));
            }
        }
        return envelope;
    }

    /** Flat trapdoor fins either side of an envelope's last three rows. */
    private static void fins(Builder b, Set<BlockPos> envelope, int cx, int finY, int span) {
        List<Integer> rows = envelope.stream().map(BlockPos::getZ).distinct().sorted().toList();
        for (int z : rows.subList(Math.max(0, rows.size() - 3), rows.size())) {
            OptionalInt edge = envelope.stream().filter(p -> p.getZ() == z && p.getY() == finY).mapToInt(BlockPos::getX).max();
            if (edge.isEmpty()) continue;
            int reach = edge.getAsInt() - cx;
            for (int o = reach + 1; o < reach + 1 + span; o++)
                for (int sx = -1; sx <= 1; sx += 2) b.block(cx + sx * o, finY, z, Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
        }
    }

    /**
     * Two posts carrying an envelope at row {@code fz}, joined by a crossbeam under it when the beam clears
     * {@code minBeamY}. A post standing on a bulwark starts above it.
     */
    private static void frame(Builder b, Set<BlockPos> envelope, int left, int right, int fz, int baseY, double minBeamY) {
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        int beamY = Integer.MAX_VALUE;
        for (int x = left; x <= right; x++) if (hasColumn(envelope, x, fz)) beamY = Math.min(beamY, lowest(envelope, x, fz) - 1);
        if (beamY == Integer.MAX_VALUE) throw new IllegalStateException("No envelope above the frame at z=" + fz);
        boolean beamed = beamY >= minBeamY || !hasColumn(envelope, left, fz) || !hasColumn(envelope, right, fz);
        for (int x : new int[] {left, right}) {
            int from = b.occupied(x, baseY, fz) ? baseY + 1 : baseY;
            b.fill(x, from, fz, x, beamed ? beamY - 1 : lowest(envelope, x, fz) - 1, fz, log);
        }
        if (!beamed) return;
        b.fill(left, beamY, fz, right, beamY, fz, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        if (beamY - 1 >= baseY + 2)                                 // hang lanterns only above the railings
            for (int x : new int[] {left + 1, right - 1})
                b.decorIfEmpty(x, beamY - 1, fz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private static boolean hasColumn(Set<BlockPos> cells, int x, int z) {
        return cells.stream().anyMatch(cell -> cell.getX() == x && cell.getZ() == z);
    }

    private static int centeredX(DockTier tier, int width) { return (tier.width - width) / 2; }
    private record Palette(BlockState helm, BlockState seat, BlockState ballast, BlockState lift,
                           BlockState brassEngine, BlockState reinforcedEngine, BlockState turbineEngine, BlockState aetherEngine,
                           BlockState lantern, BlockState awning, BlockState railing, BlockState flag, BlockState clamp) {
        /** The helm model's wheel faces north; turn it toward a pilot standing aft and looking at the bow. */
        private BlockState aftHelm() { return helm.trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH); }
        private BlockState engine(DockTier tier) {
            return switch (tier) {
                case SCOUT -> brassEngine;
                case BRIG -> reinforcedEngine;
                case CRUISER -> turbineEngine;
                case DREADNOUGHT -> aetherEngine;
            };
        }
        private static Palette production() {
            return new Palette(SkydockBlocks.HELM.get().defaultBlockState(), SkydockBlocks.SEAT.get().defaultBlockState(),
                    SkydockBlocks.BALLAST.get().defaultBlockState(), SkydockBlocks.LIFT_CELL.get().defaultBlockState(),
                    SkydockBlocks.ENGINE.get().defaultBlockState(), SkydockBlocks.ENGINE_REINFORCED.get().defaultBlockState(),
                    SkydockBlocks.ENGINE_TURBINE.get().defaultBlockState(), SkydockBlocks.ENGINE_AETHER.get().defaultBlockState(),
                    SkydockBlocks.BRASS_LANTERN.get().defaultBlockState(), SkydockBlocks.CANVAS_AWNING.get().defaultBlockState(),
                    SkydockBlocks.TIMBER_RAILING.get().defaultBlockState(), SkydockBlocks.SIGNAL_FLAG.get().defaultBlockState(),
                    SkydockBlocks.CLAMP.get().defaultBlockState());
        }
        private static Palette vanillaTestPalette() {
            return new Palette(Blocks.LECTERN.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(), Blocks.IRON_BLOCK.defaultBlockState(),
                    Blocks.WHITE_WOOL.defaultBlockState(), Blocks.PISTON.defaultBlockState(), Blocks.STICKY_PISTON.defaultBlockState(),
                    Blocks.BLAST_FURNACE.defaultBlockState(), Blocks.SMOKER.defaultBlockState(), Blocks.LANTERN.defaultBlockState(),
                    Blocks.CYAN_WOOL.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(), Blocks.CYAN_BANNER.defaultBlockState(),
                    Blocks.LODESTONE.defaultBlockState());
        }
    }

    private static final class Builder {
        private final DockTier tier;
        private final Palette palette;
        private final LinkedHashMap<BlockPos, ShipPattern.Block> cells = new LinkedHashMap<>();

        private Builder(DockTier tier, Palette palette) { this.tier = tier; this.palette = palette; }

        private void block(int x, int y, int z, BlockState state) { put(x, y, z, state, false, false); }
        private void decorIfEmpty(int x, int y, int z, BlockState state) { put(x, y, z, state, true, true); }
        private void coreIfEmpty(int x, int y, int z, BlockState state) { put(x, y, z, state, false, true); }
        private boolean occupied(int x, int y, int z) { return cells.containsKey(new BlockPos(x, y, z)); }
        private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) block(x, y, z, state);
        }
        private void decorFill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) decorIfEmpty(x, y, z, state);
        }
        private void put(int x, int y, int z, BlockState state, boolean decoration, boolean onlyIfEmpty) {
            BlockPos pos = new BlockPos(x, y, z);
            ShipPattern.Block previous = cells.get(pos);
            if (previous == null) cells.put(pos, new ShipPattern.Block(pos, state, decoration));
            else if (!onlyIfEmpty && (previous.state().getBlock() != state.getBlock() || previous.decoration() != decoration))
                throw new IllegalStateException("Conflicting authored blocks at " + pos + ": " + previous.state().getBlock() + " and " + state.getBlock());
        }
        private void stair(int x, int y, int z, Direction facing) {
            block(x, y, z, Blocks.SPRUCE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT));
        }
        private void support(int x, int minY, int maxY, int z) {
            fill(x, minY, z, x, maxY, z, Blocks.SPRUCE_LOG.defaultBlockState());
        }
        private void balloonArray(int x, int y, int z, int rowsX, int rowsZ) {
            fill(x, y, z, x + rowsX * 2 - 1, y + rowsX * 2 - 1, z + rowsZ * 2 - 1, palette.lift());
        }
        private ShipPattern pattern(String id, String name, String description) {
            resolveNeighborStates();
            return new ShipPattern(Skydock.id(id), name, description, tier, List.copyOf(cells.values()));
        }
        private void resolveNeighborStates() {
            for (Map.Entry<BlockPos, ShipPattern.Block> entry : cells.entrySet()) {
                ShipPattern.Block cell = entry.getValue();
                BlockState state = cell.state();
                if (state.getBlock() == palette.lift().getBlock() && state.hasProperty(LiftCellBlock.NORTH)) {
                    state = state.setValue(LiftCellBlock.NORTH, isLift(entry.getKey().north()));
                    state = state.setValue(LiftCellBlock.EAST, isLift(entry.getKey().east()));
                    state = state.setValue(LiftCellBlock.SOUTH, isLift(entry.getKey().south()));
                    state = state.setValue(LiftCellBlock.WEST, isLift(entry.getKey().west()));
                    state = state.setValue(LiftCellBlock.UP, isLift(entry.getKey().above()));
                    state = state.setValue(LiftCellBlock.DOWN, isLift(entry.getKey().below()));
                } else if (state.getBlock() instanceof IronBarsBlock) {
                    IronBarsBlock pane = (IronBarsBlock) state.getBlock();
                    state = state.setValue(CrossCollisionBlock.NORTH, paneConnects(pane, entry.getKey().north(), Direction.SOUTH));
                    state = state.setValue(CrossCollisionBlock.EAST, paneConnects(pane, entry.getKey().east(), Direction.WEST));
                    state = state.setValue(CrossCollisionBlock.SOUTH, paneConnects(pane, entry.getKey().south(), Direction.NORTH));
                    state = state.setValue(CrossCollisionBlock.WEST, paneConnects(pane, entry.getKey().west(), Direction.EAST));
                } else if (state.getBlock() instanceof FenceBlock) {
                    state = FenceConnections.resolve(state, entry.getKey(), this::stateAt, EmptyBlockGetter.INSTANCE);
                }
                entry.setValue(new ShipPattern.Block(entry.getKey(), state, cell.decoration()));
            }
        }
        private boolean isLift(BlockPos pos) {
            ShipPattern.Block cell = cells.get(pos);
            return cell != null && cell.state().getBlock() == palette.lift().getBlock();
        }
        private BlockState stateAt(BlockPos pos) {
            ShipPattern.Block cell = cells.get(pos);
            return cell == null ? Blocks.AIR.defaultBlockState() : cell.state();
        }
        private boolean paneConnects(IronBarsBlock pane, BlockPos neighborPos, Direction faceTowardPane) {
            ShipPattern.Block neighbor = cells.get(neighborPos);
            return neighbor != null && pane.attachsTo(neighbor.state(),
                    neighbor.state().isFaceSturdy(EmptyBlockGetter.INSTANCE, neighborPos, faceTowardPane));
        }
    }
}
