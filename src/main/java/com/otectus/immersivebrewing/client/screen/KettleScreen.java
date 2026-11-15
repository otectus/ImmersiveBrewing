package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
        plan = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.plans_short"),
                b -> action(StationActionC2S.Action.REQUEST_PLANS, 0))
                .tooltip(Tooltip.create(Component.translatable("screen.immersive_brewing.choose_plan")))
                .bounds(leftPos + 8, topPos + 94, 76, 18).build());
        resume = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.resume"),
                b -> action(StationActionC2S.Action.RESUME, 0)).bounds(leftPos + 8, topPos + 94, 76, 18).build());
        comparator = addRenderableWidget(Button.builder(Component.literal("≡"), b -> action(StationActionC2S.Action.TOGGLE_COMPARATOR, 0))
                .bounds(leftPos + 88, topPos + 94, 18, 18).build());
        comparator.setTooltip(Tooltip.create(Component.translatable("screen.immersive_brewing.comparator_tip")));
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.collect_short"),b->batch(false))
                .tooltip(Tooltip.create(Component.translatable("staging.immersive_brewing.collect")))
                .bounds(leftPos + 180, topPos + 32, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.return_short"),b->batch(true))
                .tooltip(Tooltip.create(Component.translatable("staging.immersive_brewing.return")))
                .bounds(leftPos + 180, topPos + 58, 78, 20).build());
        seal=addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.seal"),b->action(StationActionC2S.Action.CANCEL_PASSIVE,0))
                .bounds(leftPos + 180, topPos + 110, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.notebook_short"),b->minecraft.setScreen(new NotebookScreen()))
                .bounds(leftPos + 180, topPos + 84, 78, 20).build());
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
        if (station != null) {
            comparator.setMessage(Component.literal(station.statusComparator() ? "☰" : "≡"));
            comparator.setTooltip(Tooltip.create(Component.translatable(station.statusComparator()
                    ? "screen.immersive_brewing.comparator_job" : "screen.immersive_brewing.comparator_output")));
        }
    }

    @Override
    protected void renderExtras(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(leftPos + 174, topPos + 24, leftPos + 175, topPos + 112, Ui.palette().panelDark());
        for (int i = 0; i < com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.SLOTS; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) continue;
            var slot = menu.slots.get(i);
            String role = KettleMenu.slotRole(i);
            g.blit(new ResourceLocation("immersive_brewing", "textures/gui/slot_roles/" + role + ".png"),
                    leftPos + slot.x, topPos + slot.y, 0, 0, 16, 16, 16, 16);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        Ui.Palette p = Ui.palette();
        label(g, Component.translatable("screen.immersive_brewing.slot.bottles"), 35, 20, 108, p.muted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.slot.reagents"), 35, 54, 108, p.muted(), mouseX, mouseY);
        label(g, Component.translatable("screen.immersive_brewing.slot.returns"), 116, 84, 52, p.muted(), mouseX, mouseY);
        StationBlockEntity station = station();
        if (station == null) return;
        Component fuel = Component.translatable("screen.immersive_brewing.fuel_charges", station.fuelCharges());
        label(g, station.view().status(), 8, 116, seal.visible ? 160 : imageWidth - 16, p.text(), mouseX, mouseY);
        label(g, fuel, 8, 128, 160, p.muted(), mouseX, mouseY);
        int empty=0,filled=0;
        for(int i=0;i<6;i++) {
            var stack=menu.slots.get(i).getItem();
            if(stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE)) empty++;
            else if(!stack.isEmpty())filled++;
        }
        if(empty>0) Ui.wrappedMax(g,Component.translatable("staging.immersive_brewing.water",filled,empty*250),180,142,78,4,p.muted());
    }
}
