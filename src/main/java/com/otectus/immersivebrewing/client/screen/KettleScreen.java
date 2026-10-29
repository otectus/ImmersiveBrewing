package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Loading the Kettle (outside active play, spec 24.2): rack, tray, fuel, paddle, by-products, status and the plan picker. */
public class KettleScreen extends StationScreen<KettleMenu> {
    private Button plan;
    private Button resume;
    private Button comparator;
    private Button seal;

    public KettleScreen(KettleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, KettleMenu.WIDTH, KettleMenu.HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        plan = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.choose_plan"),
                b -> action(StationActionC2S.Action.REQUEST_PLANS, 0)).bounds(leftPos + 8, topPos + 66, 76, 18).build());
        resume = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.resume"),
                b -> action(StationActionC2S.Action.RESUME, 0)).bounds(leftPos + 8, topPos + 66, 76, 18).build());
        comparator = addRenderableWidget(Button.builder(Component.literal("≡"), b -> action(StationActionC2S.Action.TOGGLE_COMPARATOR, 0))
                .bounds(leftPos + 88, topPos + 66, 18, 18).build());
        comparator.setTooltip(Tooltip.create(Component.translatable("screen.immersive_brewing.comparator_tip")));
        addRenderableWidget(Button.builder(Component.translatable("staging.immersive_brewing.collect"),b->batch(false))
                .bounds(leftPos+imageWidth+3,topPos+4,65,18).build());
        addRenderableWidget(Button.builder(Component.translatable("staging.immersive_brewing.return"),b->batch(true))
                .bounds(leftPos+imageWidth+3,topPos+25,65,18).build());
        seal=addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.seal"),b->action(StationActionC2S.Action.CANCEL_PASSIVE,0))
                .bounds(leftPos+imageWidth+3,topPos+46,65,18).build());
        addRenderableWidget(Button.builder(Component.translatable("item.immersive_brewing.brewers_notebook"),b->minecraft.setScreen(new NotebookScreen()))
                .bounds(leftPos+imageWidth+3,topPos+67,65,18).build());
    }

    private void batch(boolean unused) {
        com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.BatchActionC2S(menu.pos(),
                unused?com.otectus.immersivebrewing.network.packet.BatchActionC2S.Action.RETURN:com.otectus.immersivebrewing.network.packet.BatchActionC2S.Action.COLLECT,
                "",3,com.otectus.immersivebrewing.blockentity.BatchStaging.Kits.RAW,-1,0));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        StationBlockEntity station = station();
        boolean sealed = station != null && station.view().state() == JobState.SEALED;
        resume.visible = sealed;
        plan.visible = !sealed;
        plan.active = station != null && station.view().state() == null;
        seal.visible=station!=null&&station.view().operation()==com.otectus.immersivebrewing.batch.Operation.FINISH&&station.view().state()==JobState.RUNNING;
        if (station != null) comparator.setMessage(Component.literal(station.statusComparator() ? "☰" : "≡"));
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
        int empty=0,filled=0;
        for(int i=0;i<6;i++) {
            var stack=menu.slots.get(i).getItem();
            if(stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE)) empty++;
            else if(!stack.isEmpty())filled++;
        }
        if(empty>0) label(g,Component.translatable("staging.immersive_brewing.water",filled,empty*250),8,111,imageWidth-16,p.muted(),mouseX,mouseY);
    }
}
