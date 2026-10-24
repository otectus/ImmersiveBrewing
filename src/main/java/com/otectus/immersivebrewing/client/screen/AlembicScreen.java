package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.menu.AlembicMenu;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Loading the Alembic. Purification and reclamation are separate buttons with separate explanations (spec 9.2: keep
 * their input rules and previews visibly separate).
 */
public class AlembicScreen extends StationScreen<AlembicMenu> {
    private Button purify;
    private Button reclaim;
    private Button cancel;
    private Button resume;

    public AlembicScreen(AlembicMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AlembicMenu.WIDTH, AlembicMenu.HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        purify = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.purify"), b -> {
            boolean assist = ClientConfig.get(ClientConfig.REQUEST_ASSIST);
            action(StationActionC2S.Action.START_PURIFICATION, StationActionC2S.withAssist(0, assist ? ClientConfig.get(ClientConfig.ASSIST_TIME) : 1.0,
                    assist ? ClientConfig.get(ClientConfig.ASSIST_BAND) : 1.0));
        }).bounds(leftPos + 30, topPos + 40, 66, 18).build());
        purify.setTooltip(Tooltip.create(Component.translatable("screen.immersive_brewing.purify_tip")));
        reclaim = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.reclaim"),
                b -> action(StationActionC2S.Action.START_RECLAMATION, 0)).bounds(leftPos + 100, topPos + 40, 66, 18).build());
        reclaim.setTooltip(Tooltip.create(Component.translatable("screen.immersive_brewing.reclaim_tip")));
        cancel = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.cancel_reclaim"),
                b -> action(StationActionC2S.Action.CANCEL_PASSIVE, 0)).bounds(leftPos + 100, topPos + 40, 66, 18).build());
        resume = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.resume"),
                b -> action(StationActionC2S.Action.RESUME, 0)).bounds(leftPos + 30, topPos + 40, 66, 18).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        StationBlockEntity station = station();
        JobState state = station == null ? null : station.view().state();
        Operation op = station == null ? null : station.view().operation();
        boolean reclaiming = op == Operation.RECLAIM && state == JobState.RUNNING;
        boolean sealed = state == JobState.SEALED;
        purify.visible = !sealed;
        resume.visible = sealed;
        reclaim.visible = !reclaiming;
        cancel.visible = reclaiming;
        purify.active = state == null;
        reclaim.active = state == null;
    }

    @Override
    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {
        StationBlockEntity station = station();
        if (station != null && station.view().operation() == Operation.RECLAIM && station.view().state() == JobState.RUNNING) {
            Ui.progress(g, leftPos + 35, topPos + 82, 108, 4, station.view().visual().progress(), Ui.palette().accent());
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        StationBlockEntity station = station();
        if (station == null) return;
        Ui.Palette p = Ui.palette();
        Component fuel = Component.translatable("screen.immersive_brewing.fuel_charges", station.fuelCharges());
        label(g, station.view().status(), 8, 88, imageWidth - 16, p.text(), mouseX, mouseY);
        label(g, fuel, 8, 100, imageWidth - 16, p.muted(), mouseX, mouseY);
    }
}
