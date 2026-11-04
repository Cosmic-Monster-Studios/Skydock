package dev.skydock.cargo;

import net.minecraft.core.GlobalPos;
import java.util.Set;
import java.util.UUID;

/** Pure delivery accounting: mutates the deliverer's reputation and the contract, never items or chat. */
public final class Delivery {
    /**
     * @param reputation reputation paid to the deliverer
     * @param crate crate owed to the deliverer, or null when none
     * @param full true when the pod paid its full reward (holder, on time, intact)
     * @param settled true when every pod is accounted for; the caller drops the contract from its holder's profile
     * @param holderCrate completion crate owed to the holder when a stranger's delivery settled the contract, or null
     */
    public record Result(int reputation, CrateRank crate, boolean full, boolean settled, CrateRank holderCrate) {}
    private static final Result NOTHING = new Result(0, null, false, false, null);
    private Delivery() {}

    /** Whether the contract takes this pod here at all; a refused pod stays with its carrier, an accepted one is consumed even if it pays nothing. */
    public static boolean accepts(Contract contract, PodTag pod, GlobalPos at) {
        if (pod.contract() != contract.id || pod.index() < 0 || pod.index() >= contract.pods) return false;
        return !contract.delivered.contains(pod.index()) && !contract.lost.contains(pod.index()) && !at.equals(contract.origin);
    }
    /** Whether a pod whose contract is gone is taken here: not when its id was abandoned or settled, nor at its origin. */
    public static boolean acceptsOrphan(long contractId, GlobalPos origin, GlobalPos at, Set<Long> voided) { return !voided.contains(contractId) && !at.equals(origin); }

    public static Result deliver(CargoProfile deliverer, UUID delivererId, Contract contract, PodTag pod, boolean byHolder, boolean late, GlobalPos at) {
        if (!accepts(contract, pod, at)) return NOTHING;
        int full = CargoRanks.reputation(rankOf(contract.rankLevel), distance(contract.origin, contract.destination));
        if (!byHolder) {
            int pay = full / 2; deliverer.reputation += pay; contract.lost.add(pod.index());
            CrateRank crate = contract.strangersPaid.add(delivererId) ? contract.crate.lower() : null;
            boolean settled = contract.settled();
            // a pod was lost, so the holder's completion crate is the lowered one; only owed if the holder delivered a pod
            CrateRank holderCrate = settled && !contract.delivered.isEmpty() ? contract.crate.lower() : null;
            return new Result(pay, crate, false, settled, holderCrate);
        }
        boolean reduced = late || pod.ejected();
        int pay = reduced ? full / 2 : full; if (reduced) contract.anyReduced = true;
        deliverer.reputation += pay; contract.delivered.add(pod.index());
        boolean settled = contract.settled();
        CrateRank crate = settled ? (contract.anyReduced || !contract.lost.isEmpty() ? contract.crate.lower() : contract.crate) : null;
        return new Result(pay, crate, !reduced, settled, null);
    }

    /** A pod whose contract is gone: half of the lowest rank's base unless {@link #acceptsOrphan} refuses it. */
    public static Result orphan(CargoProfile deliverer, long contractId, GlobalPos origin, GlobalPos at, Set<Long> voided) {
        if (!acceptsOrphan(contractId, origin, at, voided)) return NOTHING;
        int pay = CargoRanks.ranks().getFirst().basePerPod() / 2; deliverer.reputation += pay; return new Result(pay, null, false, false, null);
    }

    // unknown levels (ranks reloaded from data) fall back to rank 1
    private static Rank rankOf(int level) { for (Rank r : CargoRanks.ranks()) if (r.level() == level) return r; return CargoRanks.ranks().getFirst(); }
    private static double distance(GlobalPos a, GlobalPos b) { double dx = a.pos().getX() - b.pos().getX(), dz = a.pos().getZ() - b.pos().getZ(); return Math.sqrt(dx * dx + dz * dz); }
}
