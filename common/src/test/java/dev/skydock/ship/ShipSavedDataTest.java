package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ShipSavedDataTest {
    private static HolderLookup.Provider registries;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.BLOCK.asLookup()));
    }

    private static CompoundTag ship(BlockPos yard) {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.dock = BlockPos.ZERO; ship.yard = yard;
        ship.pose = new ShipPose(0, 80, 0, 0); ship.previousPose = ship.pose;
        ship.blocks.put(BlockPos.ZERO, Blocks.SPRUCE_PLANKS.defaultBlockState());
        return ship.save(registries, true);
    }

    /** One record that throws on load costs only itself: the rest load, it is saved back untouched, and no new ship takes its yard. */
    @Test void aCorruptShipIsQuarantinedAndKept() {
        CompoundTag good = ship(new BlockPos(256, 64, 0)), corrupt = ship(new BlockPos(5 * 256, 64, 2 * 256));
        corrupt.putString("Tier", "NO_SUCH_TIER");
        ListTag ships = new ListTag(); ships.add(good); ships.add(corrupt);
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships); tag.putInt("NextRegion", 0);

        ShipSavedData data = ShipSavedData.load(tag, registries);
        assertEquals(1, data.ships.size());
        assertTrue(data.ships.containsKey(good.getUUID("Id")));
        assertEquals(1, data.quarantined.size());
        assertEquals(2006, data.nextRegion, "the quarantined ship's region 2005 stays taken");

        ListTag saved = data.save(new CompoundTag(), registries).getList("Ships", Tag.TAG_COMPOUND);
        assertEquals(2, saved.size());
        assertTrue(saved.contains(corrupt), "the bad record is written back as it was");
        ShipSavedData again = ShipSavedData.load(data.save(new CompoundTag(), registries), registries);
        assertEquals(1, again.ships.size()); assertEquals(1, again.quarantined.size()); assertEquals(2006, again.nextRegion);
    }

    @Test void nextRegionNeverFallsBehindALoadedShip() {
        ListTag ships = new ListTag(); ships.add(ship(new BlockPos(3 * 256, 64, 256)));
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships); tag.putInt("NextRegion", 0);
        assertEquals(1004, ShipSavedData.load(tag, registries).nextRegion);
        tag.putInt("NextRegion", 4000);
        assertEquals(4000, ShipSavedData.load(tag, registries).nextRegion);
        assertEquals(0, ShipSavedData.load(new CompoundTag(), registries).nextRegion);
    }

    @Test void regionsMatchTheYardLayout() {
        for (int region : new int[] {0, 1, 999, 1000, 123_456, 999_999})
            assertEquals(region, ShipSavedData.regionOf(new BlockPos((region % 1000) * 256, 64, (region / 1000) * 256)));
    }

    /** A quarantined record keeps its dock's berth: no new ship launches there and no other ship redocks into it. */
    @Test void aQuarantinedShipStillReservesItsBerth() {
        BlockPos dock = new BlockPos(40, 70, -12);
        CompoundTag corrupt = ship(new BlockPos(256, 64, 0)); corrupt.putLong("Dock", dock.asLong()); corrupt.putString("Tier", "NO_SUCH_TIER");
        ListTag ships = new ListTag(); ships.add(corrupt);
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships);
        ShipSavedData data = ShipSavedData.load(tag, registries);
        assertTrue(data.ships.isEmpty());
        assertTrue(data.reserves(Level.OVERWORLD, dock, null));
        assertTrue(data.reserves(Level.OVERWORLD, dock, java.util.UUID.randomUUID()), "a redocking ship cannot take it either");
        assertFalse(data.reserves(Level.NETHER, dock, null));
        assertFalse(data.reserves(Level.OVERWORLD, dock.east(), null));
    }
    @Test void aLoadedShipReservesItsBerthExceptFromItself() {
        CompoundTag good = ship(new BlockPos(256, 64, 0));
        ListTag ships = new ListTag(); ships.add(good);
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships);
        ShipSavedData data = ShipSavedData.load(tag, registries);
        assertTrue(data.reserves(Level.OVERWORLD, BlockPos.ZERO, null));
        assertFalse(data.reserves(Level.OVERWORLD, BlockPos.ZERO, good.getUUID("Id")), "a ship redocking into its own berth");
    }
    @Test void aRawRecordsBerthIsReadDefensively() {
        CompoundTag raw = new CompoundTag();
        assertNull(ShipSavedData.berthOf(raw), "no dock, no reservation");
        raw.putString("Dock", "not a number");
        assertNull(ShipSavedData.berthOf(raw));
        raw.putLong("Dock", new BlockPos(1, 2, 3).asLong());
        ShipSavedData.Berth anywhere = ShipSavedData.berthOf(raw);
        assertTrue(anywhere.is(Level.OVERWORLD, new BlockPos(1, 2, 3)) && anywhere.is(Level.NETHER, new BlockPos(1, 2, 3)), "an unreadable dimension reserves the spot everywhere");
        raw.putString("Dimension", "Not A Valid:Key!");
        assertTrue(ShipSavedData.berthOf(raw).is(Level.END, new BlockPos(1, 2, 3)));
        raw.putString("Dimension", "minecraft:the_nether");
        ShipSavedData.Berth nether = ShipSavedData.berthOf(raw);
        assertTrue(nether.is(Level.NETHER, new BlockPos(1, 2, 3)));
        assertFalse(nether.is(Level.OVERWORLD, new BlockPos(1, 2, 3)));
    }

    private static CompoundTag redocking(BlockPos yard, BlockPos home, BlockPos target, BlockPos origin) {
        Ship ship = Ship.load(ship(yard), registries); ship.dock = home;
        ship.phase = "redocking"; ship.transferDock = target; ship.transferOrigin = origin; ship.transferFacing = net.minecraft.core.Direction.EAST; ship.transferDimension = Level.OVERWORLD;
        return ship.save(registries, true);
    }
    private static ShipSavedData of(CompoundTag... records) {
        ListTag ships = new ListTag(); for (CompoundTag record : records) ships.add(record);
        CompoundTag tag = new CompoundTag(); tag.put("Ships", ships);
        return ShipSavedData.load(tag, registries);
    }
    private static net.minecraft.world.phys.AABB scoutBerth(BlockPos corner) { return new net.minecraft.world.phys.AABB(corner.getX(), corner.getY(), corner.getZ(), corner.getX() + 32, corner.getY() + 20, corner.getZ() + 32); }

    /** A paused redock into another dock holds that dock and anything overlapping its berth, so no launch or assembly there can duplicate the hull. */
    @Test void aPausedRedockHoldsItsTargetBerth() {
        BlockPos target = new BlockPos(500, 70, 0), origin = new BlockPos(531, 71, 1);
        ShipSavedData data = of(redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, target, origin));
        assertFalse(data.reserves(Level.OVERWORLD, target, null), "it is not the ship's home berth");
        assertTrue(data.transferHolds(Level.OVERWORLD, target, scoutBerth(new BlockPos(1000, 0, 1000))), "the target dock itself");
        // Facing east, the envelope runs from the origin toward -X and +Z: x 500..531, z 1..32.
        assertTrue(data.transferHolds(Level.OVERWORLD, new BlockPos(9, 9, 9), scoutBerth(new BlockPos(520, 80, 20))), "another dock whose berth overlaps it");
        assertFalse(data.transferHolds(Level.OVERWORLD, new BlockPos(9, 9, 9), scoutBerth(new BlockPos(532, 71, 1))), "a berth beside it");
        assertFalse(data.transferHolds(Level.NETHER, target, scoutBerth(new BlockPos(520, 80, 20))));
        assertTrue(data.transferProtects(Level.OVERWORLD, origin.offset(-31, 19, 31)));
        assertFalse(data.transferProtects(Level.OVERWORLD, origin.offset(1, 0, 0)));
        assertFalse(of(ship(new BlockPos(256, 64, 0))).transferHolds(Level.OVERWORLD, BlockPos.ZERO, scoutBerth(BlockPos.ZERO)), "an active ship holds nothing as a transfer");
    }
    /** A record quarantined mid-transfer still guards and reserves its transfer berth; what cannot be read widens the guard. */
    @Test void aQuarantinedTransferStillGuardsItsBerth() {
        BlockPos target = new BlockPos(500, 70, 0), origin = new BlockPos(531, 71, 1);
        CompoundTag corrupt = redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, target, origin); corrupt.putString("Tier", "NO_SUCH_TIER");
        ShipSavedData data = of(corrupt);
        assertTrue(data.ships.isEmpty());
        assertTrue(data.transferHolds(Level.OVERWORLD, target, scoutBerth(new BlockPos(1000, 0, 1000))));
        assertTrue(data.transferProtects(Level.OVERWORLD, origin.offset(-95, 47, 95)), "an unreadable tier guards the largest envelope");
        corrupt.putString("TransferFacing", "sideways"); corrupt.putString("TransferDimension", "Not A Valid:Key!");
        data = of(corrupt);
        assertTrue(data.transferProtects(Level.NETHER, origin.offset(10, 0, 10)), "an unreadable facing guards every turn, an unreadable dimension every dimension");
        assertFalse(ShipSavedData.midTransfer(ship(BlockPos.ZERO)));
        assertNull(ShipSavedData.transferOf(ship(BlockPos.ZERO)));
    }
    /** A record quarantined mid-transfer is marked held, and if it loads again later it stays held instead of being replayed. */
    @Test void aHealedQuarantinedTransferIsHeld() {
        CompoundTag record = redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, new BlockPos(500, 70, 0), new BlockPos(531, 71, 1));
        CompoundTag corrupt = record.copy(); corrupt.putString("Tier", "NO_SUCH_TIER");
        CompoundTag kept = of(corrupt).save(new CompoundTag(), registries).getList("Ships", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(kept.getBoolean(Ship.HELD));
        kept.putString("Tier", "SCOUT");
        ShipSavedData healed = of(kept);
        Ship ship = healed.ships.get(record.getUUID("Id"));
        assertTrue(ship.transferHeld);
        assertTrue(of(healed.save(new CompoundTag(), registries).getList("Ships", Tag.TAG_COMPOUND).getCompound(0)).ships.get(ship.id).transferHeld, "held survives saving");
        assertFalse(of(record).ships.get(ship.id).transferHeld, "a transfer never quarantined is not held");
        CompoundTag active = ship(BlockPos.ZERO); active.putBoolean(Ship.HELD, true);
        assertFalse(of(active).ships.values().iterator().next().transferHeld, "an active record has nothing to hold");
    }
    /** A quarantined record stays marked quarantined once released, so every replay of that transfer keeps journal contents over bare live data; a retry hold never is. */
    @Test void aReleasedQuarantinedTransferStaysQuarantined() {
        CompoundTag record = redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, new BlockPos(500, 70, 0), new BlockPos(531, 71, 1));
        CompoundTag corrupt = record.copy(); corrupt.putString("Tier", "NO_SUCH_TIER");
        CompoundTag kept = of(corrupt).save(new CompoundTag(), registries).getList("Ships", Tag.TAG_COMPOUND).getCompound(0);
        kept.putString("Tier", "SCOUT");
        Ship ship = of(kept).ships.get(record.getUUID("Id"));
        assertTrue(ship.transferQuarantined);
        ship.transferHeld = false;
        Ship released = Ship.load(ship.save(registries, true), registries);
        assertFalse(released.transferHeld); assertTrue(released.transferQuarantined, "released, it is still a quarantined record");
        CompoundTag legacy = record.copy(); legacy.putBoolean(Ship.HELD, true);
        assertTrue(Ship.load(legacy, registries).transferQuarantined, "a hold from before the marker was a quarantine");
        Ship stuck = Ship.load(record, registries); stuck.transferHeld = true;
        Ship reloaded = Ship.load(stuck.save(registries, true), registries);
        assertTrue(reloaded.transferHeld); assertFalse(reloaded.transferQuarantined, "a transfer held after failed retries was never quarantined");
        assertFalse(Ship.load(record, registries).transferQuarantined);
        released.phase = "launching"; assertFalse(released.saveLaunched(registries).contains(Ship.QUARANTINED));
        released.finishLaunch(); assertFalse(released.transferQuarantined);
    }
    /** A record quarantined mid-transfer marks the data dirty, so its held marker is written; an active one written back as it was does not. */
    @Test void aQuarantinedTransferIsSavedWithItsHold() {
        CompoundTag corrupt = redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, new BlockPos(500, 70, 0), new BlockPos(531, 71, 1)); corrupt.putString("Tier", "NO_SUCH_TIER");
        CompoundTag tag = new CompoundTag(); ListTag ships = new ListTag(); ships.add(corrupt); tag.put("Ships", ships); tag.putInt("NextRegion", 2);
        assertTrue(ShipSavedData.load(tag, registries).isDirty());
        CompoundTag active = ship(new BlockPos(256, 64, 0)); active.putString("Tier", "NO_SUCH_TIER");
        ships = new ListTag(); ships.add(active); tag.put("Ships", ships);
        assertFalse(ShipSavedData.load(tag, registries).isDirty());
    }
    /** A transfer's step marker survives a save and load, and a new phase or a finished launch starts with none. */
    @Test void theStepMarkerIsJournaled() {
        Ship ship = Ship.load(redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, BlockPos.ZERO, new BlockPos(31, 1, 1)), registries);
        assertEquals(Ship.Step.STARTED, ship.transferStep);
        assertFalse(ship.save(registries, true).contains("TransferStep"));
        ship.transferStep = Ship.Step.PASTED;
        assertEquals(Ship.Step.PASTED, Ship.load(ship.save(registries, true), registries).transferStep);
        for (Ship.Step step : Ship.Step.values()) { ship.transferStep = step; assertEquals(step, Ship.load(ship.save(registries, true), registries).transferStep); }
        ship.phase = "launching"; assertFalse(ship.saveLaunched(registries).contains("TransferStep"));
        ship.finishLaunch(); assertEquals(Ship.Step.STARTED, ship.transferStep);
    }
    /** The journal of a phase not yet applied: one ship's record swapped or dropped, the rest as they are, and memory untouched. */
    @Test void aJournalCanCarryTheNextPhase() {
        CompoundTag a = ship(new BlockPos(256, 64, 0)), b = ship(new BlockPos(512, 64, 0));
        ShipSavedData data = of(a, b);
        ListTag dropped = data.save(new CompoundTag(), registries, a.getUUID("Id"), null).getList("Ships", Tag.TAG_COMPOUND);
        assertEquals(1, dropped.size()); assertEquals(b.getUUID("Id"), dropped.getCompound(0).getUUID("Id"));
        CompoundTag swapped = a.copy(); swapped.putString("Phase", "swapped");
        ListTag replaced = data.save(new CompoundTag(), registries, a.getUUID("Id"), swapped).getList("Ships", Tag.TAG_COMPOUND);
        assertEquals(2, replaced.size()); assertTrue(replaced.contains(swapped));
        assertEquals(2, data.ships.size());
    }
    /** The launched record written before memory changes is exactly what the ship saves once the launch is applied. */
    @Test void theLaunchedRecordMatchesTheAppliedLaunch() {
        Ship ship = Ship.load(redocking(new BlockPos(256, 64, 0), BlockPos.ZERO, BlockPos.ZERO, new BlockPos(31, 1, 1)), registries);
        ship.phase = "launching"; ship.transferHeld = true; ship.transferQuarantined = true; ship.transferStep = Ship.Step.CLEARED; ship.transferFailures = 4;
        CompoundTag chest = new CompoundTag(); chest.putString("id", "minecraft:chest"); ship.blockEntities.put(BlockPos.ZERO, chest);
        CompoundTag launched = ship.saveLaunched(registries);
        assertEquals("launching", ship.phase, "building the record changes nothing");
        assertFalse(ship.blockEntities.isEmpty());
        ship.finishLaunch();
        assertEquals(launched, ship.save(registries, true));
    }
}
