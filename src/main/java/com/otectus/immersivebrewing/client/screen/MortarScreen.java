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
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("−"), b -> kits = Math.max(1, kits - 1)).bounds(leftPos + 8, topPos + 60, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> kits = Math.min(PreparationParams.MAX_KITS, kits + 1))
                .bounds(leftPos + 50, topPos + 60, 14, 16).build());
        prepare = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.prepare"), b -> start())
                .bounds(leftPos + 68, topPos + 60, 70, 16).build());
        resume = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.resume"),
                b -> action(StationActionC2S.Action.RESUME, 0)).bounds(leftPos + 68, topPos + 60, 70, 16).build());
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
        prepare.active = station != null && station.view().state() == null;
    }

    @Override
    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        Ui.centered(g, Component.literal("×" + kits), leftPos + 36, topPos + 64, p.text());
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        StationBlockEntity station = station();
        if (station != null) label(g, station.view().status(), 8, 16, imageWidth - 16, Ui.palette().muted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.kits"), 136, 30, 32, Ui.palette().muted(), mouseX, mouseY);
    }
}
