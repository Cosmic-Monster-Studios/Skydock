package dev.skydock.client;

import dev.skydock.cargo.CargoNetwork;
import dev.skydock.cargo.CargoProfile;
import dev.skydock.cargo.Rank;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** The terminal's Shop tab: the balance, then the synced stock grouped by rank with locked ranks greyed, each line priced with a Buy button. Scrolls by row. */
final class ShopPanel {
    private static final int ENTRY = 20, HEADER = 13, BUTTONS = 12, BUY_W = 40;
    /** A flat row: a rank header ({@code listing} null) or an entry. */
    private record Row(int rank, ClientCargo.Listing listing) { int height() { return listing == null ? HEADER : ENTRY; } }

    private final Font font;
    private final List<DockScreen.ShipwrightButton> buttons = new ArrayList<>();
    private final ClientCargo.Listing[] bound = new ClientCargo.Listing[BUTTONS];
    private int scroll;
    private ItemStack hovered;

    ShopPanel(Font font, Function<Runnable, DockScreen.ShipwrightButton> button, LongConsumer buy) {
        this.font = font;
        for (int i = 0; i < BUTTONS; i++) { int slot = i; buttons.add(button.apply(() -> { if (bound[slot] != null) buy.accept(dev.skydock.cargo.Shop.fingerprint(bound[slot].index(), bound[slot].entry())); })); }
    }

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>(); List<ClientCargo.Listing> stock = ClientCargo.shop();
        stock.stream().mapToInt(l -> l.entry().rank()).distinct().sorted().forEach(rank -> {
            rows.add(new Row(rank, null));
            for (ClientCargo.Listing l : stock) if (l.entry().rank() == rank) rows.add(new Row(rank, l));
        });
        return rows;
    }

    void scroll(double delta) { scroll -= (int) Math.signum(delta); }

    /** Places the Buy buttons over the visible entry rows; {@code on} is whether the Shop tab is showing. */
    void update(boolean on, int x, int y, int width, int height) {
        for (int i = 0; i < BUTTONS; i++) { bound[i] = null; buttons.get(i).visible = false; }
        if (!on) return;
        List<Row> rows = rows(); scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - 1));
        CargoProfile profile = ClientCargo.profile(); int slot = 0, ry = y + 14, bottom = y + height;
        for (int i = scroll; i < rows.size() && slot < BUTTONS; i++) {
            Row row = rows.get(i); if (ry + row.height() > bottom) break;
            if (row.listing != null) {
                var entry = row.listing.entry(); boolean owned = entry.isPattern() && profile.unlocked.contains(entry.pattern());
                DockScreen.ShipwrightButton buy = buttons.get(slot); bound[slot++] = row.listing;
                buy.setBounds(x + width - BUY_W - 4, ry + 2, BUY_W, ENTRY - 6);
                buy.visible = !owned; buy.active = entry.rank() <= profile.peakRank() && profile.balance() >= entry.price();
            }
            ry += row.height();
        }
    }

    void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        CargoProfile profile = ClientCargo.profile(); int rank = profile.peakRank();
        graphics.drawString(font, I18n.get("gui.skydock.cargo.shop_balance", profile.balance()), x + 3, y + 3, TerminalScreen.INK, false);
        List<Row> rows = rows();
        if (rows.isEmpty()) { graphics.drawString(font, I18n.get("gui.skydock.cargo.shop_empty"), x + 3, y + 18, TerminalScreen.MUTED, false); return; }
        int ry = y + 14, bottom = y + height, shown = 0;
        for (int i = scroll; i < rows.size(); i++) {
            Row row = rows.get(i); if (ry + row.height() > bottom) break;
            boolean locked = row.rank > rank; shown++;
            if (row.listing == null) {
                Rank r = ClientCargo.byLevel(row.rank);
                String header = I18n.get("gui.skydock.cargo.shop_rank", I18n.get(r.key()), r.threshold());
                graphics.drawString(font, font.plainSubstrByWidth(header, width - 8), x + 3, ry + 3, locked ? TerminalScreen.MUTED : TerminalScreen.INK, false);
            } else renderEntry(graphics, row.listing, x, ry, width, locked, profile, mouseX, mouseY);
            ry += row.height();
        }
        if (shown < rows.size()) { // scroll bar
            int track = bottom - (y + 14), thumb = Math.max(8, track * shown / rows.size()), top = y + 14 + (track - thumb) * scroll / Math.max(1, rows.size() - shown);
            graphics.fill(x + width - 2, y + 14, x + width, bottom, TerminalScreen.CANVAS);
            graphics.fill(x + width - 2, Math.min(top, bottom - thumb), x + width, Math.min(top, bottom - thumb) + thumb, TerminalScreen.BRASS);
        }
    }

    private void renderEntry(GuiGraphics graphics, ClientCargo.Listing listing, int x, int y, int width, boolean locked, CargoProfile profile, int mouseX, int mouseY) {
        var entry = listing.entry(); boolean owned = entry.isPattern() && profile.unlocked.contains(entry.pattern());
        graphics.fill(x, y, x + width - 4, y + ENTRY - 2, locked ? 0x88F6EED8 : TerminalScreen.CARD);
        int textX = x + 4;
        if (!entry.item().isEmpty()) {
            graphics.renderItem(entry.item(), x + 2, y + 1); textX = x + 21;
            if (mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 1 && mouseY < y + 17) hovered = entry.item();
        }
        String label = label(listing);
        String price = owned ? I18n.get("gui.skydock.cargo.shop_owned") : I18n.get("gui.skydock.cargo.shop_price", entry.price());
        int priceX = x + width - BUY_W - 10 - font.width(price);
        graphics.drawString(font, font.plainSubstrByWidth(label, Math.max(10, priceX - textX - 4)), textX, y + 5, locked ? TerminalScreen.MUTED : TerminalScreen.INK, false);
        int priceColor = owned ? TerminalScreen.TEAL : locked ? TerminalScreen.MUTED : profile.balance() < entry.price() ? TerminalScreen.MISSING : TerminalScreen.INK;
        graphics.drawString(font, price, priceX, y + 5, priceColor, false);
    }

    /** What an entry sells, as one line: "16 × Lift Cell" or "Pattern: Packet Freighter". */
    static String label(ClientCargo.Listing listing) {
        var entry = listing.entry();
        return entry.isPattern() ? I18n.get("gui.skydock.cargo.shop_pattern", I18n.get(listing.patternName()))
                : I18n.get("gui.skydock.cargo.shop_item", entry.item().getCount(), entry.item().getHoverName().getString());
    }

    /** The item tooltip under the mouse from the last {@link #render}, drawn after the widgets. */
    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hovered != null) graphics.renderTooltip(font, hovered, mouseX, mouseY);
        hovered = null;
    }
}
