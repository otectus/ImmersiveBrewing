package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** The Alembic in the world: loaded bottles at its base and condensate rising in the glass receiver while it works. */
public class AlembicRenderer implements BlockEntityRenderer<AlembicBlockEntity> {
    public AlembicRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AlembicBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        var mc = Minecraft.getInstance();
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.INPUT + i);
            if (s.isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.12 + (i % 3) * 0.12, 0.26, 0.10 + (i / 3) * 0.10);
            pose.scale(0.2F, 0.2F, 0.2F);
            mc.getItemRenderer().renderStatic(s, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), i);
            pose.popPose();
        }
        // A purification's bottles stay in the still while the job owns them (running, sealed or held).
        if (be.view().operation() == Operation.PURIFY && be.view().state() != null && !be.view().output().isEmpty()) {
            for (int i = 0; i < Math.min(AlembicBlockEntity.INPUT_SIZE, be.view().servings()); i++) {
                pose.pushPose();
                pose.translate(0.12 + (i % 3) * 0.12, 0.26, 0.10 + (i / 3) * 0.10);
                pose.scale(0.2F, 0.2F, 0.2F);
                mc.getItemRenderer().renderStatic(be.view().output(), ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers,
                        be.getLevel(), 20 + i);
                pose.popPose();
            }
        }
        StationVisual visual = be.visual();
        if (visual.active()) {
            float level = 0.20F + visual.progress() * 0.32F;
            RenderBits.surface(pose, buffers, 0.66F, 0.58F, 0.90F, 0.84F, level, 0xB0E8F4FF, light);
        }
        // Finished output stands by the receiver.
        int shown = 0;
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE && shown < 4; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i);
            if (s.isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.62 + (shown % 2) * 0.14, 0.12, 0.30 + (shown / 2) * 0.12);
            pose.scale(0.2F, 0.2F, 0.2F);
            mc.getItemRenderer().renderStatic(s, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 30 + i);
            pose.popPose();
            shown++;
        }
        pose.popPose();
    }
}
