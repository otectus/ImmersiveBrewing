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
    private final Ui.ScrollArea comparison = new Ui.ScrollArea();
    private Button purify;
    private Button reclaim;
    private Button cancel;
    private Button resume;
    private Button group;
    private Button practice;

    public AlembicScreen(AlembicMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AlembicMenu.WIDTH, AlembicMenu.HEIGHT);
        this.imageWidth = 312;
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
                b -> ModNetwork.sendToServer(new AlembicGroupC2S(menu.pos()))).bounds(leftPos + 180, topPos + 164, 60, 18).build());
        practice = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.alembic.practice"),
                b -> ClientPacketHandler.practice(SessionKind.DISTILLATION)).bounds(leftPos + 244, topPos + 164, 60, 18).build());
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
        reclaim.active = state == null && preview != null && preview.inputCount() > 0 && !preview.reclaimResult().isEmpty();
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
        Ui.inset(g,leftPos+176,topPos+7,128,151);
        if (!(station instanceof AlembicBlockEntity alembic)) return;
        Ui.Palette p=Ui.palette();
        comparison.bounds(leftPos+182,topPos+13,116,139);
        comparison.begin(g);
        int y=comparison.top(),x=leftPos+182,w=comparison.width();
        boolean outputWaiting=false;
        for(int i=0;i<AlembicBlockEntity.OUTPUT_SIZE;i++) outputWaiting|=!menu.slots.get(AlembicBlockEntity.OUTPUT+i).getItem().isEmpty();
        String heading=outputWaiting?"screen.immersive_brewing.result":station.view().state()!=null?"screen.immersive_brewing.alembic":"screen.immersive_brewing.alembic.preview";
        y+=Ui.wrapped(g,Component.translatable(heading).withStyle(style->style.withBold(true)),x,y,w,p.insetText())+6;
        if(station.view().state()!=null||outputWaiting) {
            y+=Ui.wrapped(g,station.view().status(),x,y,w,p.insetText())+6;
            if(outputWaiting) for(int i=0;i<AlembicBlockEntity.OUTPUT_SIZE;i++) {
                var stack=menu.slots.get(AlembicBlockEntity.OUTPUT+i).getItem();
                if(stack.isEmpty()) continue;
                g.renderItem(stack,x,y);
                y+=Math.max(20,Ui.wrapped(g,Component.translatable("screen.immersive_brewing.result.made",stack.getCount(),stack.getHoverName()),x+22,y,w-22,p.insetText())+4);
            }
            y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.fuel_charges",station.fuelCharges()),x,y,w,p.insetMuted());
        } else {
            AlembicBlockEntity.OperationPreview preview=alembic.operationPreview();
            if(preview.problem()!=null) y+=Ui.wrapped(g,preview.problem(),x,y,w,Ui.onDark(p.warn()))+5;
            if(preview.inputCount()>0) {
                Component sample=preview.sample().getHoverName();
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.input",preview.inputCount(),sample),x,y,w,p.insetText())+5;
                Component container=preview.returnedContainer().isEmpty()?Component.empty():preview.returnedContainer().getHoverName();
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.purify_output",preview.purifyOutput(),preview.returnedContainers(),container),x,y,w,p.insetText())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.fuel",preview.purifyFuel()),x,y,w,p.insetMuted())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.quality_range",preview.qualityMinimum(),preview.qualityMaximum()),x,y,w,p.insetText())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.effects",preview.eligibleEffects(),preview.totalEffects()),x,y,w,p.insetMuted())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.purify_compare",preview.purifyOutput(),preview.qualityMaximum(),aggregateLoss(preview)),x,y,w,p.insetText())+5;
                Component reclaim=preview.reclaimResult().isEmpty()?Component.translatable("screen.immersive_brewing.alembic.unavailable"):
                        Component.translatable("screen.immersive_brewing.alembic.reclaim_compare",preview.reclaimOutput(),preview.reclaimResult().getHoverName(),preview.reclaimContainers(),preview.reclaimFuel());
                y+=Ui.wrapped(g,reclaim,x,y,w,p.insetText())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.keep",preview.inputCount(),preview.qualityIn()),x,y,w,p.insetMuted())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.attempt",preview.attemptsRemaining()),x,y,w,p.insetMuted())+5;
                y+=Ui.wrapped(g,Component.translatable("screen.immersive_brewing.alembic.attempt_warning"),x,y,w,Ui.onDark(p.warn()));
            }
        }
        comparison.end(g,y);
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
    }

    @Override
    public boolean mouseScrolled(double mx,double my,double delta) {
        return comparison.wheel(mx,my,delta)||super.mouseScrolled(mx,my,delta);
    }

    @Override
    public boolean mouseClicked(double mx,double my,int button) {
        return comparison.click(mx,my,button)||super.mouseClicked(mx,my,button);
    }

    @Override
    public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        return comparison.drag(my)||super.mouseDragged(mx,my,button,dx,dy);
    }

    @Override
    public boolean mouseReleased(double mx,double my,int button) {
        comparison.release();return super.mouseReleased(mx,my,button);
    }

    @Override
    public boolean keyPressed(int key,int scan,int modifiers) {
        return comparison.key(key)||super.keyPressed(key,scan,modifiers);
    }

    private static int aggregateLoss(AlembicBlockEntity.OperationPreview preview) {
        double keep = preview.inputCount() * QualityMath.durationMultiplier(preview.qualityIn());
        double refined = preview.purifyOutput() * QualityMath.durationMultiplier(preview.qualityMaximum());
        return keep <= 0 ? 0 : Math.max(0, (int) Math.round(100 * (1 - refined / keep)));
    }
}
