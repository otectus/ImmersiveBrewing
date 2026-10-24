package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.menu.BatchCarrierMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** The open Batch Carrier: six potion slots. */
public class BatchCarrierScreen extends AbstractContainerScreen<BatchCarrierMenu> {
    public BatchCarrierScreen(BatchCarrierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 133;
        this.inventoryLabelY = 40;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        if (font.width(title) > imageWidth - 16 && Ui.inside(mouseX, mouseY, leftPos + 8, topPos + 6, imageWidth - 16, 10)) {
            g.renderTooltip(font, font.split(title, 240), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        Ui.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (Slot slot : menu.slots) Ui.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Ui.fit(g, title, titleLabelX, titleLabelY, imageWidth - 16, Ui.palette().text());
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.palette().text(), false);
    }
}
