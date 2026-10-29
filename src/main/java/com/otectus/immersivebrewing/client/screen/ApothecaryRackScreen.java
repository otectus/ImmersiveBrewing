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
        imageWidth = 176;
        imageHeight = 167;
        inventoryLabelY = 74;
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
                    .bounds(leftPos + 34 + i * 18, topPos + 41, 17, 11).build());
        }
        shelfLabel = new EditBox(font, leftPos + 8, topPos + 55, 64, 16, Component.translatable("screen.immersive_brewing.rack.label"));
        shelfLabel.setMaxLength(ApothecaryRackBlockEntity.MAX_LABEL);
        refreshLabel();
        addRenderableWidget(shelfLabel);
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.save_label"), b -> {
            ModNetwork.sendToServer(new RackLabelC2S(menu.pos(), selectedShelf, shelfLabel.getValue()));
        }).bounds(leftPos + 76, topPos + 55, 25, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.filter"), b -> {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, selectedShelf);
        }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.rack.filter_tip")))
                .bounds(leftPos + 104, topPos + 55, 31, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.rack.pack"), b -> {
            if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ApothecaryRackMenu.PACK_BUTTON);
        }).bounds(leftPos + 138, topPos + 55, 30, 16).build());
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
                g.renderItem(filter, leftPos + slot.x, topPos + slot.y);
                g.fill(leftPos + slot.x, topPos + slot.y, leftPos + slot.x + 16, topPos + slot.y + 16, 0x88707070);
            }
            int marker = i == selectedShelf ? 0xFFFFFFFF : filter.isEmpty() ? 0xFF777777 : 0xFF55AAFF;
            g.fill(leftPos + slot.x - 1, topPos + 41, leftPos + slot.x + 17, topPos + 43, marker);
            String label = rack.label(i);

        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Ui.fit(g, title, titleLabelX, titleLabelY, imageWidth - 16, Ui.palette().text());
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.palette().text(), false);
    }
}
