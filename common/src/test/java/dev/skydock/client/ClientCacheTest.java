package dev.skydock.client;

import dev.skydock.cargo.CargoNetwork;
import dev.skydock.cargo.CargoProfile;
import dev.skydock.cargo.CargoRanks;
import dev.skydock.cargo.CrateRank;
import dev.skydock.cargo.Rank;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The per-world client caches that leaving a world clears. */
class ClientCacheTest {
    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void dockSnapshotsAreForgottenOnClear() {
        CompoundTag data = new CompoundTag(); data.putInt("Container", 3); data.putLong("Dock", new BlockPos(10, 64, -4).asLong()); data.putString("Tier", "SKIFF");
        DockView.receive(data);
        assertNotNull(DockView.get(3, new BlockPos(10, 64, -4)));
        DockView.clear();
        assertNull(DockView.get(3, new BlockPos(10, 64, -4)), "a later world reusing container 3 at this dock sees no stale snapshot");
    }

    @Test void syncedCargoStateIsForgottenOnClear() {
        CargoProfile profile = new CargoProfile(); profile.reputation = 900;
        CompoundTag tag = profile.save(); ListTag ranks = new ListTag(); ranks.add(new Rank(1, "rank.other", 0, 50, 90, 1, CrateRank.TIN, 3).save()); tag.put("Ranks", ranks);
        ClientCargo.receive(new CargoNetwork.Sync(tag, List.of(new CargoNetwork.Destination(4, Level.OVERWORLD, BlockPos.ZERO))));
        assertEquals(900, ClientCargo.profile().reputation); assertNotNull(ClientCargo.pad(4));
        ClientCargo.clear();
        assertEquals(0, ClientCargo.profile().reputation); assertNull(ClientCargo.pad(4));
        assertEquals(CargoRanks.defaults(), ClientCargo.ranks()); assertTrue(ClientCargo.shop().isEmpty());
    }
}
