package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Drawing helpers for the hands-on apparatus, in model pixels of a north-facing station (the pose is already turned by
 * {@link RenderBits#face}). Shared by the idle, focused and spectator views: there is one apparatus, drawn from the
 * synced state, with only the local player's own held tool taken from their hand.
 */
public final class WorkshopRenderBits {
    public static final float PX = 1F / 16F;

    /** An item standing at a model-pixel point. */
    public static void item(PoseStack pose, MultiBufferSource buffers, @Nullable Level level, ItemStack stack, double x, double y, double z,
                            float scale, float yaw, float pitch, int light, int seed) {
        if (stack.isEmpty()) return;
        pose.pushPose();
        pose.translate(x * PX, y * PX, z * PX);
        if (yaw != 0) pose.mulPose(Axis.YP.rotationDegrees(yaw));
        if (pitch != 0) pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, seed);
        pose.popPose();
    }

    /** An opaque box between two model-pixel corners. */
    public static void box(PoseStack pose, MultiBufferSource buffers, double x0, double y0, double z0, double x1, double y1, double z1, int argb, int light) {
        RenderBits.prism(pose, buffers, (float) x0 * PX, (float) y0 * PX, (float) z0 * PX, (float) x1 * PX, (float) y1 * PX, (float) z1 * PX, argb, light);
    }

    public static void material(PoseStack pose, MultiBufferSource buffers, RenderBits.MaterialKind material,
                                double x0,double y0,double z0,double x1,double y1,double z1,int light) {
        RenderBits.prism(pose,buffers,material,(float)x0*PX,(float)y0*PX,(float)z0*PX,(float)x1*PX,(float)y1*PX,(float)z1*PX,0xFFFFFFFF,light);
    }

    public static void decal(PoseStack pose, MultiBufferSource buffers, String kind,double x,double y,double z,double radius,int argb,int light) {
        RenderBits.decal(pose,buffers,kind,(float)(x-radius)*PX,(float)(z-radius)*PX,(float)(x+radius)*PX,(float)(z+radius)*PX,(float)y*PX,argb,light);
    }

    /** A box whose x extent follows the left-handed presentation (mirrored and kept ordered). */
    public static void boxM(PoseStack pose, MultiBufferSource buffers, double x0, double y0, double z0, double x1, double y1, double z1, int argb, int light) {
        double a = mirror(x0), b = mirror(x1);
        box(pose, buffers, Math.min(a, b), y0, z0, Math.max(a, b), y1, z1, argb, light);
    }

    /** A horizontal translucent surface at a model-pixel height. */
    public static void surface(PoseStack pose, MultiBufferSource buffers, double x0, double z0, double x1, double z1, double y, int argb, int light) {
        RenderBits.surface(pose, buffers, (float) x0 * PX, (float) z0 * PX, (float) x1 * PX, (float) z1 * PX, (float) y * PX, argb, light);
    }

    /** A falling stream between two model-pixel points: a thin vertical ribbon facing both ways. */
    public static void stream(PoseStack pose, MultiBufferSource buffers, double fx, double fy, double fz, double tx, double ty, double tz, int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderBits.translucent());
        var sprite = RenderBits.water();
        Matrix4f m = pose.last().pose();
        float a = (argb >>> 24) / 255F, r = (argb >> 16 & 0xFF) / 255F, g = (argb >> 8 & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
        float w = 0.18F * PX;
        for (int axis=0;axis<2;axis++) {
            float ox=axis==0?w:0,oz=axis==1?w:0;
            float[][] quad = {
                    {(float)fx*PX-ox,(float)fy*PX,(float)fz*PX-oz},{(float)tx*PX-ox,(float)ty*PX,(float)tz*PX-oz},
                    {(float)tx*PX+ox,(float)ty*PX,(float)tz*PX+oz},{(float)fx*PX+ox,(float)fy*PX,(float)fz*PX+oz}};
            for(int pass=0;pass<2;pass++) for(int n=0;n<4;n++) {
                int i=pass==0?n:3-n;float[] c=quad[i];
                vc.vertex(m,c[0],c[1],c[2]).color(r,g,b,a).uv(sprite.getU(i<2?0:2),sprite.getV(i%3==0?0:12))
                        .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.last().normal(),axis==1?1:0,0,axis==0?1:0).endVertex();
            }
        }
    }

    /**
     * The pose of a tool as everyone should see it: the local player's own hand when they hold it, else the holder's
     * synced pose, else null (resting). Returns {u, v, contact, deep} in normalised work-plane coordinates.
     */
    @Nullable
    public static double[] toolPose(BlockPos pos, CompoundTag snapshot, int part) {
        var player = Minecraft.getInstance().player;
        if (pos.equals(ClientWorkshop.focus()) && ClientWorkshop.tool() == part) {
            return new double[] {ClientWorkshop.shownU(), ClientWorkshop.shownV(), ClientWorkshop.contact() ? 1 : 0, ClientWorkshop.deep() ? 1 : 0};
        }
        WorkshopTargets.View view = new WorkshopTargets.View(null, null, snapshot);
        CompoundTag held = view.pose(part);
        if (held == null) return null;
        UUID holder = held.hasUUID("id") ? held.getUUID("id") : null;
        // Our own lease without our local hand (we just put it down): draw it resting.
        if (player != null && holder != null && holder.equals(player.getUUID()) && pos.equals(ClientWorkshop.focus())) return null;
        if (held.getBoolean("carry")) return null; // a carried bottle or cup, not a tool
        return new double[] {held.getFloat("x"), held.getFloat("z"), held.getBoolean("contact") ? 1 : 0, held.getBoolean("deep") ? 1 : 0};
    }

    /** Mirrors an x coordinate for the left-handed presentation (the parts acted on do not move). */
    public static double mirror(double x) {
        return ClientConfig.get(ClientConfig.MIRROR_TOOLS) ? 16 - x : x;
    }

    /** Linear colour mix of two RGB values. */
    public static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) ((a >> 16 & 0xFF) * (1 - t) + (b >> 16 & 0xFF) * t);
        int g = (int) ((a >> 8 & 0xFF) * (1 - t) + (b >> 8 & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    private WorkshopRenderBits() {}
}
