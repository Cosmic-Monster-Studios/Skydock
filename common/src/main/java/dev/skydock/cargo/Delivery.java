package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import java.util.Set;
import java.util.UUID;

/** Pure delivery accounting: mutates the deliverer's reputation and the contract, never items or chat. */
public final class Delivery {
    /** {@code crate} is null when no crate is owed yet; {@code settled} tells the caller to drop the contract from its holder's profile. */
    public record Result(int reputation, CrateRank crate, boolean full, boolean settled) {}
    private static final Result NOTHING = new Result(0, null, false, false);
    private Delivery() {}

    public static Result deliver(CargoProfile deliverer, UUID delivererId, Contract contract, PodTag pod, boolean byHolder, boolean late, GlobalPos at) {
        if (contract.delivered.contains(pod.index()) || contract.lost.contains(pod.index()) || at.equals(contract.origin)) return NOTHING;
        int full = CargoRanks.reputation(rankOf(contract.rankLevel), distance(contract.origin, contract.destination));
        if (!byHolder) {
            int pay = full / 2; deliverer.reputation += pay; contract.lost.add(pod.index());
            CrateRank crate = contract.strangersPaid.add(delivererId) ? contract.crate.lower() : null;
            return new Result(pay, crate, false, contract.settled());
        }
        boolean reduced = late || pod.ejected();
        int pay = reduced ? full / 2 : full; if (reduced) contract.anyReduced = true;
        deliverer.reputation += pay; contract.delivered.add(pod.index());
        boolean settled = contract.settled();
        CrateRank crate = settled ? (contract.anyReduced || !contract.lost.isEmpty() ? contract.crate.lower() : contract.crate) : null;
        return new Result(pay, crate, !reduced, settled);
    }

    /** A pod whose contract is gone: half of the lowest rank's base unless the id was abandoned or this is its origin. */
    public static Result orphan(CargoProfile deliverer, long contractId, GlobalPos origin, GlobalPos at, Set<Long> voided) {
        if (voided.contains(contractId) || at.equals(origin)) return NOTHING;
        int pay = CargoRanks.ranks().getFirst().basePerPod() / 2; deliverer.reputation += pay; return new Result(pay, null, false, false);
    }

    private static Rank rankOf(int level) { for (Rank r : CargoRanks.ranks()) if (r.level() == level) return r; return CargoRanks.ranks().getFirst(); }
    private static double distance(GlobalPos a, GlobalPos b) { double dx = a.pos().getX() - b.pos().getX(), dz = a.pos().getZ() - b.pos().getZ(); return Math.sqrt(dx * dx + dz * dz); }
}
