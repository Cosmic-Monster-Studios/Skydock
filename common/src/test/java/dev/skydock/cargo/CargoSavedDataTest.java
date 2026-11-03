package dev.skydock.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CargoSavedDataTest {
    @Test void profileWithContractsAndOffersSurvivesSaveAndLoad() {
        GlobalPos a = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 2, 3)), b = GlobalPos.of(Level.NETHER, new BlockPos(-600, 90, 40));
        CargoProfile p = new CargoProfile(); p.reputation = 150; p.spent = 40; p.unlocked.add(ResourceLocation.parse("skydock:wave"));
        p.offers.put(a, List.of(new Offer(3, b, 2, CrateRank.COPPER, "cargo.skydock.grain", 4, 2, 25))); p.offersRefreshed.put(a, 12000L);
        Contract c = new Contract(5, a, b, 2, CrateRank.COPPER, "cargo.skydock.grain", 3, 48000); c.delivered.add(0); c.lost.add(1); c.anyReduced = true;
        c.strangersPaid.add(java.util.UUID.randomUUID()); p.contracts.put(5L, c); p.pendingCrates.add(CrateRank.GOLD); p.pendingCrates.add(CrateRank.TIN);
        CargoProfile back = CargoProfile.load(p.save());
        assertEquals(150, back.reputation); assertEquals(40, back.spent); assertEquals(110, back.balance()); assertEquals(2, back.peakRank());
        assertEquals(p.offers, back.offers); assertEquals(p.offersRefreshed, back.offersRefreshed); assertEquals(p.unlocked, back.unlocked); assertEquals(p.pendingCrates, back.pendingCrates);
        Contract bc = back.contracts.get(5L);
        assertEquals(a, bc.origin); assertEquals(b, bc.destination); assertEquals(CrateRank.COPPER, bc.crate); assertEquals(3, bc.pods); assertEquals(48000, bc.deadline);
        assertEquals(c.delivered, bc.delivered); assertEquals(c.lost, bc.lost); assertTrue(bc.anyReduced); assertEquals(c.strangersPaid, bc.strangersPaid); assertFalse(bc.settled());
    }
    @Test void podTagSurvivesSaveAndLoad() {
        PodTag tag = new PodTag(1L << 40, 3, true); assertEquals(tag, PodTag.load(tag.save()));
    }
}
