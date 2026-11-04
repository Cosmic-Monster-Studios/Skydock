package dev.skydock.ship;

import dev.skydock.data.DockTier;
import dev.skydock.fuzz.Fuzz;
import dev.skydock.fuzz.NbtFuzz;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Ship.save / Ship.load round trips over random hulls, and corrupted ship tags. */
class ShipPersistenceFuzzTest {
    static HolderLookup.Provider registries;
    static List<Block> blocks;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
        blocks = BuiltInRegistries.BLOCK.stream().toList();
    }

    static BlockState state(Random r) { Block b = Fuzz.pick(r, blocks); List<BlockState> states = b.getStateDefinition().getPossibleStates(); return Fuzz.pick(r, states); }
    static BlockPos cell(Random r, DockTier tier) {
        return r.nextInt(20) == 0 ? new BlockPos(r.nextInt(201) - 100, r.nextInt(201) - 100, r.nextInt(201) - 100) : new BlockPos(r.nextInt(tier.width), r.nextInt(tier.height), r.nextInt(tier.length));
    }
    static ResourceKey<Level> dimension(Random r) {
        return Fuzz.pick(r, Level.OVERWORLD, Level.NETHER, Level.END, ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("skydock:shipyard")), ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("mod:a/b.c-d_e")));
    }
    static UUID uuid(Random r) { return new UUID(r.nextLong(), r.nextLong()); }

    static Ship ship(Random r, boolean finite) {
        Ship s = new Ship(); s.id = uuid(r); s.owner = r.nextBoolean() ? uuid(r) : null; s.team = Fuzz.pick(r, "", "red", "Ünïcødé team \"q\"");
        s.tier = Fuzz.pick(r, DockTier.values()); s.dimension = dimension(r);
        s.dock = BlockPos.of(Fuzz.edgyLong(r)); s.yard = BlockPos.of(Fuzz.edgyLong(r)); s.dockFacing = Fuzz.pick(r, Direction.Plane.HORIZONTAL.stream().toList());
        java.util.function.DoubleSupplier d = finite ? () -> Fuzz.worldDouble(r) : () -> Fuzz.edgyDouble(r);
        s.pose = new ShipPose(d.getAsDouble(), d.getAsDouble(), d.getAsDouble(), d.getAsDouble()); s.previousPose = s.pose;
        s.velocity = new Vec3(d.getAsDouble(), d.getAsDouble(), d.getAsDouble()); s.yawVelocity = d.getAsDouble();
        s.cruise = r.nextBoolean(); s.cruiseSpeed = (r.nextDouble() * 2 - 1) * FlightDynamics.MAX_TIER_SPEED;
        s.holdAltitude = r.nextBoolean() ? Double.NaN : d.getAsDouble(); s.headingLock = r.nextBoolean() ? Double.NaN : d.getAsDouble();
        s.mass = d.getAsDouble(); s.lift = d.getAsDouble(); s.moored = r.nextBoolean(); s.blocked = r.nextBoolean();
        s.revision = Fuzz.edgyInt(r); s.cells = Fuzz.edgyInt(r); s.engines = Fuzz.edgyInt(r); s.phase = Fuzz.pick(r, "active", "transfer", "dormant", "");
        if (r.nextBoolean()) { s.transferOrigin = BlockPos.of(Fuzz.edgyLong(r)); s.transferDock = BlockPos.of(Fuzz.edgyLong(r)); s.transferDimension = dimension(r); s.transferFacing = Fuzz.pick(r, Direction.Plane.HORIZONTAL.stream().toList()); }
        if (r.nextBoolean()) { s.pilot = uuid(r); if (r.nextBoolean()) s.pilotAnchor = new Vec3(d.getAsDouble(), d.getAsDouble(), d.getAsDouble()); }
        for (int i = r.nextInt(r.nextInt(10) == 0 ? 2000 : 60); i > 0; i--) s.blocks.put(cell(r, s.tier), state(r));
        for (var e : s.blocks.entrySet()) if (!e.getValue().isAir() && r.nextInt(4) != 0) s.blueprint.put(e.getKey(), r.nextInt(8) == 0 ? state(r) : e.getValue());
        for (int i = r.nextInt(4); i > 0; i--) s.blueprint.put(cell(r, s.tier), state(r));
        for (BlockPos p : s.blocks.keySet()) if (r.nextInt(10) == 0) {
            CompoundTag be = new CompoundTag(); be.putString("id", "minecraft:chest"); be.put("Extra", NbtFuzz.randomTag(r, 0)); s.blockEntities.put(p, be);
        }
        return s;
    }

    static void same(double a, double b, String what) { assertEquals(0, Double.compare(a, b), what + ": " + a + " vs " + b); }
    static void same(Vec3 a, Vec3 b, String what) { if (a == null || b == null) { assertSame(a, b, what); return; } same(a.x, b.x, what); same(a.y, b.y, what); same(a.z, b.z, what); }
    static void same(ShipPose a, ShipPose b, String what) { same(a.x(), b.x(), what); same(a.y(), b.y(), what); same(a.z(), b.z(), what); same(a.yaw(), b.yaw(), what); }

    static void assertSameShip(Ship a, Ship b) {
        assertEquals(a.id, b.id); assertEquals(a.owner, b.owner); assertEquals(a.team, b.team); assertEquals(a.tier, b.tier); assertEquals(a.dimension, b.dimension);
        assertEquals(a.dock, b.dock); assertEquals(a.yard, b.yard); assertEquals(a.dockFacing, b.dockFacing);
        same(a.pose, b.pose, "pose"); same(b.pose, b.previousPose, "previous pose starts at the pose"); same(a.center(), b.center(), "pivot");
        same(a.velocity, b.velocity, "velocity"); same(a.yawVelocity, b.yawVelocity, "yaw velocity"); assertEquals(a.cruise, b.cruise);
        same(Math.clamp(a.cruiseSpeed, -FlightDynamics.MAX_TIER_SPEED, FlightDynamics.MAX_TIER_SPEED), b.cruiseSpeed, "cruise speed");
        same(a.holdAltitude, b.holdAltitude, "hold altitude"); same(a.headingLock, b.headingLock, "heading lock");
        same(a.mass, b.mass, "mass"); same(a.lift, b.lift, "lift"); assertEquals(a.moored, b.moored); assertEquals(a.blocked, b.blocked);
        assertEquals(a.revision, b.revision); assertEquals(a.cells, b.cells); assertEquals(a.engines, b.engines); assertEquals(a.phase, b.phase);
        assertEquals(a.transferOrigin, b.transferOrigin); assertEquals(a.transferDock, b.transferDock); assertEquals(a.transferDimension, b.transferDimension);
        if (a.transferOrigin != null) assertEquals(a.transferFacing, b.transferFacing);
        assertEquals(a.pilot, b.pilot); same(a.pilot == null ? null : a.pilotAnchor, b.pilotAnchor, "pilot anchor");
        assertEquals(new ArrayList<>(a.blocks.entrySet()), new ArrayList<>(b.blocks.entrySet()), "hull cells, in order");
        assertEquals(new ArrayList<>(a.blueprint.entrySet()), new ArrayList<>(b.blueprint.entrySet()), "blueprint cells, in order");
        assertEquals(a.blockEntities, b.blockEntities);
    }

    @Test void randomShipsRoundTripExactly() {
        Fuzz.check("Ship round trip", 1500, (r, log) -> {
            Ship s = ship(r, r.nextInt(4) != 0); if (r.nextBoolean()) s.initializePivot();
            CompoundTag saved = s.save(registries, true); log.append("tier=").append(s.tier).append(" blocks=").append(s.blocks.size()).append(" pose=").append(s.pose);
            Ship back = Ship.load(saved, registries);
            assertSameShip(s, back);
            assertEquals(saved, back.save(registries, true), "a loaded ship saves the same tag");
        });
    }

    @Test void persistedLoadsKeepThePivotAndTheBlocks() {
        Fuzz.check("Ship persisted round trip", 800, (r, log) -> {
            Ship s = ship(r, true); s.initializePivot();
            Ship back = Ship.loadPersisted(s.save(registries, true), registries);
            assertSameShip(s, back);
        });
    }

    @Test void savesWithoutBlocksCarryOnlyTheState() {
        Fuzz.check("Ship save without blocks", 500, (r, log) -> {
            Ship s = ship(r, true); Ship back = Ship.load(s.save(registries, false), registries);
            assertTrue(back.blocks.isEmpty()); assertTrue(back.blueprint.isEmpty()); same(s.pose, back.pose, "pose"); assertEquals(s.id, back.id);
        });
    }

    /** A damaged tag either loads into a ship that can be saved again, or fails with an unchecked exception (never an Error). */
    @Test void corruptedShipTagsFailOnlyWithRuntimeExceptions() {
        int[] loaded = {0};
        Fuzz.check("Ship corrupted", 3000, (r, log) -> {
            Ship s = ship(r, true); CompoundTag bad = NbtFuzz.corrupt(r, s.save(registries, true), Fuzz.pick(r, .02, .1, .3), log);
            Ship back;
            try { back = Ship.loadPersisted(bad, registries); }
            catch (RuntimeException expected) { return; }
            loaded[0]++;
            back.save(registries, true); back.hullBounds(); back.center();
            assertNotNull(back.tier); assertNotNull(back.dockFacing); assertTrue(back.dockFacing.getAxis().isHorizontal());
            if (back.transferOrigin != null) assertTrue(back.transferFacing.getAxis().isHorizontal());
        });
        assertTrue(loaded[0] > 300, "most damaged tags still load: " + loaded[0]);
    }
}
