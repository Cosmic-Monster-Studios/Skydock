package dev.skydock.ship;

import dev.skydock.data.DockTier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummonTargetTest {
    private static final BlockPos DOCK = new BlockPos(10, 64, -20);

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Ship launched() {
        Ship ship = new Ship(); ship.tier = DockTier.SCOUT; ship.dimension = Level.OVERWORLD; ship.dock = DOCK; return ship;
    }

    @Test void picksTheActiveShipFromThatDock() { assertTrue(ShipManager.summonable(launched(), Level.OVERWORLD, DOCK)); }
    @Test void ignoresOtherDocksAndDimensions() {
        assertFalse(ShipManager.summonable(launched(), Level.OVERWORLD, DOCK.east()));
        assertFalse(ShipManager.summonable(launched(), Level.NETHER, DOCK));
    }
    @Test void ignoresShipsMidTransfer() {
        Ship ship = launched(); ship.phase = "redocking";
        assertFalse(ShipManager.summonable(ship, Level.OVERWORLD, DOCK));
    }
    @Test void anythingStandingOnTheDeckOrInsideTheHullIsAboard() {
        AABB hull = new AABB(0, 60, 0, 8, 66, 12), deck = ShipManager.deckBox(hull);
        assertEquals(new AABB(0, 60, 0, 8, 68, 12), deck, "two blocks above the hull top, nothing beside or below it");
        assertTrue(deck.intersects(new AABB(3.7, 66, 4.7, 4.3, 67.8, 5.3)), "a villager standing on deck");
        assertTrue(deck.intersects(new AABB(3.7, 61, 4.7, 4.3, 62.8, 5.3)), "a pet in the cabin");
        assertFalse(deck.intersects(new AABB(9.7, 60, 4.7, 10.3, 61.8, 5.3)), "a mob on the ground beside the hull");
    }
    /** A deck row eight blocks long with a mast four high at its bow, turned 45 degrees so its world box takes in a lot of empty air. */
    private static Ship masted() {
        Ship ship = launched();
        for (int x = 0; x < 8; x++) ship.blocks.put(new BlockPos(x, 0, 0), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
        for (int y = 1; y <= 4; y++) ship.blocks.put(new BlockPos(0, y, 0), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
        ship.initializePivot(); ship.pose = new ShipPose(100.5, 70, 40.5, 45); ship.previousPose = ship.pose;
        return ship;
    }
    private static AABB creature(Ship ship, double x, double y, double z) {
        net.minecraft.world.phys.Vec3 feet = ship.blockToWorld(new net.minecraft.world.phys.Vec3(x, y, z));
        return new AABB(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.8, feet.z + .3);
    }
    @Test void onlyCreaturesOnOrInTheHullBlocksAreAboard() {
        Ship ship = masted();
        AABB deck = ShipManager.deckBox(ship.hullWorldBounds());
        AABB onDeck = creature(ship, 5.5, 1, .5), inMast = creature(ship, .5, 2, .5), bat = new AABB(creature(ship, 6.5, 3, .5).getCenter(), creature(ship, 6.5, 3, .5).getCenter()).inflate(.25);
        assertTrue(ShipManager.onHull(ship, ship.pose, onDeck), "a villager standing on the deck");
        assertTrue(ShipManager.onHull(ship, ship.pose, inMast), "a mob caught in the mast");
        assertTrue(deck.intersects(bat), "the bat is inside the old aboard box");
        assertFalse(ShipManager.onHull(ship, ship.pose, bat), "a bat in the air above the deck, beside the mast");
        // of the box's two corners off the turned deck line, the one far from both of its ends
        AABB box = ship.hullWorldBounds(); net.minecraft.world.phys.Vec3 bow = ship.blockToWorld(new net.minecraft.world.phys.Vec3(.5, 1, .5)), stern = ship.blockToWorld(new net.minecraft.world.phys.Vec3(7.5, 1, .5));
        double cx = box.minX + .5, cz = box.maxZ - .5;
        if (Math.min(Math.hypot(cx - bow.x, cz - bow.z), Math.hypot(cx - stern.x, cz - stern.z)) < 3) { cx = box.maxX - .5; cz = box.minZ + .5; }
        AABB besideDiagonal = new AABB(cx - .3, 71, cz - .3, cx + .3, 72.8, cz + .3);
        assertTrue(deck.intersects(besideDiagonal)); assertFalse(ShipManager.onHull(ship, ship.pose, besideDiagonal), "a mob in a corner of the box the turned hull leaves empty");
    }
    @Test void aCreatureInsideTheHullTouchingNoBlockIsAboard() {
        Ship ship = masted(); ship.blocks.put(new BlockPos(4, 3, 0), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
        ship.blocks.put(new BlockPos(6, 4, 0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        net.minecraft.world.phys.Vec3 roost = ship.blockToWorld(new net.minecraft.world.phys.Vec3(4.5, 2, .5));
        AABB hop = creature(ship, 6.5, 1.6, .5), bat = new AABB(roost.x - .25, roost.y, roost.z - .25, roost.x + .25, roost.y + .9, roost.z + .25);
        assertFalse(ShipManager.onHull(ship, ship.pose, hop)); assertFalse(ShipManager.onHull(ship, ship.pose, bat));
        assertTrue(ShipManager.inHullCell(ship, ship.pose, ship.blockToWorld(new net.minecraft.world.phys.Vec3(6.5, 1.6, .5))), "a mob mid-hop over the deck");
        assertTrue(ShipManager.inHullCell(ship, ship.pose, roost), "a bat hanging under a ceiling");
        assertTrue(ShipManager.inHullCell(ship, ship.pose, ship.blockToWorld(new net.minecraft.world.phys.Vec3(2.5, .4, .5))), "a fish in a cell of the hull");
        assertFalse(ShipManager.inHullCell(ship, ship.pose, ship.blockToWorld(new net.minecraft.world.phys.Vec3(6.5, 3.2, .5))), "a stored air state is no hull");
        assertFalse(ShipManager.inHullCell(ship, ship.pose, ship.blockToWorld(new net.minecraft.world.phys.Vec3(5.5, 2.1, 1.5))), "a bat in the air beside the deck");
        assertFalse(ShipManager.inHullCell(ship, ship.pose, ship.blockToWorld(new net.minecraft.world.phys.Vec3(5.5, -2.1, .5))), "a mob two blocks under the keel");
    }
    @Test void onlyLivingCreaturesBlockASummon() {
        Class<?> player = net.minecraft.world.entity.player.Player.class, pig = net.minecraft.world.entity.animal.Pig.class, stand = net.minecraft.world.entity.decoration.ArmorStand.class;
        assertTrue(ShipManager.blocksSummon(player, true, false, false, true), "a player standing on the deck");
        assertTrue(ShipManager.blocksSummon(pig, true, false, true, false), "a pig attached to the ship");
        assertTrue(ShipManager.blocksSummon(stand, true, false, false, true), "an armor stand on the deck");
        assertFalse(ShipManager.blocksSummon(pig, true, false, false, false), "a pig on the ground beside the hull");
        assertFalse(ShipManager.blocksSummon(pig, false, false, true, true), "a dead pig");
        assertFalse(ShipManager.blocksSummon(player, true, true, true, true), "a spectator");
        for (Class<?> loose : new Class<?>[]{net.minecraft.world.entity.item.ItemEntity.class, net.minecraft.world.entity.ExperienceOrb.class,
                net.minecraft.world.entity.vehicle.Boat.class, net.minecraft.world.entity.vehicle.Minecart.class})
            assertFalse(ShipManager.blocksSummon(loose, true, false, true, true), loose.getSimpleName() + " attached to or resting on the hull does not block");
    }
    @Test void onlyNonLivingThingsAttachedToThisShipOrRestingOnItGoWithIt() {
        Class<?> item = net.minecraft.world.entity.item.ItemEntity.class, cart = net.minecraft.world.entity.vehicle.Minecart.class;
        assertTrue(ShipManager.carriedWith(item, true, true, false, false), "an item attached to this ship");
        assertTrue(ShipManager.carriedWith(item, true, false, false, true), "an item resting on the hull");
        assertTrue(ShipManager.carriedWith(cart, true, false, false, true), "a minecart on the deck");
        assertFalse(ShipManager.carriedWith(item, true, false, false, false), "an item on the ground, or under the keel, stays");
        assertFalse(ShipManager.carriedWith(item, true, false, true, true), "an item attached to another ship stays with that ship");
        assertFalse(ShipManager.carriedWith(item, true, true, true, true), "never taken from the ship it is attached to");
        assertFalse(ShipManager.carriedWith(item, false, true, false, true), "an entity attachable() refuses (a passenger, noPhysics, a spectator) is not attached to this ship");
        assertFalse(ShipManager.carriedWith(net.minecraft.world.entity.animal.Pig.class, true, true, false, true), "creatures never ride along; they block the summon instead");
    }
    @Test void hangingPodDisplayMarkerAndInteractionEntitiesAreNeverCarried() {
        for (Class<?> type : new Class<?>[]{net.minecraft.world.entity.decoration.ItemFrame.class, net.minecraft.world.entity.decoration.GlowItemFrame.class,
                net.minecraft.world.entity.decoration.Painting.class, net.minecraft.world.entity.decoration.LeashFenceKnotEntity.class, dev.skydock.cargo.PodEntity.class,
                net.minecraft.world.entity.Display.BlockDisplay.class, net.minecraft.world.entity.Display.ItemDisplay.class, net.minecraft.world.entity.Display.TextDisplay.class,
                net.minecraft.world.entity.Marker.class, net.minecraft.world.entity.Interaction.class}) {
            assertTrue(ShipManager.neverCarried(type), type.getSimpleName());
            assertFalse(ShipManager.carriedWith(type, true, true, false, true), type.getSimpleName() + " stays even when attached or supported");
        }
        assertFalse(ShipManager.neverCarried(net.minecraft.world.entity.item.ItemEntity.class)); assertFalse(ShipManager.neverCarried(net.minecraft.world.entity.vehicle.Boat.class));
    }
}
