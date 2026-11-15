package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Drawing pieces for the close-up apparatus (handoff 4, 7, 8.5), in model pixels of a north-facing station: a readable
 * glass bottle with its real fill level and stopper, translucent glass and liquid boxes, ghosts for missing supplies,
 * the player's own arm gripping a tool or vessel, and small text laid on a page. Shared by the idle, focused and
 * observer views, so there is one apparatus for every viewer.
 */
public final class ApparatusBits {
    public static final float PX = 1F / 16F;
    private static final ResourceLocation GLASS = new ResourceLocation("immersive_brewing", "block/alembic_glass");
    private static final ResourceLocation PLAIN = new ResourceLocation("immersive_brewing", "block/apparatus_neutral");

    public static TextureAtlasSprite glass() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(GLASS);
    }

    public static TextureAtlasSprite plain() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PLAIN);
    }

    /** A translucent box between two model-pixel corners (glass, liquid, foam), every face, sorted with the world's translucency. */
    public static void clearBox(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, double x0, double y0, double z0, double x1, double y1,
                                double z1, int argb, int light) {
        RenderBits.translucentPrism(pose,buffers,sprite,(float)x0*PX,(float)y0*PX,(float)z0*PX,(float)x1*PX,(float)y1*PX,(float)z1*PX,argb,light);
    }

    // ---- bottles ----

    /** How a bottle reads at a glance: its shape by container, its contents and fill, and whether it is stoppered. */
    public static void bottle(PoseStack pose, MultiBufferSource buffers, @Nullable Level level, ItemStack stack, double x, double y, double z, float fill,
                              boolean stopper, float tilt, float yaw, int light) {
        if (stack.isEmpty()) return;
        boolean potion = stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
        boolean glassBottle = stack.is(Items.GLASS_BOTTLE);
        if (!potion && !glassBottle) {
            // Anything that is not a bottle shows as its own item (a modded container keeps its identity).
            WorkshopRenderBits.item(pose, buffers, level, stack, x, y + 1.6, z, 0.26F, yaw, 0, light, 7);
            return;
        }
        boolean splash = stack.is(Items.SPLASH_POTION), lingering = stack.is(Items.LINGERING_POTION);
        int color = potion ? PotionUtils.getColor(stack) : 0x9FC8E8;
        pose.pushPose();
        pose.translate(x * PX, y * PX, z * PX);
        if (yaw != 0) pose.mulPose(Axis.YP.rotationDegrees(yaw));
        if (tilt != 0) {
            // Tipped about the base's far edge (toward the station's back), so the neck swings out over the vessel.
            pose.translate(0, 0, 0.8F * PX);
            pose.mulPose(Axis.XP.rotationDegrees(tilt));
            pose.translate(0, 0, -0.8F * PX);
        }
        TextureAtlasSprite glass = plain();
        float w = lingering ? 1.10F : 1.00F, body = lingering ? 1.85F : 2.1F;
        float shoulder = body + .55F, mouth = 3.65F, neckOffset = splash ? .38F : 0;
        // One chamfered flask outline: joined walls have no internal top/bottom planes.
        int shell = 0x28C4DDE9;
        vessel(pose,buffers,glass,0,.3F,w-.22F,w,0,0,true,false,shell,light);
        vessel(pose,buffers,glass,.3F,body,w,w,0,0,false,false,shell,light);
        vessel(pose,buffers,glass,body,shoulder,w,.34F,0,0,false,false,0x589EBFCF,light);
        vessel(pose,buffers,glass,shoulder,mouth,.34F,.34F,0,neckOffset,false,false,0x909EBFCF,light);
        vessel(pose,buffers,glass,mouth-.12F,mouth+.12F,.46F,.46F,neckOffset,neckOffset,false,false,0xB0BCD5DD,light);
        if (potion && fill > .01F) {
            float fillY=.24F+(body-.3F)*Math.max(0,Math.min(1,fill));
            float lowerY=Math.min(.36F,fillY),lowerRadius=w-.27F+(lowerY-.24F)/.12F*.13F;
            int liquid=0xCE000000|color;
            vessel(pose,buffers,glass,.24F,lowerY,w-.27F,lowerRadius,0,0,true,fillY<=.36F,liquid,light);
            if(fillY>.36F) vessel(pose,buffers,glass,.36F,fillY,w-.14F,w-.14F,0,0,false,true,liquid,light);
        }
        RenderBits.glassHighlight(pose,buffers,glass,-w*.48F*PX,.6F*PX,-w*.28F*PX,1.5F*PX,(-w-.006F)*PX,0xC0DCF1EF,light);
        RenderBits.glassHighlight(pose,buffers,glass,w*.32F*PX,.45F*PX,w*.48F*PX,.8F*PX,(-w-.006F)*PX,0x708AA9B9,light);
        if(stopper) WorkshopRenderBits.box(pose,buffers,neckOffset-.3,mouth-.12,-.3,neckOffset+.3,mouth+.52,.3,0xFFBC8252,light);
        pose.popPose();
    }

    private static void vessel(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,
                               float y0,float y1,float r0,float r1,float x0,float x1,boolean bottom,boolean top,int color,int light) {
        RenderBits.vesselSection(pose,buffers,sprite,y0*PX,y1*PX,r0*PX,r1*PX,x0*PX,x1*PX,bottom,top,color,light);
    }

    /** Same flask proportions in smaller apparatus racks; size changes without squeezing the silhouette. */
    public static void bottleScaled(PoseStack pose,MultiBufferSource buffers,@Nullable Level level,ItemStack stack,
                                    double x,double y,double z,float size,int light) {
        pose.pushPose();pose.translate(x*PX,y*PX,z*PX);pose.scale(size,size,size);
        bottle(pose,buffers,level,stack,0,0,0,stack.is(Items.GLASS_BOTTLE)?0:1,!stack.is(Items.GLASS_BOTTLE),0,0,light);
        pose.popPose();
    }

    // ---- ghosts (missing supplies shown where they belong) ----

    /** A faint, unlit item where a missing supply goes. Purely a hint: it can not be taken or counted. */
    public static void ghost(PoseStack pose, MultiBufferSource buffers, @Nullable Level level, ItemStack stack, double x, double y, double z, float scale,
                             float pitch, long time) {
        if (stack.isEmpty()) return;
        double motion = com.otectus.immersivebrewing.client.presentation.PresentationPolicy.current().decorativeMotion();
        float pulse = 0.35F + 0.1F * (float) (Math.sin(time * 0.12) * motion);
        MultiBufferSource faint = type -> new AlphaConsumer(buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS)), pulse);
        pose.pushPose();
        pose.translate(x * PX, y * PX, z * PX);
        if (pitch != 0) pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, 0xF000F0, OverlayTexture.NO_OVERLAY, pose, faint, level, 3);
        pose.popPose();
    }

    /** Scales the alpha of everything written through it. */
    private record AlphaConsumer(VertexConsumer inner, float alpha) implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { inner.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { inner.color(r, g, b, Math.round(a * alpha)); return this; }
        @Override public VertexConsumer uv(float u, float v) { inner.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { inner.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { inner.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { inner.normal(x, y, z); return this; }
        @Override public void endVertex() { inner.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { inner.defaultColor(r, g, b, Math.round(a * alpha)); }
        @Override public void unsetDefaultColor() { inner.unsetDefaultColor(); }
    }

    // ---- the player's arm at a grip (handoff 7.4) ----

    /**
     * Draws a vanilla-sized translucent player arm reaching a grip point from the player's side: their own skin and model (slim or
     * standard) and hand (main arm, or the left in the left-handed presentation). Nothing is drawn for an unknown or
     * invisible holder, or when the hand style is tools-only.
     *
     * @param grip   the grip point, model pixels
     * @param toward the direction the forearm extends from the hand (toward the player's body), model space
     */
    private static final java.util.Set<UUID> DRAWN_HANDS = new java.util.HashSet<>();
    public static void beginFrame() { DRAWN_HANDS.clear(); }
    public static void clearHands() { DRAWN_HANDS.clear(); WorkshopArmRenderer.clear(); }

    public static void arm(PoseStack pose, MultiBufferSource buffers, @Nullable UUID holder, Vec3 grip, Vec3 toward, int light) {
        if (holder == null || ClientConfig.get(ClientConfig.HANDS) == ClientConfig.HandStyle.TOOLS_ONLY) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getPlayerByUUID(holder) instanceof AbstractClientPlayer player)) return;
        if (player.isInvisible() || !DRAWN_HANDS.add(holder)) return;
        boolean right = player.getMainArm() == HumanoidArm.RIGHT;
        if (ClientConfig.get(ClientConfig.MIRROR_TOOLS)) right = !right;
        // Every task and view uses the same arm. Only its rigid pose follows the grip; neither the camera's
        // viewport nor the distance to an off-screen point may resize the player's anatomy or stretch their skin.
        Vec3 direction = new Vec3(right ? -Math.abs(toward.x) : Math.abs(toward.x), toward.y, toward.z);
        WorkshopArmRenderer.queue(pose, player.getSkinTextureLocation(), player.getModelName().equals("slim"), right,
                player.isModelPartShown(right ? net.minecraft.world.entity.player.PlayerModelPart.RIGHT_SLEEVE
                        : net.minecraft.world.entity.player.PlayerModelPart.LEFT_SLEEVE),
                grip, direction, ClientConfig.get(ClientConfig.ARM_OPACITY).floatValue(), light);
    }

    // ---- page text ----

    /**
     * Text laid flat on the current pose's XZ plane facing up (+Y), starting at a model-pixel point; {@code size} is the
     * line height in model pixels. Used for the recipe page and rack labels.
     */
    public static void text(PoseStack pose, MultiBufferSource buffers, Component text, double x, double y, double z, float size, int argb,
                            boolean centred, int light) {
        var font = Minecraft.getInstance().font;
        float scale = size * PX / 9F;
        pose.pushPose();
        pose.translate(x * PX, y * PX, z * PX);
        // A sign's upright text (readable from +Z), turned to face a viewer on the station's near side, then laid back
        // flat: lines run toward the viewer, letters to the viewer's right.
        pose.mulPose(Axis.XP.rotationDegrees(90));
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(scale, -scale, scale);
        float dx = centred ? -font.width(text) / 2F : 0;
        font.drawInBatch(text, dx, 0, argb, false, pose.last().pose(), buffers, net.minecraft.client.gui.Font.DisplayMode.POLYGON_OFFSET, 0, light);
        pose.popPose();
    }

    private ApparatusBits() {}
}
