package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.item.KitData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** The Mortar in the world: the reagent fragments in the bowl, prepared kits on the shelf side, the pestle working. */
public class MortarRenderer implements BlockEntityRenderer<ApothecaryMortarBlockEntity> {
    public MortarRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ApothecaryMortarBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        var mc = Minecraft.getInstance();
        ItemStack input = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT);
        StationVisual visual = be.visual();
        // While a job owns the reagent (running, sealed or held) it is still in the bowl.
        boolean held = be.view().state() != null;
        ItemStack shown = !input.isEmpty() ? input : held ? KitData.read(be.view().output()).map(KitData::source).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        if (!shown.isEmpty()) {
            int pieces = Math.min(5, Math.max(1, shown.getCount()));
            for (int i = 0; i < pieces; i++) {
                pose.pushPose();
                pose.translate(0.38 + (i % 3) * 0.12, 0.40, 0.40 + (i / 3) * 0.14);
                pose.mulPose(Axis.XP.rotationDegrees(90));
                pose.mulPose(Axis.ZP.rotationDegrees(i * 47));
                float scale = visual.active() ? 0.22F - visual.trouble() * 0.12F : 0.22F;
                pose.scale(scale, scale, scale);
                mc.getItemRenderer().renderStatic(shown, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), i);
                pose.popPose();
            }
        }
        // Prepared kits wait on the shelf beside the bowl.
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            ItemStack kit = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
            if (kit.isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.12 + (i % 2) * 0.13, 0.08, 0.86 - (i / 2) * 0.13);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.scale(0.18F, 0.18F, 0.18F);
            mc.getItemRenderer().renderStatic(kit, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 10 + i);
            pose.popPose();
        }
        pose.popPose();
    }
}
