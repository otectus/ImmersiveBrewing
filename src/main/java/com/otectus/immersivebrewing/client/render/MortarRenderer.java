package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.client.presentation.PreparationMaterialVisuals;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import static com.otectus.immersivebrewing.client.render.WorkshopRenderBits.mirror;

/**
 * The Mortar in the world (handoff 6, 19): the reagent in the bowl as five material zones whose coarse fragments shrink
 * into an even layer as they are worked, fines as a pale dust, the family's correctable mistake (paste on the rim,
 * froth) at the rim, and the pestle and scraper where their holders hold them. One real reagent inset keeps the
 * identity readable; the bowl never becomes a swarm of item icons.
 */
public class MortarRenderer implements BlockEntityRenderer<ApothecaryMortarBlockEntity> {
    private static final int[] FAMILY_COLOR = {0xFFE0E3E8, 0xFFC9A66E, 0xFFC98272, 0xFFE2D8AD, 0xFF8CB9C9};

    public MortarRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ApothecaryMortarBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        var level = be.getLevel();
        WorkshopTargets.View view = WorkshopTargets.view(be, WorkshopKind.MORTAR);
        CompoundTag m = view.mortar();
        ItemStack input = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT);
        ItemStack reagent = view.snapshot().contains("reagent") ? ItemStack.of(view.snapshot().getCompound("reagent")) : input;
        if (m.contains("zones")) {
            byte[] zones = m.getByteArray("zones");
            byte[] fines = m.getByteArray("fines");
            int family = Math.max(0, Math.min(FAMILY_COLOR.length - 1, m.getByte("family")));
            int color = FAMILY_COLOR[family];
            int units = Math.max(1, m.getByte("units"));
            for (int z = 0; z < MortarProcess.ZONES; z++) {
                double processed = z < zones.length ? zones[z] / 100.0 : 0;
                double dust = z < fines.length ? fines[z] / 100.0 : 0;
                double[] c = zoneCentre(z);
                // The worked layer: grows and evens out as the zone is processed.
                double layer = 0.25 + processed * 0.55 * Math.min(1.5, 0.7 + units * 0.2);
                WorkshopRenderBits.box(pose, buffers, c[0] - 1.3, WorkshopParts.MORTAR_FLOOR, c[1] - 1.3, c[0] + 1.3,
                        WorkshopParts.MORTAR_FLOOR + layer, c[1] + 1.3, color, light);
                // Coarse fragments remain where the zone was not worked.
                int pieces = (int) Math.round((1 - processed) * 3);
                for (int i = 0; i < pieces; i++) {
                    double a = i * 2.1 + z;
                    double fx = c[0] + Math.cos(a) * 0.7, fz = c[1] + Math.sin(a) * 0.7, s = 0.35 + (1 - processed) * 0.45;
                    WorkshopRenderBits.box(pose, buffers, fx - s, WorkshopParts.MORTAR_FLOOR + layer, fz - s * 0.7,
                            fx + s, WorkshopParts.MORTAR_FLOOR + layer + s * 0.6, fz + s * 0.7, color, light);
                }
                if (dust > 0.05) {
                    WorkshopRenderBits.surface(pose, buffers, c[0] - 1.2, c[1] - 1.2, c[0] + 1.2, c[1] + 1.2,
                            WorkshopParts.MORTAR_FLOOR + layer + 0.05, (int) (80 + dust * 150) << 24 | 0xF5F2EA, light);
                }
            }
            double clump = m.getByte("clump") / 100.0;
            if (clump > 0.08) {
                // Paste creeping up the rim (soft) or froth (liquid): a ring the scraper takes back in.
                int ring = family == 4 ? 0xF0F4F8 : color & 0xFFFFFF;
                int alpha = (int) (90 + clump * 150) << 24;
                WorkshopRenderBits.box(pose, buffers, 4.1, 7.6, 4.1, 11.9, 7.6 + clump * 1.2, 4.6, alpha | ring, light);
                WorkshopRenderBits.box(pose, buffers, 4.1, 7.6, 11.4, 11.9, 7.6 + clump * 1.2, 11.9, alpha | ring, light);
            }
            WorkshopRenderBits.item(pose, buffers, level, reagent, 2.6, 7.0, 2.9, 0.11F, 0, 0, light, 1);
        } else if (!input.isEmpty()) {
            // Reagents set out in the bowl, not yet worked: whole pieces, one per unit placed.
            var fragments = PreparationMaterialVisuals.fragments(0, 0);
            for (int i = 0; i < Math.min(ApothecaryMortarBlockEntity.BOWL_UNITS, input.getCount()); i++) {
                double a = i * 1.57 + 0.4;
                WorkshopRenderBits.item(pose, buffers, level, input, 8 + Math.cos(a) * 1.8, WorkshopParts.MORTAR_FLOOR + 0.3, 8 + Math.sin(a) * 1.8,
                        0.18F, (float) Math.toDegrees(a), 90, light, 2 + i);
            }
            if (fragments.length == 0) WorkshopRenderBits.item(pose, buffers, level, input, 8, 6, 8, 0.2F, 0, 90, light, 9);
        } else if (view.state() != null && !reagent.isEmpty()) {
            WorkshopRenderBits.item(pose, buffers, level, reagent, 8, WorkshopParts.MORTAR_FLOOR + 0.3, 8, 0.2F, 0, 90, light, 9);
        }

        // Pestle and scraper: in the hand over the bowl (lowered on contact), else on their rests.
        double[] pestle = WorkshopRenderBits.toolPose(be.getBlockPos(), view.snapshot(), WorkshopParts.M_PESTLE);
        if (pestle != null) {
            double x = WorkshopParts.MORTAR_CX + pestle[0] * WorkshopParts.MORTAR_R, z = WorkshopParts.MORTAR_CZ + pestle[1] * WorkshopParts.MORTAR_R;
            long pressAge = com.otectus.immersivebrewing.client.sound.StationSoundManager.acknowledgementAge(be,
                    com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.PRESS);
            double press = PresentationPolicy.current().animate() && pressAge >= 0 && pressAge < 200 ? (200 - pressAge) / 200.0 : 0;
            double y = (pestle[2] > 0 ? WorkshopParts.MORTAR_FLOOR + 0.6 : WorkshopParts.MORTAR_FLOOR + 3.0) - press * 0.6;
            pose.pushPose();
            pose.translate(x / 16, y / 16, z / 16);
            pose.mulPose(Axis.ZP.rotationDegrees((float) (pestle[0] * 10)));
            RenderBits.prism(pose, buffers, -.065F, 0, -.065F, .065F, .48F, .065F, 0xFFD8D0BE, light);
            pose.popPose();
        } else {
            pose.pushPose();
            pose.translate(mirror(12.6) / 16, 8.0 / 16, 12.6 / 16);
            pose.mulPose(Axis.ZP.rotationDegrees(14));
            RenderBits.prism(pose, buffers, -.06F, 0, -.06F, .06F, .5F, .06F, 0xFFD8D0BE, light);
            pose.popPose();
        }
        double[] scraper = WorkshopRenderBits.toolPose(be.getBlockPos(), view.snapshot(), WorkshopParts.M_SCRAPER);
        double sx = scraper == null ? 2.5 : WorkshopParts.MORTAR_CX + scraper[0] * WorkshopParts.MORTAR_R;
        double sz = scraper == null ? 2.5 : WorkshopParts.MORTAR_CZ + scraper[1] * WorkshopParts.MORTAR_R;
        double sy = scraper == null ? 9.0 : WorkshopParts.MORTAR_FLOOR + 1.0;
        if (scraper == null) WorkshopRenderBits.boxM(pose, buffers, sx - 1.2, sy, sz - 0.15, sx + 1.2, sy + 1.6, sz + 0.15, 0xFF8A6A48, light);
        else WorkshopRenderBits.box(pose, buffers, sx - 1.2, sy, sz - 0.15, sx + 1.2, sy + 1.6, sz + 0.15, 0xFF8A6A48, light);

        // The dose cup and the kits waiting on the shelf.
        WorkshopRenderBits.box(pose, buffers, 13.2, 0, -1.2, 15.8, 2.0, 1.4, 0xFFB9A27A, light);
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            ItemStack kit = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
            if (kit.isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.12 + (i % 2) * 0.13, 0.08, 0.86 - (i / 2) * 0.13);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.scale(0.18F, 0.18F, 0.18F);
            Minecraft.getInstance().getItemRenderer().renderStatic(kit, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, 10 + i);
            pose.popPose();
        }
        pose.popPose();
    }

    /** A zone's centre in model pixels (the same zones the server works). */
    static double[] zoneCentre(int zone) {
        if (zone == 0) return new double[] {WorkshopParts.MORTAR_CX, WorkshopParts.MORTAR_CZ};
        double angle = -Math.PI + (zone - 1 + 0.5) * Math.PI / 2;
        return new double[] {WorkshopParts.MORTAR_CX + Math.cos(angle) * 2.5, WorkshopParts.MORTAR_CZ + Math.sin(angle) * 2.5};
    }

    @Override
    public boolean shouldRenderOffScreen(ApothecaryMortarBlockEntity be) {
        return true;
    }
}
