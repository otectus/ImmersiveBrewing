package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.menu.BatchCarrierMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.CarrierSettingsC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The open Batch Carrier: six potion slots. */
public class BatchCarrierScreen extends AbstractContainerScreen<BatchCarrierMenu> {
    private EditBox loadoutName;
    private final java.util.List<Button> selections = new java.util.ArrayList<>();

    public BatchCarrierScreen(BatchCarrierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = BatchCarrierMenu.WIDTH;
        this.imageHeight = BatchCarrierMenu.HEIGHT;
        this.inventoryLabelY = 140;
        this.inventoryLabelX = 43;
    }

    @Override
    protected void init() {
        super.init();
        selections.clear();
        for (int i = 0; i < CarrierContents.SLOTS; i++) {
            final int index = i;
            selections.add(addRenderableWidget(Button.builder(Component.literal(Integer.toString(i + 1)), b ->
                    send(CarrierSettingsC2S.Action.SELECT, index, ""))
                    .createNarration(ignored -> Component.translatable("screen.immersive_brewing.carrier.slot", index + 1,
                            menu.slots.get(index).getItem().isEmpty() ? Component.translatable("screen.immersive_brewing.carrier.empty")
                                    : menu.slots.get(index).getItem().getHoverName()))
                    .bounds(leftPos + 69 + i * 18, topPos + 46, 17, 12).build()));
        }
        String draft = loadoutName == null ? null : loadoutName.getValue();
        loadoutName = new EditBox(font, leftPos + 8, topPos + 78, 154, 18, Component.translatable("screen.immersive_brewing.carrier.loadout"));
        loadoutName.setMaxLength(CarrierContents.MAX_LOADOUT_NAME);
        String name = CarrierContents.meta(menu.carrier()).loadoutName();
        loadoutName.setValue(draft != null ? draft : name == null ? "" : name);
        addRenderableWidget(loadoutName);
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.save_short"), b ->
                send(CarrierSettingsC2S.Action.SAVE_LOADOUT, 0, loadoutName.getValue()))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.carrier.save")))
                .bounds(leftPos + 168, topPos + 78, 72, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.carrier.advance"), b ->
                send(CarrierSettingsC2S.Action.SET_ADVANCE, menu.advanceOnEmpty() ? 0 : 1, "")).bounds(leftPos + 8, topPos + 104, 112, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.carrier.color"), b ->
                send(CarrierSettingsC2S.Action.SET_COLOR, nextColor(menu.labelColor()), "")).bounds(leftPos + 128, topPos + 104, 52, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.carrier.emblem"), b ->
                send(CarrierSettingsC2S.Action.SET_EMBLEM, (menu.emblem() + 1) % CarrierContents.EMBLEM_COUNT, ""))
                .bounds(leftPos + 184, topPos + 104, 56, 20).build());
    }

    private void send(CarrierSettingsC2S.Action action, int value, String text) {
        ModNetwork.sendToServer(new CarrierSettingsC2S(action, value, text));
    }

    private static int nextColor(int current) {
        int[] colors = {0x8B5A2B, 0xA33B32, 0xC07A24, 0xD2B84B, 0x3B7A57, 0x315A91, 0x684A91, 0xE0E0E0};
        for (int i = 0; i < colors.length; i++) if (colors[i] == current) return colors[(i + 1) % colors.length];
        return colors[0];
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        for (int i = 0; i < selections.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            Component name = Component.translatable("screen.immersive_brewing.carrier.slot", i + 1,
                    stack.isEmpty() ? Component.translatable("screen.immersive_brewing.carrier.empty") : stack.getHoverName());
            selections.get(i).setTooltip(net.minecraft.client.gui.components.Tooltip.create(name));
            selections.get(i).setMessage(Component.literal((menu.selected() == i ? ">" : "") + (i + 1)));
        }
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
        CarrierContents.Meta meta = CarrierContents.meta(menu.carrier());
        for (int i = 0; i < CarrierContents.SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            ItemStack ghost = meta.templates().get(i);
            if (slot.getItem().isEmpty() && !ghost.isEmpty()) {
                g.renderItem(ghost, x, y);
                g.fill(x, y, x + 16, y + 16, 0x88707070);
            }
            ItemStack actual = slot.getItem();
            boolean missing = !ghost.isEmpty() && (actual.isEmpty() || !ItemStack.isSameItemSameTags(ghost, actual) || actual.getCount() < ghost.getCount());
            if (missing) {
                g.drawString(font, "!", x + 11, y - 3, Ui.palette().bad(), true);
                if (menu.selected() == i) {
                    String key = actual.isEmpty() ? "screen.immersive_brewing.carrier.missing"
                            : !ItemStack.isSameItemSameTags(ghost, actual) ? "screen.immersive_brewing.carrier.different"
                            : "screen.immersive_brewing.carrier.count_short";
                    // Full mismatch text stays in the selected-slot tooltip/narration;
                    // never draw it over the player inventory.
                }
            }
            if (menu.selected() == i) {
                g.fill(x - 2, y - 2, x + 18, y, 0xFFFFFFFF);
                g.fill(x - 2, y, x, y + 18, 0xFFFFFFFF);
                g.fill(x, y + 16, x + 18, y + 18, 0xFFFFFFFF);
                g.fill(x + 16, y, x + 18, y + 16, 0xFFFFFFFF);
            }
        }
        // Saved carrier tag preview: only this mod's carrier UI is painted.
        int tag = 0xFF000000 | menu.labelColor();
        g.fill(leftPos + 184, topPos + 130, leftPos + 240, topPos + 142, tag);
        g.fill(leftPos + 186, topPos + 132, leftPos + 238, topPos + 140, 0xFFE8DFC8);
        String emblem = Integer.toString(menu.emblem() + 1);
        Ui.centered(g, Component.literal(emblem), leftPos + 212, topPos + 132, tag);
        if (menu.advanceOnEmpty()) g.drawString(font, "⟳", leftPos + 106, topPos + 110, Ui.palette().good(), false);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        Ui.fit(g, title, titleLabelX, titleLabelY, imageWidth - 16, Ui.palette().text());
        Ui.fit(g,Component.translatable("screen.immersive_brewing.carrier.loadout"),8,66,imageWidth-16,Ui.palette().muted());
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Ui.palette().text(), false);
    }
}
