package dev.skydock.client;

import dev.skydock.block.DeviceBlock;
import dev.skydock.menu.DeviceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class DeviceScreen extends AbstractContainerScreen<DeviceMenu> {
    private static final int DARK = 0xFF132C31, TEAL = 0xFF4FA89C, CANVAS = 0xFFE7D6AA, BRASS = 0xFFC69852;
    private Button primaryAction;
    public DeviceScreen(DeviceMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 286; imageHeight = 166; }
    @Override protected void init() {
        super.init();
        if (menu.view.ship() != null && actionable(menu.view.kind()))
            primaryAction = addRenderableWidget(Button.builder(actionLabel(), button -> action()).bounds(leftPos + 24, topPos + 132, imageWidth - 48, 20).build());
    }
    private static boolean actionable(DeviceBlock.Kind kind) { return kind == DeviceBlock.Kind.HELM || kind == DeviceBlock.Kind.CLAMP || kind == DeviceBlock.Kind.SEAT; }
    private Component actionLabel() {
        int flags = menu.flags();
        return switch (menu.view.kind()) {
            case HELM -> (flags & 8) != 0 ? Component.translatable("gui.skydock.helm_release", SkydockClient.releaseKey()) : Component.translatable("gui.skydock.helm_engage");
            case CLAMP -> Component.translatable((flags & 1) != 0 ? "gui.skydock.clamp_release" : "gui.skydock.clamp_secure");
            case SEAT -> (flags & 32) != 0 ? Component.translatable("gui.skydock.seat_stand", SkydockClient.releaseKey()) : Component.translatable("gui.skydock.seat_take");
            default -> Component.empty();
        };
    }
    private void action() { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0); }
    @Override protected void containerTick() { super.containerTick(); if (primaryAction != null) primaryAction.setMessage(actionLabel()); }
    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, BRASS);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, DARK);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + 5, TEAL);
        g.fill(leftPos + 15, topPos + 34, leftPos + imageWidth - 15, topPos + 124, 0xFF1C3A3F);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 16, 13, CANVAS, false);
        String[] paragraphs = lines(); int y = 42;
        for (int paragraph = 0; paragraph < paragraphs.length; paragraph++) {
            for (var line : font.split(net.minecraft.network.chat.FormattedText.of(paragraphs[paragraph]), imageWidth - 48)) {
                g.drawString(font, line, 24, y, paragraph == 0 ? BRASS : 0xFFFFFFFF, false); y += 12;
            }
            y += 2;
        }
    }
    private String[] lines() {
        var view = menu.view; int flags = menu.flags();
        return switch (view.kind()) {
            case HELM -> new String[] { I18n.get((flags & 8) != 0 ? "gui.skydock.helm_yours" : (flags & 4) != 0 ? "gui.skydock.helm_occupied" : "gui.skydock.helm_ready"),
                    I18n.get("gui.skydock.ship_speed", String.format(java.util.Locale.ROOT, "%.1f", menu.speed())),
                    (flags & 2) != 0 ? I18n.get("gui.skydock.cruise_active") : I18n.get("gui.skydock.helm_controls", SkydockClient.thrustKeys(), SkydockClient.turnKeys(), SkydockClient.altitudeKeys()),
                    I18n.get("gui.skydock.cruise_toggle", SkydockClient.cruiseKey()) };
            case CLAMP -> new String[] { I18n.get((flags & 1) != 0 ? "gui.skydock.clamp_state_secured" : "gui.skydock.clamp_state_released"), I18n.get("gui.skydock.clamp_note1"), I18n.get("gui.skydock.clamp_note2") };
            case SEAT -> new String[] { I18n.get((flags & 32) != 0 ? "gui.skydock.seat_seated" : (flags & 16) != 0 ? "gui.skydock.seat_occupied" : "gui.skydock.seat_available"), I18n.get("gui.skydock.seat_note1"), I18n.get("gui.skydock.seat_note2", SkydockClient.releaseKey()) };
            case LIFT -> new String[] { I18n.get(menu.amount() == 1 ? "gui.skydock.lift_one" : "gui.skydock.lift_many", menu.amount()), I18n.get("gui.skydock.lift_cluster", String.format(java.util.Locale.ROOT, "%.0f", menu.value())), I18n.get("gui.skydock.lift_note") };
            case BALLAST -> new String[] { I18n.get("gui.skydock.ballast_mass", String.format(java.util.Locale.ROOT, "%.0f", menu.value())), I18n.get("gui.skydock.ballast_note1"), I18n.get("gui.skydock.ballast_note2") };
        };
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) { super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY); }
}
