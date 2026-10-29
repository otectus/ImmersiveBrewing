package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.block.ApothecaryRackBlock;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.Grade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Actual item models provide bottle shape and tint; grade symbols and short literal labels remain distinguishable without colour. */
public class ApothecaryRackRenderer implements BlockEntityRenderer<ApothecaryRackBlockEntity> {
    public ApothecaryRackRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ApothecaryRackBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(ApothecaryRackBlock.FACING));
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) {
            ItemStack stack = be.inventory().getStackInSlot(i);
            if (stack.isEmpty()) continue;
            int column = i % 3;
            int row = i / 3;
            float x = 0.18F + column * 0.32F;
            float y = row == 0 ? 0.60F : 0.16F;
            pose.pushPose();
            pose.translate(x, y, 0.31F);
            pose.scale(0.36F, 0.36F, 0.36F);
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();

            Grade grade = BrewingMetadata.read(stack).map(BrewingMetadata::grade).orElse(Grade.STANDARD);
            String label = be.label(i);
            String marker = grade.symbol() + (label.isEmpty() ? "" : " " + shortLabel(label));
            pose.pushPose();
            pose.translate(x + 0.09F, y - 0.02F, 0.115F);
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.scale(0.006F, -0.006F, 0.006F);
            int width = mc.font.width(marker);
            mc.font.drawInBatch(marker, -width / 2F, 0, 0xFF000000 | grade.color(), false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, light);
            pose.popPose();
        }
        pose.popPose();
    }

    private static String shortLabel(String value) {
        int end = value.offsetByCodePoints(0, Math.min(6, value.codePointCount(0, value.length())));
        return value.substring(0, end);
    }
}
