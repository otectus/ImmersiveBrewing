package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.menu.MortarMenu;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Loading the Mortar: the reagent, the kit shelf, and how many kits (1-4) to prepare in one session. */
public class MortarScreen extends StationScreen<MortarMenu> {
    private int kits = 1;
    private Button prepare;
    private Button resume;

    public MortarScreen(MortarMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, MortarMenu.WIDTH, MortarMenu.HEIGHT);
        inventoryLabelX = 23;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("−"), b -> kits = Math.max(1, kits - 1)).bounds(leftPos + 8, topPos + 96, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> kits = Math.min(PreparationParams.MAX_KITS, kits + 1))
                .bounds(leftPos + 50, topPos + 96, 14, 16).build());
        prepare = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.prepare"), b -> start())
                .bounds(leftPos + 80, topPos + 96, 120, 20).build());
        resume = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.resume"),
                b -> action(StationActionC2S.Action.RESUME, 0)).bounds(leftPos + 80, topPos + 96, 120, 20).build());
    }

    private void start() {
        boolean assist = ClientConfig.get(ClientConfig.REQUEST_ASSIST);
        double time = assist ? ClientConfig.get(ClientConfig.ASSIST_TIME) : 1.0;
        double band = assist ? ClientConfig.get(ClientConfig.ASSIST_BAND) : 1.0;
        action(StationActionC2S.Action.START_PREPARATION, StationActionC2S.withAssist(kits, time, band));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        StationBlockEntity station = station();
        boolean sealed = station != null && station.view().state() == JobState.SEALED;
        resume.visible = sealed;
        prepare.visible = !sealed;
        prepare.active = station != null && station.view().state() == null && !menu.slots.get(0).getItem().isEmpty();
    }

    @Override
    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        Ui.centered(g, Component.literal("×" + kits), leftPos + 36, topPos + 100, p.text());
        Ui.arrow(g, leftPos + 82, topPos + 60, p.muted());
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        StationBlockEntity station = station();
        if (station != null) Ui.wrappedMax(g, station.view().status(), 8, 20, imageWidth - 16, 2, Ui.palette().muted());
        label(g, Component.translatable("screen.immersive_brewing.kits"), 126, 84, 72, Ui.palette().muted(), mouseX, mouseY);
    }
}
