package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.menu.ApothecaryRackMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.RackLabelC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Six real slots, exact reservation ghosts, short labels and one-click carrier packing. */
public class ApothecaryRackScreen extends AbstractContainerScreen<ApothecaryRackMenu> {
    private EditBox shelfLabel;
    private int selectedShelf;

    public ApothecaryRackScreen(ApothecaryRackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = ApothecaryRackMenu.WIDTH;
        imageHeight = ApothecaryRackMenu.HEIGHT;
        inventoryLabelX = 43;
        inventoryLabelY = 138;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) {
            final int index = i;
            addRenderableWidget(Button.builder(Component.literal(Integer.toString(i + 1)), b -> {
                selectedShelf = index; refreshLabel();
                if (minecraft != null) minecraft.getNarrator().sayNow(Component.translatable("screen.immersive_brewing.rack.slot", index + 1,
                        menu.slots.get(index).getItem().getHoverName(), menu.rack() == null ? "" : menu.rack().label(index)));
            }).createNarration(ignored -> Component.translatable("screen.immersive_brewing.rack.slot", index + 1,
                    menu.slots.get(index).getItem().getHoverName(), menu.rack() == null ? "" : menu.rack().label(index)))
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.rack.select", i + 1)))
                    .bounds(leftPos + menu.slots.get(i).x - 1, topPos + 56, 17, 12).build());
        }
        String draft = shelfLabel == null ? null : shelfLabel.getValue();
        shelfLabel = new EditBox(font, leftPos + 8, topPos + 86, 168, 18, Component.translatable("screen.immersive_brewing.rack.label"));
        shelfLabel.setMaxLength(ApothecaryRackBlockEntity.MAX_LABEL);
        refreshLabel();
        if (draft != null) shelfLabel.setValue(draft);
        addRenderableWidget(shelfLabel);
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.save_label"), b -> {
            ModNetwork.sendToServer(new RackLabelC2S(menu.pos(), selectedShelf, shelfLabel.getValue()));
        }).bounds(leftPos + 182, topPos + 86, 58, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.filter"), b -> {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, selectedShelf);
        }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.rack.filter_tip")))
                .bounds(leftPos + 8, topPos + 112, 112, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.pack"), b -> {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ApothecaryRackMenu.PACK_BUTTON);
        }).bounds(leftPos + 128, topPos + 112, 112, 20).build());
    }

    private void refreshLabel() {
        ApothecaryRackBlockEntity rack = menu.rack();
        shelfLabel.setValue(rack == null ? "" : rack.label(selectedShelf));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        Ui.panel(g, leftPos, topPos, imageWidth, imageHeight);
        ApothecaryRackBlockEntity rack = menu.rack();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            Ui.slot(g, leftPos + slot.x - 1, topPos + slot.y - 1);
            if (i >= ApothecaryRackBlockEntity.SLOTS || rack == null) continue;
            ItemStack filter = rack.filter(i);
            if (slot.getItem().isEmpty() && !filter.isEmpty()) {
                // A reservation is a plaque, not a ghost bottle that can be
                // mistaken for stock ready to collect.
                int x = leftPos + slot.x, y = topPos + slot.y;
                int c = Ui.palette().accent();
                g.fill(x + 3, y + 3, x + 13, y + 4, c);
                g.fill(x + 3, y + 12, x + 13, y + 13, c);
                g.fill(x + 3, y + 3, x + 4, y + 13, c);
                g.fill(x + 12, y + 3, x + 13, y + 13, c);
                g.drawString(font, "R", x + 6, y + 5, c, false);
            }
            int marker = i == selectedShelf ? Ui.palette().accent() : filter.isEmpty() ? Ui.palette().muted() : Ui.palette().good();
            g.fill(leftPos + slot.x - 1, topPos + slot.y + 16, leftPos + slot.x + 17, topPos + slot.y + 18, marker);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Ui.fit(g, title, titleLabelX, titleLabelY, imageWidth - 16, Ui.palette().text());
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.palette().text(), false);
        Ui.fit(g, Component.translatable("screen.immersive_brewing.rack.label"), 8, 74, imageWidth - 16, Ui.palette().muted());
        ApothecaryRackBlockEntity rack = menu.rack();
        if (rack != null) Ui.fit(g, Component.translatable("screen.immersive_brewing.rack.selected", selectedShelf + 1, rack.label(selectedShelf)),
                8, 18, imageWidth - 16, Ui.palette().muted());
    }
}
