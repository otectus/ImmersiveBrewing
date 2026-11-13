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
    private static final ResourceLocation PALE_STONE = new ResourceLocation("minecraft", "block/quartz_block_bottom");

    public static TextureAtlasSprite water() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER);
    }

    public static TextureAtlasSprite cloth() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(CLOTH);
    }
    public static TextureAtlasSprite paleStone() { return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PALE_STONE); }

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

    /** A small textured prism used for moving tool parts.  It intentionally stays in station-local model space. */
    public static void prism(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float z0, float x1, float y1, float z1, int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        TextureAtlasSprite sprite = paleStone();
        // All six faces stay opaque; local coordinates never become atlas UVs.
        faceQuad(pose,vc,sprite,argb,light,0,1,0, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,0,-1,0, x0,y0,z1, x0,y0,z0, x1,y0,z0, x1,y0,z1);
        faceQuad(pose,vc,sprite,argb,light,0,0,-1, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,0,0,1, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1);
        faceQuad(pose,vc,sprite,argb,light,-1,0,0, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,1,0,0, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
    }
    private static void faceQuad(PoseStack pose, VertexConsumer vc, TextureAtlasSprite sprite, int color, int light,
                                 float nx, float ny, float nz, float ax,float ay,float az,float bx,float by,float bz,
                                 float cx,float cy,float cz,float dx,float dy,float dz) {
        vertex(pose,vc,color,light,nx,ny,nz,ax,ay,az,sprite.getU0(),sprite.getV0());
        vertex(pose,vc,color,light,nx,ny,nz,bx,by,bz,sprite.getU1(),sprite.getV0());
        vertex(pose,vc,color,light,nx,ny,nz,cx,cy,cz,sprite.getU1(),sprite.getV1());
        vertex(pose,vc,color,light,nx,ny,nz,dx,dy,dz,sprite.getU0(),sprite.getV1());
    }
    private static void vertex(PoseStack pose, VertexConsumer vc, int color, int light, float nx,float ny,float nz,
                               float x,float y,float z,float u,float v) {
        vc.vertex(pose.last().pose(),x,y,z).color(color>>16&255,color>>8&255,color&255,color>>>24)
                .uv(u,v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.last().normal(),nx,ny,nz).endVertex();
    }

    /** Rotates the pose so north-facing model coordinates apply to {@code facing}. */
    public static void face(PoseStack pose, Direction facing) {
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot() + 180));
        pose.translate(-0.5, 0, -0.5);
    }

    private RenderBits() {}
}
