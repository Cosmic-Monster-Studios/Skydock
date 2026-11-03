package dev.skydock.client;

import dev.skydock.menu.EngineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class EngineScreen extends AbstractContainerScreen<EngineMenu> {
    private static final int DARK = 0xFF132C31, TEAL = 0xFF4FA89C, CANVAS = 0xFFE7D6AA, BRASS = 0xFFC69852;
    public EngineScreen(EngineMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 282; imageHeight = 188; }
    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        g.fill(leftPos - 2, topPos - 2, leftPos + imageWidth + 2, topPos + imageHeight + 2, BRASS);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, DARK);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + 5, TEAL);
        for (int i = 0; i < 5; i++) {
            int x = leftPos + 95 + i * 18; g.fill(x, topPos + 67, x + 18, topPos + 85, BRASS); g.fill(x + 1, topPos + 68, x + 17, topPos + 84, 0xFF203C40);
        }
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int y = topPos + (row == 3 ? 162 : 104 + row * 18), x = leftPos + 59 + col * 18;
            g.fill(x, y, x + 18, y + 18, 0xFF315056); g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF1B3439);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var tier = menu.tier();
        g.drawString(font, title, 14, 11, CANVAS, false);
        g.drawString(font, I18n.get("gui.skydock.engine_thrust"), 14, 28, BRASS, false);
        g.drawString(font, I18n.get("gui.skydock.engine_fuel"), 99, 28, BRASS, false);
        g.drawString(font, I18n.get("gui.skydock.engine_speed_limit"), 184, 28, BRASS, false);
        g.drawString(font, I18n.get("gui.skydock.multiplier", String.format(java.util.Locale.ROOT, "%.1f", tier.thrustMultiplier)), 14, 40, 0xFFFFFFFF, false);
        g.drawString(font, I18n.get("gui.skydock.multiplier", String.format(java.util.Locale.ROOT, "%.2f", tier.fuelEfficiency)), 99, 40, 0xFFFFFFFF, false);
        g.drawString(font, I18n.get("gui.skydock.blocks_per_second", String.format(java.util.Locale.ROOT, "%.1f", tier.maxSpeed * 20)), 184, 40, 0xFFFFFFFF, false);
        g.drawString(font, menu.burnTicks() > 0 ? I18n.get("gui.skydock.engine_burning", menu.burnTicks()) : I18n.get("gui.skydock.engine_idle"), 14, 54, menu.burnTicks() > 0 ? 0xFF8BE2C8 : 0xFFE7B98E, false);
        g.drawString(font, I18n.get("gui.skydock.inventory"), 60, 94, CANVAS, false);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) { super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY); }
}
