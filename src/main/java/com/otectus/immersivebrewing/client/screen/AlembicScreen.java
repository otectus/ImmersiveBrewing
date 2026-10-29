package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.menu.AlembicMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.AlembicGroupC2S;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.session.SessionKind;
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
    private Button group;
    private Button practice;

    public AlembicScreen(AlembicMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AlembicMenu.WIDTH, AlembicMenu.HEIGHT);
        this.imageWidth = 296;
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
        group = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.alembic.group"),
                b -> ModNetwork.sendToServer(new AlembicGroupC2S(menu.pos()))).bounds(leftPos + 184, topPos + 164, 50, 18).build());
        practice = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.alembic.practice"),
                b -> ClientPacketHandler.practice(SessionKind.DISTILLATION)).bounds(leftPos + 238, topPos + 164, 50, 18).build());
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
        AlembicBlockEntity.OperationPreview preview = station instanceof AlembicBlockEntity alembic ? alembic.operationPreview() : null;
        purify.active = state == null && preview != null && preview.problem() == null;
        reclaim.active = state == null;
        group.visible = state == null;
        group.active = preview != null && preview.inputCount() > 0;
        practice.active = state == null;
    }

    @Override
    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {
        StationBlockEntity station = station();
        if (station != null && station.view().operation() == Operation.RECLAIM && station.view().state() == JobState.RUNNING) {
            Ui.progress(g, leftPos + 35, topPos + 82, 108, 4, station.view().visual().progress(), Ui.palette().accent());
        }
        Ui.inset(g, leftPos + 180, topPos + 7, 108, 151);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        StationBlockEntity station = station();
        if (station == null) return;
        Ui.Palette p = Ui.palette();
        Component fuel = Component.translatable("screen.immersive_brewing.fuel_charges", station.fuelCharges());
        label(g, station.view().status(), 8, 88, 160, p.text(), mouseX, mouseY);
        label(g, fuel, 8, 100, 160, p.muted(), mouseX, mouseY);
        if (!(station instanceof AlembicBlockEntity alembic)) return;
        AlembicBlockEntity.OperationPreview preview = alembic.operationPreview();
        int x = 184;
        int w = 100;
        label(g, Component.translatable("screen.immersive_brewing.alembic.preview"), x, 11, w, p.insetText(), mouseX, mouseY);
        if (preview.problem() != null) label(g, preview.problem(), x, 23, w, Ui.onDark(p.bad()), mouseX, mouseY);
        Component sample = preview.sample().isEmpty() ? Component.translatable("screen.immersive_brewing.alembic.none") : preview.sample().getHoverName();
        label(g, Component.translatable("screen.immersive_brewing.alembic.input", preview.inputCount(), sample), x, 35, w, p.insetText(), mouseX, mouseY);
        Component container = preview.returnedContainer().isEmpty() ? Component.empty() : preview.returnedContainer().getHoverName();
        label(g, Component.translatable("screen.immersive_brewing.alembic.purify_output", preview.purifyOutput(), preview.returnedContainers(), container),
                x, 47, w, p.insetText(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.fuel", preview.purifyFuel()), x, 59, w, p.insetMuted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.quality_range", preview.qualityMinimum(), preview.qualityMaximum()),
                x, 71, w, p.insetText(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.effects", preview.eligibleEffects(), preview.totalEffects()),
                x, 83, w, preview.eligibleEffects() > 0 ? Ui.onDark(p.good()) : p.insetMuted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.attempt", preview.attemptsRemaining()), x, 95, w,
                preview.attemptsRemaining() > 0 ? p.insetText() : Ui.onDark(p.bad()), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.keep", preview.inputCount(), preview.qualityIn()), x, 107, w,
                p.insetMuted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.purify_compare", preview.purifyOutput(), preview.qualityMaximum(),
                aggregateLoss(preview)), x, 119, w, p.insetText(), mouseX, mouseY);
        Component reclaim = preview.reclaimResult().isEmpty() ? Component.translatable("screen.immersive_brewing.alembic.unavailable")
                : Component.translatable("screen.immersive_brewing.alembic.reclaim_compare", preview.reclaimOutput(), preview.reclaimResult().getHoverName(),
                        preview.reclaimContainers(), preview.reclaimFuel());
        label(g, reclaim, x, 131, w, p.insetMuted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.alembic.attempt_warning"), x, 143, w, Ui.onDark(p.warn()), mouseX, mouseY);
    }

    private static int aggregateLoss(AlembicBlockEntity.OperationPreview preview) {
        double keep = preview.inputCount() * QualityMath.durationMultiplier(preview.qualityIn());
        double refined = preview.purifyOutput() * QualityMath.durationMultiplier(preview.qualityMaximum());
        return keep <= 0 ? 0 : Math.max(0, (int) Math.round(100 * (1 - refined / keep)));
    }
}
