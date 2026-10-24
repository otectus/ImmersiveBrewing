package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/** Vanilla-styled container screen for a station: panel, slot wells, title and the station's live status line. */
public abstract class StationScreen<M extends StationMenu> extends AbstractContainerScreen<M> {
    private Component hoveredText;
    protected StationScreen(M menu, Inventory inventory, Component title, int width, int height) {
        super(menu, inventory, title);
        this.imageWidth = width;
        this.imageHeight = height;
        this.inventoryLabelY = height - 94;
    }

    @Nullable
    protected StationBlockEntity station() {
        return menu.station();
    }

    protected void action(StationActionC2S.Action action, int argument) {
        ModNetwork.sendToServer(new StationActionC2S(menu.pos(), action, argument));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hoveredText = null;
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (hoveredText != null) g.renderTooltip(font, font.split(hoveredText, 240), mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        Ui.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) Ui.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1);
        renderExtras(g, mouseX, mouseY);
    }

    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {}

    /** The station's status, wrapped into the given box. */
    protected void status(GuiGraphics g, int x, int y, int width) {
        StationBlockEntity station = station();
        if (station == null) return;
        Ui.wrapped(g, station.view().status(), x, y, width, Ui.palette().text());
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        label(g, title, titleLabelX, titleLabelY, imageWidth - 16, Ui.palette().text(), mouseX, mouseY);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.palette().text(), false);
    }

    protected void label(GuiGraphics g, Component text, int x, int y, int w, int color, int mouseX, int mouseY) {
        Ui.fit(g, text, x, y, w, color);
        if (font.width(text) > w && Ui.inside(mouseX - leftPos, mouseY - topPos, x, y, w, 10)) hoveredText = text;
    }
}
