package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;

/**
 * The Alembic in the world (handoff 9, 19): the most visibly mechanical station. Loaded bottles stand at the base; a
 * charged run shows the warming charge in the boiler, the clamp, the burner and coolant valves at their real settings,
 * the jacket's water level, condensate whose clarity follows the actual fraction, the diverter lever pointing where
 * the stream goes, and the receiver and waste collector filling with what was routed to each.
 */
public class AlembicRenderer implements BlockEntityRenderer<AlembicBlockEntity> {
    private static final int[] CONDENSATE = {0x00000000, 0xB04D83A3, 0xB093C7D8, 0xB0E8F4FF, 0xB0B8DDE8, 0xB0738D9B};

    public AlembicRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AlembicBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        var level = be.getLevel();
        long time = level == null ? 0 : level.getGameTime();
        WorkshopTargets.View view = WorkshopTargets.view(be, WorkshopKind.ALEMBIC);
        CompoundTag a = view.alembic();
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.INPUT + i);
            WorkshopRenderBits.item(pose, buffers, level, s, 1.92 + (i % 3) * 1.92, 4.2, 1.6 + (i / 3) * 1.6, 0.2F, 0, 0, light, i);
        }
        ItemStack charge = view.snapshot().contains("charge") ? ItemStack.of(view.snapshot().getCompound("charge")) : ItemStack.EMPTY;
        boolean purifying = view.operation() == Operation.PURIFY && !charge.isEmpty();
        if (purifying) {
            // The charge in the boiler: its own colour, a little warmer as the still heats.
            double temp = a.getByte("temp") / 100.0;
            int color = RenderBits.darken(PotionUtils.getColor(charge), (float) (0.65 + temp * 0.35));
            double fill = 4.0 + Math.min(5.0, view.snapshot().getInt("chargeCount") * 0.8) * (1 - a.getByte("fraction") / 100.0 * 0.6);
            WorkshopRenderBits.surface(pose, buffers, 2.6, 4.6, 9.4, 11.4, 3.2 + fill, 0xC0000000 | color, light);
        } else if (view.operation() == Operation.PURIFY || view.operation() == Operation.RECLAIM) {
            renderLegacyCharge(be, pose, buffers, light);
        }

        // Clamp lever on the dome: down when closed.
        boolean clamped = a.getBoolean("clamped");
        pose.pushPose();
        pose.translate(3.2 / 16, 11.6 / 16, 6.5 / 16);
        pose.mulPose(Axis.XP.rotationDegrees(clamped ? 0 : -50));
        RenderBits.prism(pose, buffers, -0.6F / 16, -0.4F / 16, -1.6F / 16, 0.6F / 16, 0.4F / 16, 1.6F / 16, clamped ? 0xFF6E6E74 : 0xFF9A9AA0, light);
        pose.popPose();

        // Burner and coolant valves: their handles turn with the setting.
        valve(pose, buffers, 7.7, 2.2, 0.2, a.getByte("burner"), 0xFFD9A441, light);
        valve(pose, buffers, 14.0, 9.8, 5.0, a.getByte("valve"), 0xFF5FA8D8, light);

        // The coolant jacket's water, separate from any potion.
        int capacity = Math.max(1, view.snapshot().getInt("coolantCapacity"));
        double water = Math.min(1, view.snapshot().getInt("coolantMb") / (double) capacity);
        if (water > 0) WorkshopRenderBits.surface(pose, buffers, 12.3, 5.3, 15.2, 8.7, 12.2 + water * 3.0, 0xA03F76E4, light);

        // Diverter: points at the receiver or the waste collector; the stream follows it.
        boolean receiver = a.getBoolean("receiver");
        pose.pushPose();
        pose.translate(StationAnchors.ALEMBIC_DIVERTER.x, StationAnchors.ALEMBIC_DIVERTER.y, StationAnchors.ALEMBIC_DIVERTER.z);
        pose.mulPose(Axis.YP.rotationDegrees(receiver ? -35 : 35));
        RenderBits.prism(pose, buffers, -.025F, -.025F, -.12F, .025F, .025F, .12F, receiver ? 0xFFC7B07C : 0xFF8D6A52, light);
        pose.popPose();
        int condensate = Math.max(0, Math.min(CONDENSATE.length - 1, a.getByte("condensate")));
        boolean flowing = purifying && clamped && a.getByte("burner") > 0 && a.getByte("valve") > 0 && condensate > 0
                && a.getByte("fraction") < 100 && PresentationPolicy.current().animate();
        if (flowing) {
            double tx = receiver ? 12.5 : 9.0, tz = receiver ? 13.0 : 13.6;
            double drip = (time + partialTick) % 10 / 10.0;
            WorkshopRenderBits.stream(pose, buffers, 11.2, 9.6, 9.5, tx, 6.5 + (1 - drip) * 0.5, tz, CONDENSATE[condensate], light);
        }
        // Condensate character at the condenser outlet: a readable tint and width (with text in the working view).
        if (purifying && condensate > 0) {
            float width = .025F + (condensate - 1) * .016F;
            RenderBits.surface(pose, buffers, .76F - width, .69F - .025F, .76F + width, .69F + .025F, .32F, CONDENSATE[condensate], light);
        }
        // Receiver and waste fill with what was routed to each, never with the whole run.
        double toReceiver = a.getByte("toReceiver") / 100.0, toWaste = a.getByte("toWaste") / 100.0;
        if (toReceiver > 0) {
            var bounds = StationAnchors.ALEMBIC_RECEIVER_FILL;
            float y = bounds.minY() + (float) toReceiver * (bounds.maxY() - bounds.minY());
            RenderBits.surface(pose, buffers, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), y, 0xB0E8F4FF, light);
        }
        if (toWaste > 0) WorkshopRenderBits.surface(pose, buffers, 7.0, 12.8, 10.0, 15.2, 3.2 + toWaste * 2.8, 0xA0738D9B, light);

        // Finished output stands by the receiver.
        int shown = 0;
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE && shown < 4; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i);
            if (s.isEmpty()) continue;
            WorkshopRenderBits.item(pose, buffers, level, s, 9.9 + (shown % 2) * 2.2, 1.9, 4.8 + (shown / 2) * 1.9, 0.2F, 0, 0, light, 30 + i);
            shown++;
        }
        pose.popPose();
    }

    private static void valve(PoseStack pose, MultiBufferSource buffers, double x, double y, double z, int setting, int color, int light) {
        pose.pushPose();
        pose.translate(x / 16, y / 16, z / 16);
        pose.mulPose(Axis.ZP.rotationDegrees(-setting * (270F / AlembicProcess.BURNER_STEPS)));
        RenderBits.prism(pose, buffers, -0.9F / 16, -0.9F / 16, -0.4F / 16, 0.9F / 16, 0.9F / 16, 0.4F / 16, 0xFF2F2A26, light);
        RenderBits.prism(pose, buffers, -0.2F / 16, 0.2F / 16, -0.7F / 16, 0.2F / 16, 1.1F / 16, -0.3F / 16, color, light);
        pose.popPose();
    }

    /** A legacy purification or a passive reclamation: the bottles stay in the still while the job owns them. */
    private static void renderLegacyCharge(AlembicBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        if (be.view().state() == null || be.view().material().isEmpty()) return;
        for (int i = 0; i < Math.min(AlembicBlockEntity.INPUT_SIZE, be.view().servings()); i++) {
            WorkshopRenderBits.item(pose, buffers, be.getLevel(), be.view().material(), 1.92 + (i % 3) * 1.92, 4.2, 1.6 + (i / 3) * 1.6, 0.2F, 0, 0,
                    light, 20 + i);
        }
        StationVisual visual = be.visual();
        if (visual.alembic().collectedFraction() > 0) {
            var bounds = StationAnchors.ALEMBIC_RECEIVER_FILL;
            float level = bounds.minY() + visual.alembic().collectedFraction() * (bounds.maxY() - bounds.minY());
            RenderBits.surface(pose, buffers, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), level, 0xB0E8F4FF, light);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(AlembicBlockEntity be) {
        return true;
    }
}
