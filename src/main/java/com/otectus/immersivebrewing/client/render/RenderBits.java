package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;

/** Small rendering helpers shared by the station renderers: tinted horizontal surfaces and facing rotation. */
public final class RenderBits {
    private static final ResourceLocation WATER = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation CLOTH = new ResourceLocation("minecraft", "block/white_wool");

    public static TextureAtlasSprite water() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER);
    }

    public static TextureAtlasSprite cloth() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(CLOTH);
    }

    /** RGB scaled toward black by {@code factor} (0..1 kept). */
    public static int darken(int rgb, float factor) {
        int r = (int) ((rgb >> 16 & 0xFF) * factor);
        int g = (int) ((rgb >> 8 & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return r << 16 | g << 8 | b;
    }

    /** A horizontal liquid quad from (x0, z0) to (x1, z1) at height y, in block units, tinted ARGB. */
    public static void surface(PoseStack pose, MultiBufferSource buffers, float x0, float z0, float x1, float z1, float y, int argb, int light) {
        surface(pose, buffers, water(), x0, z0, x1, z1, y, argb, light);
    }

    /** The same quad with any block-atlas sprite. */
    public static void surface(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, float x0, float z0, float x1, float z1, float y,
                               int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
        Matrix4f m = pose.last().pose();
        float a = (argb >>> 24) / 255F;
        float r = (argb >> 16 & 0xFF) / 255F;
        float g = (argb >> 8 & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
        float u0 = sprite.getU(x0 * 16), u1 = sprite.getU(x1 * 16), v0 = sprite.getV(z0 * 16), v1 = sprite.getV(z1 * 16);
        vc.vertex(m, x0, y, z0).color(r, g, b, a).uv(u0, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vc.vertex(m, x0, y, z1).color(r, g, b, a).uv(u0, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vc.vertex(m, x1, y, z1).color(r, g, b, a).uv(u1, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vc.vertex(m, x1, y, z0).color(r, g, b, a).uv(u1, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
    }

    /** Rotates the pose so north-facing model coordinates apply to {@code facing}. */
    public static void face(PoseStack pose, Direction facing) {
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot() + 180));
        pose.translate(-0.5, 0, -0.5);
    }

    private RenderBits() {}
}
