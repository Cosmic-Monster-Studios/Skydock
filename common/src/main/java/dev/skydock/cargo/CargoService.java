package dev.skydock.cargo;

import dev.skydock.block.SkydockBlocks;
import dev.skydock.data.ShipPatterns;
import dev.skydock.network.ShipNetwork;
import dev.skydock.ship.Ship;
import dev.skydock.ship.ShipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** What a cargo terminal does for a player: accepting offers, delivering pods, abandoning contracts, and keeping the client in sync. */
public final class CargoService {
    public static final int MAX_CONTRACTS = 3;
    public static final double DELIVERY_RANGE = 32;
    /** A predicted outpost site is replaced by the terminal that generates near it; contracts bound for the site follow it. */
    public static final double SITE_MATCH = 64;
    private CargoService() {}

    /** Empty when the offer may be accepted, else the lang key saying why not (the inventory check is the caller's). */
    public static String acceptRefusal(CargoProfile profile, Offer offer, int playerRank) {
        if (offer.rankLevel() > playerRank) return "message.skydock.cargo_rank_locked";
        if (profile.contracts.size() >= MAX_CONTRACTS) return "message.skydock.cargo_full";
        return "";
    }
    public static long deadline(long now, int days) { return now + days * Offers.DAY_TICKS; }

    /** Applies a delivery's consequences to the holder: a stranger-earned completion crate is queued, a settled contract dropped and its id voided. */
    public static void applyResult(CargoSavedData data, UUID holder, Contract contract, Delivery.Result result) {
        CargoProfile p = data.profile(holder);
        if (result.holderCrate() != null) p.pendingCrates.add(result.holderCrate());
        // Leftover (duplicate) pods of a settled contract must not pay again as orphans.
        if (result.settled()) { p.contracts.remove(contract.id); data.voided.add(contract.id); }
        data.setDirty();
    }

    /** The registered terminal a contract delivers to: its destination, or the generated terminal that replaced that predicted site; null when gone. */
    public static GlobalPos resolveDestination(Map<GlobalPos, CargoSavedData.Terminal> terminals, GlobalPos destination) {
        if (terminals.containsKey(destination)) return destination;
        for (CargoSavedData.Terminal t : terminals.values())
            if (t.generated() && t.pos().dimension().equals(destination.dimension()) && Offers.distance(t.pos().pos(), destination.pos()) <= SITE_MATCH) return t.pos();
        return null;
    }

    /**
     * Drops and returns the holder's contracts whose destination terminal is gone. Their ids are not voided, so the pods
     * become orphans that any terminal but their origin takes for the orphan reward.
     */
    static List<Contract> failGone(CargoSavedData data, CargoProfile p) {
        List<Contract> gone = new ArrayList<>();
        for (Iterator<Contract> it = p.contracts.values().iterator(); it.hasNext(); ) { Contract c = it.next(); if (resolveDestination(data.terminals, c.destination) == null) { it.remove(); gone.add(c); } }
        return gone;
    }

    /** An empty board means no destination was found; the site search runs again only once its day is up. */
    static boolean siteSearchWaits(CargoProfile p, GlobalPos at, long now) {
        List<Offer> board = p.offers.get(at); Long last = p.offersRefreshed.get(at);
        return board != null && board.isEmpty() && last != null && now - last < Offers.DAY_TICKS;
    }
    /** How far to look for destinations: out to the band of the rank above the player's (the open-ended top band looks twice its minimum). */
    static int searchRadius(int playerRank) {
        Rank preview = CargoRanks.ranks().getLast();
        for (Rank r : CargoRanks.ranks()) if (r.level() == Math.min(playerRank + 1, preview.level())) { preview = r; break; }
        return preview.maxDistance() == Integer.MAX_VALUE ? preview.minDistance() * 2 : preview.maxDistance();
    }

    private static long now(MinecraftServer server) { return server.overworld().getGameTime(); }
    private static Component cargoName(String key) { return Component.translatable(CargoPodItem.nameKey(key)); }
    private static void give(ServerPlayer player, ItemStack stack) { if (!player.getInventory().add(stack)) player.drop(stack, false); }
    private static void giveCrate(ServerPlayer player, CrateRank rank) { give(player, new ItemStack(SkydockBlocks.CRATES.get(rank).get())); }

