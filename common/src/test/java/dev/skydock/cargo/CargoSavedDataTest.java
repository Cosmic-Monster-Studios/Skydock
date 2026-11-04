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
        assertEquals(c.reputationPerPod, bc.reputationPerPod);
    }
    @Test void contractPayKeepsItsOfferRateAndOldSavesFallBackToTheRank() {
        GlobalPos a = GlobalPos.of(Level.OVERWORLD, new BlockPos(0, 64, 0)), b = GlobalPos.of(Level.OVERWORLD, new BlockPos(1042, 64, 0));
        Contract c = new Contract(9, a, b, 1, CrateRank.COPPER, "cargo.skydock.grain", 1, 0, 43);
        assertEquals(43, Contract.load(c.save()).reputationPerPod);
        net.minecraft.nbt.CompoundTag old = c.save(); old.remove("Reputation");
        assertEquals(CargoRanks.reputation(CargoRanks.ranks().getFirst(), 1042), Contract.load(old).reputationPerPod);
    }
    @Test void podTagSurvivesSaveAndLoad() {
        PodTag tag = new PodTag(1L << 40, 3, true); assertEquals(tag, PodTag.load(tag.save()));
    }
    @Test void anEmptyBoardKeepsItsSearchWaitAcrossARestart() {
        GlobalPos a = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 2, 3)); CargoProfile p = new CargoProfile();
        Offers.refreshIfDue(p, a, 5000, List::of);
        CargoProfile back = CargoProfile.load(p.save());
        assertEquals(List.of(), back.offers.get(a));
        assertTrue(CargoService.siteSearchWaits(back, a, 5000 + Offers.DAY_TICKS - 1));
    }
    @Test void oneBadEntryIsSkippedAndTheRestLoads() {
        GlobalPos a = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 3)), b = GlobalPos.of(Level.OVERWORLD, new BlockPos(900, 64, 0));
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(a, a.pos()); data.registerTerminal(b, b.pos());
        java.util.UUID good = java.util.UUID.randomUUID(), bad = java.util.UUID.randomUUID();
        CargoProfile p = data.profile(good); p.reputation = 77;
        p.offers.put(a, new java.util.ArrayList<>(List.of(new Offer(3, b, 1, CrateRank.TIN, "cargo.skydock.grain", 1, 2, 10), new Offer(4, b, 1, CrateRank.TIN, "cargo.skydock.grain", 1, 2, 10))));
        p.contracts.put(5L, new Contract(5, a, b, 1, CrateRank.TIN, "cargo.skydock.grain", 1, 0)); p.contracts.put(6L, new Contract(6, a, b, 1, CrateRank.TIN, "cargo.skydock.grain", 1, 0));
        p.pendingCrates.add(CrateRank.GOLD); data.profile(bad).reputation = 5;
        net.minecraft.nbt.CompoundTag tag = data.save(new net.minecraft.nbt.CompoundTag(), null);
        tag.getList("Terminals", 10).getCompound(1).putString("Pos", "nowhere");
        net.minecraft.nbt.ListTag profiles = tag.getList("Profiles", 10);
        net.minecraft.nbt.CompoundTag goodProfile = profiles.getCompound(0).getCompound("Profile");
        goodProfile.getList("Boards", 10).getCompound(0).getList("Offers", 10).getCompound(0).putString("Crate", "PLATINUM");
        goodProfile.getList("Contracts", 10).getCompound(1).putString("Crate", "PLATINUM");
        goodProfile.getList("Pending", 8).add(net.minecraft.nbt.StringTag.valueOf("PLATINUM"));
        profiles.getCompound(1).remove("Player");
        CargoSavedData back = CargoSavedData.load(tag, null);
        assertEquals(java.util.Set.of(a), back.terminals.keySet());
        assertEquals(java.util.Set.of(good), back.profiles.keySet());
        CargoProfile q = back.profiles.get(good);
        assertEquals(77, q.reputation); assertEquals(List.of(4L), q.offers.get(a).stream().map(Offer::id).toList());
        assertEquals(java.util.Set.of(5L), q.contracts.keySet()); assertEquals(List.of(CrateRank.GOLD), q.pendingCrates);
    }
    @Test void boardsAtTerminalsThatAreGoneAreDroppedOnLoad() {
        GlobalPos a = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 3)), gone = GlobalPos.of(Level.OVERWORLD, new BlockPos(900, 64, 0));
        CargoSavedData data = new CargoSavedData(); data.registerTerminal(a, a.pos());
        CargoProfile p = data.profile(java.util.UUID.randomUUID());
        p.offers.put(a, List.of()); p.offersRefreshed.put(a, 1L); p.offers.put(gone, List.of()); p.offersRefreshed.put(gone, 1L);
        CargoProfile back = CargoSavedData.load(data.save(new net.minecraft.nbt.CompoundTag(), null), null).profiles.values().iterator().next();
        assertEquals(java.util.Set.of(a), back.offers.keySet()); assertEquals(java.util.Set.of(a), back.offersRefreshed.keySet());
    }
}
