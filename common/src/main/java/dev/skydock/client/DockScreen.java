package dev.skydock.client;

import dev.skydock.menu.DockMenu;
import dev.skydock.network.DockNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DockScreen extends AbstractContainerScreen<DockMenu> {
    private static final int CANVAS = 0xFFE7D6AA;
    private static final int CANVAS_DARK = 0xFFD6BF8A;
    private static final int TIMBER = 0xFF8A5B32;
    private static final int BRASS = 0xFFC69852;
    private static final int INK = 0xFF132C31;
    private static final int TEAL = 0xFF4FA89C;
    private static final int PALE = 0xFFF6EED8;
    private static final int MISSING = 0xFF9D3F38;
    private static final int MUTED = 0xFF536A67;

    private enum Page { DESIGNS, MANUAL }

    private final DockPreviewRenderer preview = new DockPreviewRenderer();
    private final List<ShipwrightButton> patternButtons = new ArrayList<>();
    private Page page = Page.DESIGNS;
    private ShipwrightButton designsTab, manualTab, decorations, assemble, cancel, inspect, launch, redock, previousPage, nextPage;
    /** Patterns show three to a page; the page opens on the selected pattern once, then follows the arrows. */
    private static final int PER_PAGE = 3;
    private int patternPage;
    private boolean pageChosen;
    private DockView.Snapshot snapshot;
    private int seenRevision = Integer.MIN_VALUE;
    private int previewX, previewY, previewW, previewH;
    private int costsX, costsY, costsW, costsH, costScroll;
    private int requestTicks;
    private boolean waiting;

    public DockScreen(DockMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 588;
        imageHeight = 326;
        titleLabelX = inventoryLabelX = -10000;
    }

    @Override
    protected void init() {
        imageWidth = Math.max(286, Math.min(588, width - 8));
        imageHeight = Math.max(190, Math.min(326, height - 8));
        super.init();
        patternButtons.clear();
        int tabY = topPos + 27;
        designsTab = addShipButton(leftPos + 9, tabY, 76, 18, "gui.skydock.tab_designs", () -> page = Page.DESIGNS);
        manualTab = addShipButton(leftPos + 87, tabY, 70, 18, "gui.skydock.tab_manual", () -> page = Page.MANUAL);
        for (int i = 0; i < PER_PAGE; i++) {
            final int slot = i;
            patternButtons.add(addShipButton(0, 0, 10, 10, "gui.skydock.none", () -> menuButton(DockNetwork.PATTERN_BUTTON + patternPage * PER_PAGE + slot)));
        }
        previousPage = addShipButton(0, 0, 10, 10, "gui.skydock.page_previous", () -> patternPage--);
        nextPage = addShipButton(0, 0, 10, 10, "gui.skydock.page_next", () -> patternPage++);
        decorations = addShipButton(0, 0, 10, 10, "gui.skydock.decorations", () -> menuButton(5));
        assemble = addShipButton(0, 0, 10, 10, "gui.skydock.build", () -> menuButton(3));
        cancel = addShipButton(0, 0, 10, 10, "gui.skydock.cancel", () -> menuButton(4));
        inspect = addShipButton(0, 0, 10, 10, "gui.skydock.inspect", () -> menuButton(0));
        launch = addShipButton(0, 0, 10, 10, "gui.skydock.launch", () -> menuButton(1));
        redock = addShipButton(0, 0, 10, 10, "gui.skydock.redock", () -> menuButton(2));
        layoutButtons();
        DockNetwork.request(menu.containerId, menu.pos, -1);
        requestTicks = 0;
    }

    private ShipwrightButton addShipButton(int x, int y, int w, int h, String label, Runnable action) {
        return addRenderableWidget(new ShipwrightButton(x, y, w, h, Component.translatable(label), action));
    }

    private void menuButton(int id) {
        if (minecraft.gameMode == null) return;
        waiting = true;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void layoutButtons() {
        int bodyY = topPos + 51;
        int footerY = topPos + imageHeight - 29;
        boolean wide = imageWidth >= 520 && imageHeight >= 260;
        if (wide) {
            int leftX = leftPos + 9;
            for (int i = 0; i < patternButtons.size(); i++) patternButtons.get(i).setBounds(leftX, bodyY + 15 + i * 39, 114, 34);
            decorations.setBounds(leftX, bodyY + 136, 114, 20);
            previousPage.setBounds(leftX + 80, bodyY, 16, 13);
            nextPage.setBounds(leftX + 98, bodyY, 16, 13);
        } else {
            // Paging arrows flank the three pattern buttons; without paging the row keeps its four equal columns.
            int gap = 3, usable = imageWidth - 18, arrows = pages() > 1 ? 2 * (14 + gap) : 0, buttonW = (usable - arrows - gap * 3) / 4;
            int x = leftPos + 9;
            previousPage.setBounds(x, bodyY, 14, 20);
            if (arrows > 0) x += 14 + gap;
            for (int i = 0; i < patternButtons.size(); i++) { patternButtons.get(i).setBounds(x, bodyY, buttonW, 20); x += buttonW + gap; }
            nextPage.setBounds(x, bodyY, 14, 20);
            if (arrows > 0) x += 14 + gap;
            decorations.setBounds(x, bodyY, buttonW, 20);
        }
        int actionW = Math.min(102, Math.max(74, (imageWidth - 28) / 4));
        cancel.setBounds(leftPos + imageWidth - 9 - actionW, footerY + 3, actionW, 20);
        assemble.setBounds(cancel.getX() - actionW - 4, footerY + 3, actionW, 20);
        int manualW = Math.max(70, (imageWidth - 26) / 3);
        inspect.setBounds(leftPos + 9, footerY + 3, manualW, 20);
        launch.setBounds(leftPos + 13 + manualW, footerY + 3, manualW, 20);
        redock.setBounds(leftPos + 17 + manualW * 2, footerY + 3, imageWidth - 26 - manualW * 2, 20);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (++requestTicks >= (snapshot != null && snapshot.active() ? 10 : 60)) {
            requestTicks = 0;
            DockNetwork.request(menu.containerId, menu.pos, snapshot == null ? -1 : snapshot.revision());
        }
    }

    private void refreshSnapshot() {
        DockView.Snapshot latest = DockView.get(menu.containerId, menu.pos);
        if (latest == null) return;
        boolean received = latest != snapshot;
        snapshot = latest;
        if (received) waiting = false;
        if (seenRevision != snapshot.revision()) {
            seenRevision = snapshot.revision();
            costScroll = Mth.clamp(costScroll, 0, Math.max(0, snapshot.costs().size() - visibleCostRows()));
        }
    }

    private void updateWidgets() {
        layoutButtons();
        boolean designs = page == Page.DESIGNS;
        boolean complete = snapshot != null && snapshot.complete();
        designsTab.selected = designs;
        manualTab.selected = !designs;
        if (snapshot != null && !pageChosen) {
            pageChosen = true;
            for (int i = 0; i < snapshot.patterns().size(); i++) if (snapshot.patterns().get(i).id().equals(snapshot.selected())) patternPage = i / PER_PAGE;
        }
        patternPage = Mth.clamp(patternPage, 0, pages() - 1);
        previousPage.visible = nextPage.visible = designs && pages() > 1;
        previousPage.active = patternPage > 0;
        nextPage.active = patternPage < pages() - 1;
        for (int i = 0; i < patternButtons.size(); i++) {
            ShipwrightButton button = patternButtons.get(i);
            button.visible = designs;
            int index = patternPage * PER_PAGE + i;
            boolean exists = snapshot != null && index < snapshot.patterns().size();
            button.active = exists && !waiting && !snapshot.active() && !complete;
            if (exists) {
                DockView.Pattern pattern = snapshot.patterns().get(index);
                button.setMessage(Component.translatable(pattern.name()));
                button.selected = snapshot.selected().equals(pattern.id());
            } else {
                button.setMessage(Component.translatable("gui.skydock.none"));
                button.selected = false;
            }
        }
        decorations.visible = designs;
        decorations.active = snapshot != null && !waiting && !snapshot.active() && !complete;
        decorations.selected = snapshot != null && snapshot.decorations();
        decorations.setMessage(Component.translatable(snapshot != null && snapshot.decorations() ? "gui.skydock.decorations_on" : "gui.skydock.decorations_off"));
        assemble.visible = designs && (snapshot == null || (!snapshot.active() && !complete));
        cancel.visible = designs && snapshot != null && snapshot.active();
        assemble.active = snapshot != null && snapshot.canAssemble() && !waiting;
        cancel.active = snapshot != null && !waiting;
        inspect.visible = redock.visible = !designs;
        launch.visible = !designs || complete;
        if (designs && complete) launch.setBounds(assemble.getX(), assemble.getY(), assemble.getWidth(), assemble.getHeight());
        inspect.active = redock.active = snapshot != null && !waiting && !snapshot.active();
        launch.active = snapshot != null && !waiting && !snapshot.active();
    }

    private int pages() {
        int count = snapshot == null ? 0 : Math.min(snapshot.patterns().size(), DockNetwork.MAX_PATTERN_BUTTON - DockNetwork.PATTERN_BUTTON + 1);
        return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshSnapshot();
        updateWidgets();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (snapshot != null && page == Page.DESIGNS && in(mouseX, mouseY, costsX, costsY, costsW, costsH)) {
            int row = costScroll + (mouseY - costsY) / 20;
            if (row >= 0 && row < snapshot.costs().size()) {
                DockView.Cost cost = snapshot.costs().get(row);
                graphics.renderTooltip(font, new ItemStack(cost.item()), mouseX, mouseY);
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int right = leftPos + imageWidth, bottom = topPos + imageHeight;
        graphics.fill(leftPos - 2, topPos - 2, right + 2, bottom + 2, 0xDD0B181B);
        graphics.fill(leftPos, topPos, right, bottom, CANVAS);
        graphics.fill(leftPos, topPos, right, topPos + 24, INK);
        graphics.fill(leftPos, topPos + 23, right, topPos + 26, BRASS);
        graphics.fill(leftPos + 6, topPos + 48, right - 6, bottom - 32, CANVAS_DARK);
        graphics.drawString(font, I18n.get("gui.skydock.title"), leftPos + 10, topPos + 8, PALE, false);
        String berth = snapshot == null ? I18n.get("gui.skydock.awaiting") : I18n.get("gui.skydock.berth", snapshot.tier().toUpperCase(Locale.ROOT));
        berth = font.plainSubstrByWidth(berth, Math.max(58, imageWidth - 148));
        graphics.drawString(font, berth, right - 10 - font.width(berth), topPos + 8, TEAL, false);
        if (page == Page.DESIGNS) renderDesigns(graphics);
        else renderManual(graphics);
    }

    private void renderDesigns(GuiGraphics graphics) {
        int bodyY = topPos + 51, bodyBottom = topPos + imageHeight - 27;
        boolean wide = imageWidth >= 520 && imageHeight >= 260;
        int leftW = wide ? 120 : 0;
        int rightW = wide ? 151 : Math.max(108, (imageWidth - 18) * 37 / 100);
        previewX = leftPos + 9 + leftW;
        previewY = bodyY + (wide ? 0 : 25);
        previewW = imageWidth - 18 - leftW - rightW - 5;
        previewH = bodyBottom - previewY;
        costsX = previewX + previewW + 6;
        int rightTop = bodyY + (wide ? 0 : 25);
        costsY = rightTop + (wide ? 82 : 67);
        costsW = rightW - 1;
        costsH = Math.max(20, bodyBottom - costsY);

        if (wide) {
            String heading = pages() > 1 ? I18n.get("gui.skydock.patterns_page", patternPage + 1, pages()) : I18n.get("gui.skydock.patterns");
            graphics.drawString(font, font.plainSubstrByWidth(heading, 76), leftPos + 12, bodyY + 3, INK, false);
            if (snapshot != null) {
                DockView.Pattern selected = snapshot.patterns().stream().filter(p -> p.id().equals(snapshot.selected())).findFirst().orElse(null);
                if (selected != null) drawWrapped(graphics, I18n.get(selected.description()), leftPos + 12, bodyY + 162, 110, MUTED, 3);
            }
        }
        panel(graphics, previewX, previewY, previewW, previewH, INK);
        drawPreviewGrid(graphics, previewX, previewY, previewW, previewH);
        if (snapshot == null) {
            center(graphics, I18n.get("gui.skydock.reading"), previewX, previewY + previewH / 2, previewW, PALE);
        } else if (snapshot.preview().isEmpty()) {
            center(graphics, I18n.get("gui.skydock.no_preview"), previewX, previewY + previewH / 2, previewW, PALE);
        } else {
            preview.render(graphics, previewX, previewY, previewW, previewH, snapshot.preview());
        }
        String help = font.plainSubstrByWidth(I18n.get("gui.skydock.preview_help"), Math.max(10, previewW - 14));
        graphics.drawString(font, help, previewX + 7, previewY + previewH - 12, 0xFF9CCBC4, false);

        int rx = costsX, ry = rightTop;
        graphics.drawString(font, I18n.get("gui.skydock.figures"), rx + 3, ry + 3, INK, false);
        if (snapshot != null) renderStats(graphics, rx + 3, ry + (wide ? 16 : 13), costsW - 6, snapshot.stats());
        if (wide) graphics.drawString(font, I18n.get("gui.skydock.materials"), rx + 3, costsY - 12, INK, false);
        graphics.fill(costsX, costsY, costsX + costsW, costsY + costsH, 0xCCF6EED8);
        renderCosts(graphics);
        renderFooterStatus(graphics);
    }

    private void renderStats(GuiGraphics graphics, int x, int y, int width, DockView.Stats stats) {
        int gutter = 6;
        int columnWidth = (width - gutter) / 2;
        int rightX = x + columnWidth + gutter;
        int rightWidth = width - columnWidth - gutter;
        stat(graphics, I18n.get("gui.skydock.stat_mass"), format(stats.mass()), x, y, columnWidth);
        stat(graphics, I18n.get("gui.skydock.stat_lift"), format(stats.lift()), rightX, y, rightWidth);
        stat(graphics, I18n.get("gui.skydock.stat_power"), format(stats.power()), x, y + 11, columnWidth);
        stat(graphics, I18n.get("gui.skydock.stat_speed"), I18n.get("gui.skydock.per_second", format(stats.speed() * 20.0)), rightX, y + 11, rightWidth);
        stat(graphics, I18n.get("gui.skydock.stat_structural"), Integer.toString(stats.structuralBlocks()), x, y + 22, columnWidth);
        stat(graphics, I18n.get("gui.skydock.stat_blocks"), Integer.toString(stats.blocks()), rightX, y + 22, rightWidth);
        stat(graphics, I18n.get("gui.skydock.stat_cells"), Integer.toString(stats.cells()), x, y + 33, columnWidth);
        stat(graphics, I18n.get("gui.skydock.stat_engines"), Integer.toString(stats.engines()), rightX, y + 33, rightWidth);
        graphics.drawString(font, font.plainSubstrByWidth(I18n.get("gui.skydock.size", stats.width(), stats.height(), stats.length()), width), x, y + 44, INK, false);
    }

    private void stat(GuiGraphics graphics, String label, String value, int x, int y, int width) {
        int valueWidth = font.width(value);
        int labelWidth = Math.max(0, width - valueWidth - 2);
        String shownLabel = font.plainSubstrByWidth(label, labelWidth);
        if (!shownLabel.isEmpty()) graphics.drawString(font, shownLabel, x, y, MUTED, false);
        graphics.drawString(font, value, x + width - valueWidth, y, INK, false);
    }

    private void renderCosts(GuiGraphics graphics) {
        if (snapshot == null || snapshot.costs().isEmpty()) {
            center(graphics, I18n.get("gui.skydock.no_materials"), costsX, costsY + 8, costsW, MUTED);
            return;
        }
        int rows = visibleCostRows();
        for (int shown = 0; shown < rows; shown++) {
            int index = costScroll + shown;
            if (index >= snapshot.costs().size()) break;
            DockView.Cost cost = snapshot.costs().get(index);
            int y = costsY + shown * 20 + 2;
            ItemStack stack = new ItemStack(cost.item());
            graphics.renderFakeItem(stack, costsX + 3, y);
            int color = cost.missing() ? MISSING : INK;
            String count = cost.available() + " / " + cost.required();
            graphics.drawString(font, count, costsX + 23, y + 1, color, false);
            String clipped = font.plainSubstrByWidth(stack.getHoverName().getString(), costsW - 28);
            graphics.drawString(font, clipped, costsX + 23, y + 10, MUTED, false);
            if (cost.missing()) graphics.fill(costsX, y + 17, costsX + costsW, y + 19, 0x66A33A32);
        }
        if (snapshot.costs().size() > rows) {
            int barH = Math.max(8, costsH * rows / snapshot.costs().size());
            int barY = costsY + (costsH - barH) * costScroll / Math.max(1, snapshot.costs().size() - rows);
            graphics.fill(costsX + costsW - 2, barY, costsX + costsW, barY + barH, TEAL);
        }
    }

    private int visibleCostRows() { return Math.max(1, costsH / 20); }

    private void renderFooterStatus(GuiGraphics graphics) {
        int y = topPos + imageHeight - 25;
        int statusRight = assemble.getX() - 6;
        String status = snapshot == null ? I18n.get("gui.skydock.contacting") : Component.translatable(snapshot.status()).getString();
        if (waiting) status = I18n.get("gui.skydock.updating");
        if (snapshot != null && snapshot.active() && snapshot.total() > 0)
            status += "  ·  " + snapshot.progress() + " / " + snapshot.total();
        graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(40, statusRight - leftPos - 14)), leftPos + 10, y + 8, INK, false);
        if (snapshot != null && snapshot.total() > 0) {
            int barX = leftPos + 10, barY = y + 1, barW = Math.max(30, statusRight - barX);
            graphics.fill(barX, barY, barX + barW, barY + 4, 0x55213234);
            float fraction = snapshot.complete() ? 1.0F : Mth.clamp(snapshot.progress() / (float) snapshot.total(), 0, 1);
            graphics.fill(barX, barY, barX + Math.round(barW * fraction), barY + 4, TEAL);
        }
    }

    private void renderManual(GuiGraphics graphics) {
        int x = leftPos + 16, y = topPos + 58;
        graphics.enableScissor(leftPos + 7, topPos + 49, leftPos + imageWidth - 7, topPos + imageHeight - 32);
        graphics.drawString(font, I18n.get("gui.skydock.manual_title"), x, y, INK, false);
        y += 18;
        y = paragraph(graphics, I18n.get("gui.skydock.manual_inspect"), x, y, imageWidth - 32);
        y = paragraph(graphics, I18n.get("gui.skydock.manual_launch"), x, y + 7, imageWidth - 32);
        y = paragraph(graphics, I18n.get("gui.skydock.manual_redock"), x, y + 7, imageWidth - 32);
        y += 11;
        graphics.fill(x, y, leftPos + imageWidth - 16, y + 1, BRASS);
        y += 10;
        graphics.drawString(font, I18n.get("gui.skydock.live_status"), x, y, INK, false);
        y += 14;
        boolean complete = snapshot != null && snapshot.complete();
        boolean hullPresent = complete || snapshot != null && snapshot.manualHull();
        String status = snapshot == null ? I18n.get("gui.skydock.waiting_ledger") : complete ? I18n.get("gui.skydock.assembled_hint")
                : snapshot.manualHull() ? I18n.get("gui.skydock.manual_hint") : Component.translatable(snapshot.status()).getString();
        drawWrapped(graphics, status, x, y, imageWidth - 32, snapshot != null && (snapshot.spaceClear() || hullPresent) ? MUTED : MISSING, 3);
        if (snapshot != null) {
            String berthState = I18n.get(complete ? "gui.skydock.berth_assembled" : snapshot.manualHull() ? "gui.skydock.berth_manual"
                    : snapshot.spaceClear() ? "gui.skydock.berth_clear" : "gui.skydock.berth_obstructed");
            graphics.drawString(font, berthState, x, y + 34, snapshot.spaceClear() || hullPresent ? TEAL : MISSING, false);
        }
        graphics.disableScissor();
    }

    private int paragraph(GuiGraphics graphics, String text, int x, int y, int width) {
        List<FormattedCharSequence> lines = font.split(FormattedText.of(text), width);
        for (FormattedCharSequence line : lines) { graphics.drawString(font, line, x, y, MUTED, false); y += 10; }
        return y;
    }

    private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int color, int maxLines) {
        List<FormattedCharSequence> lines = font.split(FormattedText.of(text), width);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) graphics.drawString(font, lines.get(i), x, y + i * 10, color, false);
    }

    private void drawPreviewGrid(GuiGraphics graphics, int x, int y, int w, int h) {
        int horizon = y + h * 2 / 3;
        graphics.fill(x + 1, horizon, x + w - 1, y + h - 1, 0xFF19383C);
        for (int gx = x + 12; gx < x + w; gx += 18) graphics.fill(gx, horizon, gx + 1, y + h - 1, 0x224FA89C);
        for (int gy = horizon + 12; gy < y + h; gy += 12) graphics.fill(x + 1, gy, x + w - 1, gy + 1, 0x224FA89C);
        graphics.fill(x + 1, horizon, x + w - 1, horizon + 1, 0x664FA89C);
    }

    private void panel(GuiGraphics graphics, int x, int y, int w, int h, int border) {
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        graphics.fill(x, y, x + w, y + h, 0xFF17353A);
    }

    private void center(GuiGraphics graphics, String text, int x, int y, int width, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(text, width - 6), x + 3, y, color, false);
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "—";
        if (Math.abs(value) >= 1000) return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        return String.format(Locale.ROOT, value == Math.rint(value) ? "%.0f" : "%.1f", value);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (page == Page.DESIGNS && button == 0 && in(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
            preview.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page == Page.DESIGNS && in(mouseX, mouseY, previewX, previewY, previewW, previewH)) {
            preview.zoom(vertical);
            return true;
        }
        if (page == Page.DESIGNS && snapshot != null && in(mouseX, mouseY, costsX, costsY, costsW, costsH)) {
            int max = Math.max(0, snapshot.costs().size() - visibleCostRows());
            costScroll = Mth.clamp(costScroll - (int) Math.signum(vertical), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (page == Page.DESIGNS && keyCode == 82) {
            preview.reset();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        preview.close();
        DockView.remove(menu.containerId, menu.pos);
        super.removed();
    }

    private static boolean in(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** The canvas-and-timber button shared by the dock and terminal screens. */
    static final class ShipwrightButton extends AbstractButton {
        private final Runnable action;
        boolean selected;

        ShipwrightButton(int x, int y, int width, int height, Component label, Runnable action) {
            super(x, y, width, height, label);
            this.action = action;
        }

        void setBounds(int x, int y, int width, int height) {
            setX(x); setY(y); setWidth(width); setHeight(height);
        }

        @Override public void onPress() { action.run(); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int fill = !active ? 0x776D756E : selected ? TEAL : isHoveredOrFocused() ? BRASS : TIMBER;
            int border = selected ? PALE : INK;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
            renderScrollingString(graphics, Minecraft.getInstance().font, 3, active ? PALE : 0xFFB8B4A8);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