    /** Called whenever a terminal screen opens: hands out queued crates, fails contracts whose terminal is gone, and refreshes the board. */
    public static void opened(ServerPlayer player, GlobalPos terminal) {
        MinecraftServer server = player.getServer(); CargoSavedData data = CargoSavedData.get(server); CargoProfile p = data.profile(player.getUUID());
        boolean changed = false;
        if (!p.pendingCrates.isEmpty()) { p.pendingCrates.forEach(rank -> giveCrate(player, rank)); p.pendingCrates.clear(); changed = true; }
        for (Contract c : failGone(data, p)) { changed = true; ShipManager.tell(player, "message.skydock.cargo_destination_gone", cargoName(c.cargoKey)); }
        long now = now(server); ServerLevel level = server.getLevel(terminal.dimension());
        if (level != null && !siteSearchWaits(p, terminal, now)) {
            int rank = p.peakRank();
            changed |= Offers.refreshIfDue(p, terminal, now, () -> Offers.generate(terminal, rank, TerminalSites.near(level, terminal.pos(), 1, searchRadius(rank)),
                    level.getRandom(), () -> data.nextContract++, CargoNames.names()));
        }
        if (changed) data.setDirty();
        sync(player);
    }

    public static void accept(ServerPlayer player, GlobalPos terminal, long offerId) {
        MinecraftServer server = player.getServer(); CargoSavedData data = CargoSavedData.get(server); CargoProfile p = data.profile(player.getUUID());
        List<Offer> board = p.offers.get(terminal); if (board == null) return;
        Offer offer = board.stream().filter(o -> o.id() == offerId).findFirst().orElse(null); if (offer == null) return;
        String refusal = acceptRefusal(p, offer, p.peakRank());
        if (!refusal.isEmpty()) { ShipManager.tell(player, refusal); return; }
        int free = 0; for (ItemStack s : player.getInventory().items) if (s.isEmpty()) free++;
        if (free < offer.pods()) { ShipManager.tell(player, "message.skydock.cargo_no_room", offer.pods()); return; }
        Contract contract = new Contract(data.nextContract++, terminal, offer.destination(), offer.rankLevel(), offer.crate(), offer.cargoKey(), offer.pods(), deadline(now(server), offer.deadlineDays()));
        p.contracts.put(contract.id, contract); board.remove(offer);
        // An emptied board was not short of destinations, so it should not wait a day for the next search.
        if (board.isEmpty()) p.offersRefreshed.remove(terminal);
        for (int i = 0; i < offer.pods(); i++) give(player, CargoPodItem.create(new PodTag(contract.id, i, false), offer.cargoKey(), offer.destination()));
        data.setDirty();
        ShipManager.tell(player, "message.skydock.cargo_accepted", cargoName(offer.cargoKey()), offer.pods());
    }

    public static void abandon(ServerPlayer player, long contractId) {
        CargoSavedData data = CargoSavedData.get(player.getServer());
        Contract c = data.profile(player.getUUID()).contracts.remove(contractId); if (c == null) return;
        data.voided.add(contractId); data.setDirty();
        ShipManager.tell(player, "message.skydock.cargo_abandoned", cargoName(c.cargoKey));
    }

