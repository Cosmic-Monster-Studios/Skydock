package dev.skydock.client;

import dev.skydock.block.SkydockBlocks;
import dev.skydock.cargo.*;
import dev.skydock.ship.Navigation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The cargo terminal: the player's offers and contracts here, the shop ({@link ShopPanel}), and the rank ladder. Drawn in the dock screen's canvas style. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    static final int CANVAS = 0xFFE7D6AA, CANVAS_DARK = 0xFFD6BF8A, BRASS = 0xFFC69852, INK = 0xFF132C31, TEAL = 0xFF4FA89C,
            PALE = 0xFFF6EED8, MISSING = 0xFF9D3F38, MUTED = 0xFF536A67, CARD = 0xCCF6EED8;
    private static final int ROW = 46;

    private enum Page { CONTRACTS, SHOP, REPUTATION }
    private Page page = Page.CONTRACTS;
    /** Abandoning takes two clicks: the first arms the contract's button for {@link #CONFIRM_MS}, the second sends. */
    private static final long CONFIRM_MS = 3000;
    private long armedContract = -1, armedUntil;
    private DockScreen.ShipwrightButton contractsTab, shopTab, reputationTab, deliverHere;
    private ShopPanel shop;
    private final List<DockScreen.ShipwrightButton> acceptButtons = new ArrayList<>(), deliverButtons = new ArrayList<>(), abandonButtons = new ArrayList<>();
    private final List<Offer> offers = new ArrayList<>();
    private final List<Contract> contracts = new ArrayList<>();
    /** The crate whose ladder icon is under the mouse, for its tooltip. */
    private ItemStack hoveredCrate = ItemStack.EMPTY;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        titleLabelX = inventoryLabelX = -10000;
    }

    @Override protected void init() {
        imageWidth = Math.max(300, Math.min(460, width - 8));
        imageHeight = Math.max(212, Math.min(236, height - 8));
        super.init();
        acceptButtons.clear(); deliverButtons.clear(); abandonButtons.clear();
        int tabY = topPos + 27;
        contractsTab = button(leftPos + 9, tabY, 80, 18, "gui.skydock.cargo.tab_contracts", () -> page = Page.CONTRACTS);
        shopTab = button(leftPos + 91, tabY, 60, 18, "gui.skydock.cargo.tab_shop", () -> page = Page.SHOP);
        reputationTab = button(leftPos + 153, tabY, 84, 18, "gui.skydock.cargo.tab_reputation", () -> page = Page.REPUTATION);
        shop = new ShopPanel(font, action -> button(0, 0, 10, 10, "gui.skydock.cargo.buy", action), fingerprint -> send(CargoNetwork.BUY, fingerprint));
        for (int i = 0; i < CargoService.MAX_CONTRACTS; i++) {
            int slot = i;
            acceptButtons.add(button(0, 0, 10, 10, "gui.skydock.cargo.accept", () -> { if (slot < offers.size()) send(CargoNetwork.ACCEPT, offers.get(slot).id()); }));
            deliverButtons.add(button(0, 0, 10, 10, "gui.skydock.cargo.deliver", () -> send(CargoNetwork.DELIVER, 0)));
            abandonButtons.add(button(0, 0, 10, 10, "gui.skydock.cargo.abandon", () -> { if (slot < contracts.size()) abandon(contracts.get(slot).id); }));
        }
        deliverHere = button(0, 0, 10, 10, "gui.skydock.cargo.deliver_here", () -> send(CargoNetwork.DELIVER, 0));
    }

    private DockScreen.ShipwrightButton button(int x, int y, int w, int h, String label, Runnable action) {
        return addRenderableWidget(new DockScreen.ShipwrightButton(x, y, w, h, Component.translatable(label), action));
    }
    private boolean armed(long contract) { return armedContract == contract && System.currentTimeMillis() < armedUntil; }
    private void abandon(long contract) {
        if (armed(contract)) { armedContract = -1; send(CargoNetwork.ABANDON, contract); }
        else { armedContract = contract; armedUntil = System.currentTimeMillis() + CONFIRM_MS; }
    }
    private void send(int action, long id) { CargoNetwork.send(menu.containerId, action, id); }

    private GlobalPos here() { return GlobalPos.of(minecraft.level.dimension(), menu.pos); }
    private int columnWidth() { return (imageWidth - 24) / 2; }
    private int bodyY() { return topPos + 51; }
    private long now() { return minecraft.level.getGameTime(); }

    /** Pulls this terminal's board and the player's contracts from the synced profile and places the row buttons. */
    private void updateWidgets() {
        CargoProfile profile = ClientCargo.profile();
        offers.clear(); offers.addAll(profile.offers.getOrDefault(here(), List.of()));
        contracts.clear(); contracts.addAll(profile.contracts.values());
        contractsTab.selected = page == Page.CONTRACTS; shopTab.selected = page == Page.SHOP; reputationTab.selected = page == Page.REPUTATION;
        shop.update(page == Page.SHOP, leftPos + 9, bodyY(), imageWidth - 18, imageHeight - 57);
        boolean on = page == Page.CONTRACTS; int col = columnWidth(), left = leftPos + 9, right = left + col + 6, rows = bodyY() + 14;
        int rank = ClientCargo.rank(profile.reputation).level();
        for (int i = 0; i < acceptButtons.size(); i++) {
            int y = rows + i * ROW;
            DockScreen.ShipwrightButton accept = acceptButtons.get(i), deliver = deliverButtons.get(i), abandon = abandonButtons.get(i);
            accept.setBounds(left + col - 54, y + 3, 50, 16); deliver.setBounds(right + col - 54, y + 3, 50, 16); abandon.setBounds(right + col - 54, y + 23, 50, 16);
            accept.visible = on && i < offers.size();
            accept.active = accept.visible && offers.get(i).rankLevel() <= rank && contracts.size() < CargoService.MAX_CONTRACTS;
            boolean held = on && i < contracts.size();
            deliver.visible = held && boundHere(contracts.get(i));
            abandon.visible = held;
            abandon.setMessage(Component.translatable(held && armed(contracts.get(i).id) ? "gui.skydock.cargo.confirm_abandon" : "gui.skydock.cargo.abandon"));
        }
        deliverHere.setBounds(right + col - 74, bodyY(), 74, 13); deliverHere.visible = on;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateWidgets();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        shop.renderTooltip(graphics, mouseX, mouseY);
        if (!hoveredCrate.isEmpty()) graphics.renderTooltip(font, hoveredCrate, mouseX, mouseY);
        hoveredCrate = ItemStack.EMPTY;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page == Page.SHOP) { shop.scroll(scrollY); return true; }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int right = leftPos + imageWidth, bottom = topPos + imageHeight;
        graphics.fill(leftPos - 2, topPos - 2, right + 2, bottom + 2, 0xDD0B181B);
        graphics.fill(leftPos, topPos, right, bottom, CANVAS);
        graphics.fill(leftPos, topPos, right, topPos + 24, INK);
        graphics.fill(leftPos, topPos + 23, right, topPos + 26, BRASS);
        graphics.fill(leftPos + 6, topPos + 48, right - 6, bottom - 6, CANVAS_DARK);
        graphics.drawString(font, I18n.get("gui.skydock.cargo.title"), leftPos + 10, topPos + 8, PALE, false);
        CargoProfile profile = ClientCargo.profile();
        String standing = font.plainSubstrByWidth(I18n.get("gui.skydock.cargo.standing", I18n.get(ClientCargo.rank(profile.reputation).key()), profile.reputation), imageWidth / 2);
        graphics.drawString(font, standing, right - 10 - font.width(standing), topPos + 8, TEAL, false);
        switch (page) {
            case CONTRACTS -> renderContracts(graphics);
            case REPUTATION -> renderReputation(graphics, leftPos + 9, bodyY(), imageWidth - 18, imageHeight - 57, profile, mouseX, mouseY);
            case SHOP -> shop.render(graphics, leftPos + 9, bodyY(), imageWidth - 18, imageHeight - 57, mouseX, mouseY);
        }
    }

    private void renderContracts(GuiGraphics graphics) {
        int col = columnWidth(), left = leftPos + 9, right = left + col + 6, rows = bodyY() + 14, rank = ClientCargo.rank(ClientCargo.profile().reputation).level();
        graphics.drawString(font, I18n.get("gui.skydock.cargo.offers"), left + 3, bodyY() + 3, INK, false);
        graphics.drawString(font, font.plainSubstrByWidth(I18n.get("gui.skydock.cargo.contracts"), col - 80), right + 3, bodyY() + 3, INK, false);
        if (offers.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(I18n.get("gui.skydock.cargo.no_offers"), col - 6), left + 3, rows + 4, MUTED, false);
        if (contracts.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(I18n.get("gui.skydock.cargo.no_contracts"), col - 6), right + 3, rows + 4, MUTED, false);
        for (int i = 0; i < Math.min(offers.size(), CargoService.MAX_CONTRACTS); i++) {
            Offer o = offers.get(i); int y = rows + i * ROW, text = col - 60; boolean locked = o.rankLevel() > rank;
            graphics.fill(left, y, left + col, y + ROW - 4, CARD);
            line(graphics, I18n.get(CargoPodItem.nameKey(o.cargoKey())), left + 4, y + 4, text, locked ? MUTED : INK);
            line(graphics, I18n.get(CargoService.byCount(o.pods(), "gui.skydock.cargo.route_one", "gui.skydock.cargo.route"), o.pods(), Math.round(distance(o.destination().pos())), bearing(o.destination().pos())), left + 4, y + 16, col - 8, MUTED);
            String reward = locked ? I18n.get("gui.skydock.cargo.locked", I18n.get(ClientCargo.byLevel(o.rankLevel()).key()))
                    : I18n.get("gui.skydock.cargo.reward", o.deadlineDays(), o.reputationPerPod(), crateName(o.crate()));
            line(graphics, reward, left + 4, y + 28, col - 8, locked ? MISSING : INK);
        }
        for (int i = 0; i < Math.min(contracts.size(), CargoService.MAX_CONTRACTS); i++) {
            Contract c = contracts.get(i); int y = rows + i * ROW, text = col - 60; long remaining = c.deadline - now();
            BlockPos pad = ClientCargo.pad(c.id); if (pad == null) pad = c.destination.pos();
            graphics.fill(right, y, right + col, y + ROW - 4, CARD);
            line(graphics, I18n.get(CargoPodItem.nameKey(c.cargoKey)), right + 4, y + 4, text, INK);
            line(graphics, I18n.get(CargoService.byCount(c.pods, "gui.skydock.cargo.progress_one", "gui.skydock.cargo.progress"), c.delivered.size(), c.pods, Math.round(distance(pad)), bearing(pad)), right + 4, y + 16, text, MUTED);
            String due = remaining < 0 ? I18n.get("gui.skydock.cargo.overdue")
                    : I18n.get("gui.skydock.cargo.due", remaining / Offers.DAY_TICKS, remaining % Offers.DAY_TICKS / 1000);
            line(graphics, due, right + 4, y + 28, text, remaining < 0 ? MISSING : INK);
        }
    }

    /** The server follows a predicted site to the terminal that generated within 64 blocks of it; the screen does the same. */
    private boolean boundHere(Contract c) { return c.destination.dimension().equals(here().dimension()) && distance(c.destination.pos()) <= CargoService.SITE_MATCH; }
    private void line(GuiGraphics graphics, String text, int x, int y, int width, int color) { graphics.drawString(font, font.plainSubstrByWidth(text, Math.max(10, width)), x, y, color, false); }
    private double distance(BlockPos to) { return Offers.distance(menu.pos, to); }
    /** The 8-point compass direction from this terminal to {@code to}, in the {@link Navigation} convention. */
    private String bearing(BlockPos to) {
        double dx = to.getX() - menu.pos.getX(), dz = to.getZ() - menu.pos.getZ();
        return Navigation.compass(Math.toDegrees(Math.atan2(dx, -dz)));
    }
    static String crateName(CrateRank rank) { return I18n.get("block.skydock." + rank.name().toLowerCase(Locale.ROOT) + "_crate"); }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    /**
     * The Reputation tab: the rank ladder with the current rank lit, and what the next rank brings. Each rung has columns sized to their widest
     * entry: rank, threshold, compact band and crate (its icon, plus its name when the ladder has room for it).
     */
    private void renderReputation(GuiGraphics graphics, int x, int y, int width, int height, CargoProfile profile, int mouseX, int mouseY) {
        List<Rank> ranks = ClientCargo.ranks(); Rank current = ClientCargo.rank(profile.reputation), next = null;
        for (Rank r : ranks) if (r.threshold() > profile.reputation) { next = r; break; }
        int nameW = 0, repW = 0, bandW = 0, crateW = 0;
        for (Rank r : ranks) {
            nameW = Math.max(nameW, font.width(rankName(r))); repW = Math.max(repW, font.width(I18n.get("gui.skydock.cargo.threshold", r.threshold())));
            bandW = Math.max(bandW, font.width(shortBand(r))); crateW = Math.max(crateW, font.width(crateName(r.crate())));
        }
        int tight = LADDER_PAD + nameW + LADDER_GAP + repW + LADDER_GAP + bandW + LADDER_GAP + 16 + LADDER_PAD, full = tight + 3 + crateW;
        int ladderW = Math.min(width - 80, Math.max(width * 3 / 5, full));
        if (ladderW < tight) ladderW = Math.min(width, tight);
        boolean named = full <= ladderW;
        int rowH = Math.min(24, Math.max(16, (height - 16) / Math.max(1, ranks.size())));
        graphics.drawString(font, I18n.get("gui.skydock.cargo.ladder"), x + 3, y + 3, INK, false);
        // The ladder reads upwards: the top rank at the top, Deckhand at the foot.
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(ranks.size() - 1 - i); int ry = y + 14 + i * rowH, ty = ry + (rowH - 10) / 2; boolean lit = r.level() == current.level(), reached = r.threshold() <= profile.reputation;
            graphics.fill(x, ry, x + ladderW, ry + rowH - 2, lit ? TEAL : CARD);
            int ink = lit ? PALE : reached ? INK : MUTED, edge = x + ladderW - LADDER_PAD;
            if (named) { graphics.drawString(font, crateName(r.crate()), edge - crateW, ty, ink, false); edge -= crateW + 3; }
            int iconX = edge - 16, iconY = ry + (rowH - 18) / 2; ItemStack crate = crateStack(r.crate());
            graphics.renderItem(crate, iconX, iconY);
            if (mouseX >= iconX && mouseX < iconX + 16 && mouseY >= iconY && mouseY < iconY + 16) hoveredCrate = crate;
            String band = shortBand(r), threshold = I18n.get("gui.skydock.cargo.threshold", r.threshold());
            int bandRight = iconX - LADDER_GAP, repRight = bandRight - bandW - LADDER_GAP;
            graphics.drawString(font, band, bandRight - font.width(band), ty, ink, false);
            graphics.drawString(font, threshold, repRight - font.width(threshold), ty, ink, false);
            graphics.drawString(font, font.plainSubstrByWidth(rankName(r), repRight - repW - LADDER_GAP - x - LADDER_PAD), x + LADDER_PAD, ty, ink, false);
        }
        int sx = x + ladderW + 8, sw = width - ladderW - 8, sy = y + 14;
        if (sw < 40) return;
        graphics.fill(sx, sy, sx + sw, y + height, CARD);
        List<String> lines = new ArrayList<>(List.of(I18n.get("gui.skydock.cargo.reputation", profile.reputation), I18n.get("gui.skydock.cargo.balance", profile.balance())));
        if (next == null) lines.add(I18n.get("gui.skydock.cargo.top_rank"));
        else {
            lines.add(I18n.get("gui.skydock.cargo.next", I18n.get(next.key()), next.threshold() - profile.reputation));
            lines.add(I18n.get("gui.skydock.cargo.unlocks"));
            lines.add("  " + band(next));
            lines.add("  " + crateName(next.crate()));
            for (ClientCargo.Listing listing : ClientCargo.shop()) if (listing.entry().rank() == next.level()) lines.add("  " + ShopPanel.label(listing));
        }
        int ly = sy + 5;
        for (String line : lines) for (var part : font.split(net.minecraft.network.chat.FormattedText.of(line), sw - 8)) { if (ly + 9 > y + height) return; graphics.drawString(font, part, sx + 4, ly, INK, false); ly += 10; }
    }
    private static final int LADDER_PAD = 4, LADDER_GAP = 6;
    private static String rankName(Rank r) { return r.level() + "  " + I18n.get(r.key()); }
    private static ItemStack crateStack(CrateRank rank) { return new ItemStack(SkydockBlocks.CRATES.get(rank).get()); }
    /** The band for the ladder's narrow column: "2.5k–6k", "5k+". */
    private static String shortBand(Rank r) {
        return r.maxDistance() == Integer.MAX_VALUE ? I18n.get("gui.skydock.cargo.band_open_short", compact(r.minDistance()))
                : I18n.get("gui.skydock.cargo.band_short", compact(r.minDistance()), compact(r.maxDistance()));
    }
    /** Block counts of a thousand or more in thousands with at most one decimal (2500 is "2.5k"); smaller ones as they are. */
    private static String compact(int blocks) { return blocks < 1000 ? Integer.toString(blocks) : I18n.get("gui.skydock.cargo.thousands", thousands(blocks)); }
    /** Blocks in thousands to at most one decimal, without a trailing ".0": 2500 is "2.5", 6000 is "6". */
    private static String thousands(int blocks) { String k = String.format(Locale.ROOT, "%.1f", blocks / 1000.0); return k.endsWith(".0") ? k.substring(0, k.length() - 2) : k; }
    private static String band(Rank r) {
        return r.maxDistance() == Integer.MAX_VALUE ? I18n.get("gui.skydock.cargo.band_open", r.minDistance()) : I18n.get("gui.skydock.cargo.band", r.minDistance(), r.maxDistance());
    }
}
