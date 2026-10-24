package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;

/**
 * The Kettle in the world (spec 4: another player can tell what it is doing without opening it): bottles standing in the
 * rack, the liquid in the vessel tinted by its contents and rising with the batch, foam and churn from the synced
 * visual, and the paddle turning with circulation.
 */
public class KettleRenderer implements BlockEntityRenderer<BrewersKettleBlockEntity> {
    public KettleRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(BrewersKettleBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        var mc = Minecraft.getInstance();
        int bottles = 0;
        ItemStack sample = ItemStack.EMPTY;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.isEmpty()) continue;
            bottles++;
            if (sample.isEmpty()) sample = s;
            pose.pushPose();
            pose.translate(0.12 + i * 0.152, 0.62, 0.92);
            pose.scale(0.24F, 0.24F, 0.24F);
            mc.getItemRenderer().renderStatic(s, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }
        StationVisual visual = be.visual();
        boolean active = visual.active();
        // The vessel holds liquid only while a job owns the bottles (reserved, running, sealed or held); loaded and
        // finished bottles stand in the rack.
        JobState state = be.view().state();
        int servings = state != null ? Math.max(1, be.view().servings()) : 0;
        if (servings > 0) {
            ItemStack shown = !be.view().output().isEmpty() ? be.view().output() : sample;
            int color = shown.isEmpty() ? 0x385DC6 : PotionUtils.getColor(shown);
            if (!active) color = RenderBits.darken(color, 0.7F);
            float level = 0.42F + servings * 0.06F;
            RenderBits.surface(pose, buffers, 0.16F, 0.16F, 0.84F, 0.84F, level, 0xC0000000 | color, light);
            if (active && visual.trouble() > 0.2F) {
                int alpha = (int) Math.min(200, visual.trouble() * 220);
                RenderBits.surface(pose, buffers, 0.18F, 0.18F, 0.82F, 0.82F, level + 0.01F, alpha << 24 | 0xF2F2EA, light);
            }
            if (state == JobState.SEALED || state == JobState.RECOVERY) {
                // A tied cloth over the mouth: linen when sealed for resuming, dull red when held for recovery.
                int cover = state == JobState.RECOVERY ? 0xFF9C4A40 : 0xFFD8C8A4;
                RenderBits.surface(pose, buffers, RenderBits.cloth(), 0.13F, 0.13F, 0.87F, 0.87F, 0.95F, cover, light);
            }
        }
        ItemStack paddle = be.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
        if (!paddle.isEmpty()) {
            pose.pushPose();
            pose.translate(0.5, 0.95, 0.45);
            long time = be.getLevel() == null ? 0 : be.getLevel().getGameTime();
            double motion = ClientConfig.get(ClientConfig.REDUCED_MOTION) ? 0.3 : 1.0;
            float angle = active ? (float) ((time + partialTick) * visual.circulation() * 9 * motion) : 20F;
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            pose.mulPose(Axis.ZP.rotationDegrees(-25));
            pose.scale(0.5F, 0.5F, 0.5F);
            mc.getItemRenderer().renderStatic(paddle, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 7);
            pose.popPose();
        }
        pose.popPose();
    }
}