    /** Buys the shop entry at this index for the player, re-checking rank, balance and ownership; items go to the inventory or drop at their feet. */
    public static void buy(ServerPlayer player, long index) {
        List<ShopEntry> entries = Shop.entries(); if (index < 0 || index >= entries.size()) return;
        ShopEntry entry = entries.get((int) index);
        if (entry.isPattern() && ShipPatterns.get(entry.pattern()).isEmpty()) { ShipManager.tell(player, "message.skydock.shop_unavailable"); return; }
        CargoSavedData data = CargoSavedData.get(player.getServer());
        String refusal = Shop.buy(data.profile(player.getUUID()), entry);
        if (!refusal.isEmpty()) { ShipManager.tell(player, refusal); return; }
        data.setDirty();
        if (entry.isPattern()) ShipManager.tell(player, "message.skydock.shop_bought_pattern", Shop.patternName(entry.pattern()), entry.price());
        else { ItemStack stack = entry.item().copy(); ShipManager.tell(player, "message.skydock.shop_bought", stack.getCount(), stack.getHoverName(), entry.price()); give(player, stack); }
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1, 1);
    }

    /** A pod the terminal can reach and how to take it away. */
    private record Reachable(PodCargo cargo, Ship ship, BlockPos cell, int slot) {}

    private static List<Reachable> reachable(ServerPlayer player, ServerLevel level, BlockPos pad) {
        List<Reachable> out = new ArrayList<>(); Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) { int slot = i; CargoPodItem.cargo(inventory.getItem(i)).ifPresent(c -> out.add(new Reachable(c, null, null, slot))); }
        ServerLevel yard = player.getServer().getLevel(ShipManager.SHIPYARD); if (yard == null) return out;
        Vec3 centre = Vec3.atCenterOf(pad);
        for (Ship ship : ShipManager.ships(level)) {
            if (!ship.moored || !ShipManager.permitted(player, ship) || ship.hullWorldBounds().getCenter().distanceTo(centre) > DELIVERY_RANGE) continue;
            for (Map.Entry<BlockPos, BlockState> cell : ship.blocks.entrySet())
                if (cell.getValue().is(SkydockBlocks.CARGO_POD.get()) && yard.getBlockEntity(ship.yard.offset(cell.getKey())) instanceof CargoPodBlockEntity pod && pod.cargo() != null)
                    out.add(new Reachable(pod.cargo(), ship, cell.getKey(), -1));
        }
        return out;
    }

    /** Takes every pod bound here (and every orphan pod) from the player's inventory and moored ships in range, and pays for each. */
    public static void deliver(ServerPlayer player, GlobalPos terminal) {
        MinecraftServer server = player.getServer(); CargoSavedData data = CargoSavedData.get(server);
        CargoSavedData.Terminal site = data.terminals.get(terminal); ServerLevel level = server.getLevel(terminal.dimension());
        if (site == null || level == null) return;
        CargoProfile me = data.profile(player.getUUID()); int rankBefore = me.peakRank(); long now = now(server);
        Set<Long> settled = new HashSet<>(); Set<UUID> holders = new LinkedHashSet<>(); Set<Ship> touched = new LinkedHashSet<>();
        int count = 0, gained = 0;
        for (Reachable pod : reachable(player, level, site.pad())) {
            PodTag tag = pod.cargo().tag(); if (settled.contains(tag.contract())) continue;
            Map.Entry<UUID, Contract> held = data.findContract(tag.contract());
            Delivery.Result result;
            if (held != null) {
                Contract c = held.getValue();
                if (!terminal.equals(resolveDestination(data.terminals, c.destination))) continue;
                boolean byHolder = held.getKey().equals(player.getUUID());
                if (!Delivery.accepts(c, tag, terminal)) continue;
                result = Delivery.deliver(me, player.getUUID(), c, tag, byHolder, now > c.deadline, terminal);
                applyResult(data, held.getKey(), c, result);
                if (result.settled()) settled.add(c.id);
                if (!byHolder) holders.add(held.getKey());
            } else {
                // The contract is gone with its origin, so only an abandoned or settled (voided) id is refused.
                if (!Delivery.acceptsOrphan(tag.contract(), null, terminal, data.voided)) continue;
                result = Delivery.orphan(me, tag.contract(), null, terminal, data.voided);
            }
            if (pod.ship() == null) player.getInventory().removeItem(pod.slot(), 1);
            else { server.getLevel(ShipManager.SHIPYARD).setBlock(pod.ship().yard.offset(pod.cell()), Blocks.AIR.defaultBlockState(), PodLoading.FLAGS); PodLoading.forget(pod.ship(), pod.cell()); touched.add(pod.ship()); }
            count++; gained += result.reputation();
            if (result.crate() != null) giveCrate(player, result.crate());
        }
        for (Ship ship : touched) { ship.revision++; ShipManager.refresh(server, ship); }
        if (!touched.isEmpty()) ShipNetwork.syncPlayers(server);
        if (count == 0) { ShipManager.tell(player, "message.skydock.cargo_nothing"); return; }
        data.setDirty();
        ShipManager.tell(player, "message.skydock.cargo_delivered", count, gained);
        if (me.peakRank() > rankBefore) {
            ShipManager.tell(player, "message.skydock.cargo_rank_up", Component.translatable(CargoRanks.forReputation(me.reputation).key()));
            player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1, 1);
        }
        for (UUID holder : holders) { ServerPlayer online = server.getPlayerList().getPlayer(holder); if (online != null) sync(online); }
    }

    /** Sends the player's profile, the rank ladder, the shop stock (pattern entries carry the pattern's name) and each active contract's destination pad. */
    public static void sync(ServerPlayer player) {
        CargoSavedData data = CargoSavedData.get(player.getServer()); CargoProfile p = data.profile(player.getUUID());
        CompoundTag tag = p.save(); ListTag ranks = new ListTag(); CargoRanks.ranks().forEach(r -> ranks.add(r.save())); tag.put("Ranks", ranks);
        ListTag shop = new ListTag();
        for (ShopEntry entry : Shop.entries()) {
            CompoundTag line = entry.save();
            if (entry.isPattern()) ShipPatterns.get(entry.pattern()).ifPresent(pattern -> line.putString("Name", pattern.name()));
            shop.add(line);
        }
        tag.put("Shop", shop);
        List<CargoNetwork.Destination> destinations = new ArrayList<>();
        for (Contract c : p.contracts.values()) {
            GlobalPos at = resolveDestination(data.terminals, c.destination);
            destinations.add(new CargoNetwork.Destination(c.id, at == null ? c.destination.pos() : data.terminals.get(at).pad()));
        }
        CargoNetwork.sync(player, tag, destinations);
    }
}
