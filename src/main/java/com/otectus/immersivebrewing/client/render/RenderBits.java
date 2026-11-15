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
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;

/** Small rendering helpers shared by the station renderers: tinted horizontal surfaces and facing rotation. */
public final class RenderBits {
    private static final ResourceLocation WATER = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation CLOTH = new ResourceLocation("minecraft", "block/white_wool");
    private static final java.util.Map<String,ResourceLocation> EFFECT_TEXTURES=new java.util.HashMap<>();
    private static final float[] VESSEL_X = {-.55F,.55F,1,1,.55F,-.55F,-1,-1};
    private static final float[] VESSEL_Z = {-1,-1,-.55F,.55F,1,1,.55F,-.55F};
    public enum MaterialKind {
        NEUTRAL("apparatus_neutral"), WOOD("apparatus_wood"), COPPER("alembic_copper"), IRON("kettle_iron"),
        STEEL("apparatus_steel"), STONE("mortar_stone"), PESTLE("mortar_pestle"), CERAMIC("apparatus_ceramic"), PAPER("apparatus_paper");
        private final ResourceLocation texture;
        MaterialKind(String name) { texture = new ResourceLocation("immersive_brewing", "block/" + name); }
        public TextureAtlasSprite sprite() { return atlas(texture); }
    }

    private static TextureAtlasSprite atlas(ResourceLocation name) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(name);
    }

    public static TextureAtlasSprite effect(String name) {
        return atlas(EFFECT_TEXTURES.computeIfAbsent(name,key->new ResourceLocation("immersive_brewing","block/fx_"+key)));
    }

    /** Dynamic block-atlas geometry belongs to the entity target: Fabulous clears the chunk target after BERs. */
    public static RenderType translucent() {
        return RenderType.translucentMovingBlock();
    }

    public static TextureAtlasSprite water() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER);
    }

    public static TextureAtlasSprite cloth() {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(CLOTH);
    }
    public static TextureAtlasSprite paleStone() { return MaterialKind.PESTLE.sprite(); }

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
        VertexConsumer vc = buffers.getBuffer(translucent());
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

    /** Model-scale UVs and vanilla face shading, shared by every moving material. Coordinates are block units. */
    public static void prism(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float z0, float x1, float y1, float z1, int argb, int light) {
        prism(pose, buffers, MaterialKind.NEUTRAL, x0, y0, z0, x1, y1, z1, argb, light);
    }

    public static void prism(PoseStack pose, MultiBufferSource buffers, MaterialKind material, float x0, float y0, float z0, float x1, float y1, float z1,
                             int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        TextureAtlasSprite sprite = material.sprite();
        drawPrism(pose,vc,sprite,x0,y0,z0,x1,y1,z1,argb,light);
    }

    public static void translucentPrism(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,
                                        float x0,float y0,float z0,float x1,float y1,float z1,int argb,int light) {
        drawPrism(pose,buffers.getBuffer(translucent()),sprite,x0,y0,z0,x1,y1,z1,argb,light);
    }

    /** A continuous eight-sided vessel wall. Only exposed ends get caps, so joined sections have no internal glass planes. */
    public static void vesselSection(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,
                                      float y0,float y1,float radius0,float radius1,float offset0,float offset1,
                                      boolean bottom,boolean top,int argb,int light) {
        if(y1<=y0) return;
        VertexConsumer vc=buffers.getBuffer(translucent());
        for(int i=0;i<8;i++) {
            int j=(i+1)%8;
            float ax=offset0+VESSEL_X[i]*radius0,az=VESSEL_Z[i]*radius0;
            float bx=offset1+VESSEL_X[i]*radius1,bz=VESSEL_Z[i]*radius1;
            float cx=offset1+VESSEL_X[j]*radius1,cz=VESSEL_Z[j]*radius1;
            float dx=offset0+VESSEL_X[j]*radius0,dz=VESSEL_Z[j]*radius0;
            float nx=(y1-y0)*(cz-bz),ny=(bz-az)*(cx-ax)-(bx-ax)*(cz-az),nz=(y1-y0)*(bx-cx);
            float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            faceQuad(pose,vc,sprite,argb,light,nx/length,ny/length,nz/length,ax,y0,az,bx,y1,bz,cx,y1,cz,dx,y0,dz);
            // The repeated centre closes one triangle of each cap; no coplanar square overlaps its chamfered corners.
            if(bottom) faceQuad(pose,vc,sprite,argb,light,0,-1,0,offset0,y0,0,ax,y0,az,dx,y0,dz,offset0,y0,0);
            if(top) faceQuad(pose,vc,sprite,argb,light,0,1,0,offset1,y1,0,cx,y1,cz,bx,y1,bz,offset1,y1,0);
        }
    }

    /** One glass reflection, flush with the front face rather than a thin box with six overlapping faces. */
    public static void glassHighlight(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,
                                       float x0,float y0,float x1,float y1,float z,int argb,int light) {
        faceQuad(pose,buffers.getBuffer(translucent()),sprite,argb,light,0,0,-1,
                x1,y0,z,x0,y0,z,x0,y1,z,x1,y1,z);
    }

    private static void drawPrism(PoseStack pose,VertexConsumer vc,TextureAtlasSprite sprite,
                                   float x0,float y0,float z0,float x1,float y1,float z1,int argb,int light) {
        faceQuad(pose,vc,sprite,argb,light,0,1,0, x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,0,-1,0, x0,y0,z1, x0,y0,z0, x1,y0,z0, x1,y0,z1);
        faceQuad(pose,vc,sprite,argb,light,0,0,-1, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,0,0,1, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1);
        faceQuad(pose,vc,sprite,argb,light,-1,0,0, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
        faceQuad(pose,vc,sprite,argb,light,1,0,0, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
    }

    private static float origin(float coordinate, float span) {
        float start = coordinate - (float) Math.floor(coordinate / 16F) * 16F;
        return Math.min(start, Math.max(0, 16 - span));
    }

    private static void faceQuad(PoseStack pose, VertexConsumer vc, TextureAtlasSprite sprite, int color, int light,
                                 float nx, float ny, float nz, float ax,float ay,float az,float bx,float by,float bz,
                                 float cx,float cy,float cz,float dx,float dy,float dz) {
        float minX=Math.min(Math.min(ax,bx),Math.min(cx,dx)),maxX=Math.max(Math.max(ax,bx),Math.max(cx,dx));
        float minY=Math.min(Math.min(ay,by),Math.min(cy,dy)),maxY=Math.max(Math.max(ay,by),Math.max(cy,dy));
        float minZ=Math.min(Math.min(az,bz),Math.min(cz,dz)),maxZ=Math.max(Math.max(az,bz),Math.max(cz,dz));
        float minU=(nx!=0?minZ:minX)*16,maxU=(nx!=0?maxZ:maxX)*16;
        float minV=(ny!=0?minZ:-maxY)*16,maxV=(ny!=0?maxZ:-minY)*16;
        float spanU=Math.min(16,maxU-minU),spanV=Math.min(16,maxV-minV);
        float startU=origin(minU,spanU),startV=origin(minV,spanV);
        float scaleU=spanU/Math.max(.00001F,maxU-minU),scaleV=spanV/Math.max(.00001F,maxV-minV);
        float shade=ny>0?1:ny<0?.5F:nx!=0?.6F:.8F;
        uvVertex(pose,vc,sprite,color,light,nx,ny,nz,ax,ay,az,minU,minV,startU,startV,scaleU,scaleV,shade);
        uvVertex(pose,vc,sprite,color,light,nx,ny,nz,bx,by,bz,minU,minV,startU,startV,scaleU,scaleV,shade);
        uvVertex(pose,vc,sprite,color,light,nx,ny,nz,cx,cy,cz,minU,minV,startU,startV,scaleU,scaleV,shade);
        uvVertex(pose,vc,sprite,color,light,nx,ny,nz,dx,dy,dz,minU,minV,startU,startV,scaleU,scaleV,shade);
    }

    private static void uvVertex(PoseStack pose,VertexConsumer vc,TextureAtlasSprite sprite,int color,int light,float nx,float ny,float nz,
                                 float x,float y,float z,float minU,float minV,float startU,float startV,float scaleU,float scaleV,float shade) {
        float u=startU+((nx!=0?z:x)*16-minU)*scaleU,v=startV+((ny!=0?z:-y)*16-minV)*scaleV;
        vc.vertex(pose.last().pose(),x,y,z).color((color>>16&255)/255F*shade,(color>>8&255)/255F*shade,(color&255)/255F*shade,(color>>>24)/255F)
                .uv(sprite.getU(u),sprite.getV(v)).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.last().normal(),nx,ny,nz).endVertex();
    }

    /** Full authored sprite on a horizontal surface; material surfaces retain their coordinate-aware water UVs. */
    public static void decal(PoseStack pose, MultiBufferSource buffers, String kind, float x0, float z0, float x1, float z1, float y,
                             int argb, int light) {
        spriteQuad(pose,buffers,effect(kind),argb,light,0,1,0,false,x0,y,z0,x0,y,z1,x1,y,z1,x1,y,z0);
    }

    /** Clip a surface sprite against the actual vessel opening, keeping its pixel silhouette and UVs intact. */
    public static void decalClipped(PoseStack pose,MultiBufferSource buffers,String kind,float x0,float z0,float x1,float z1,float y,
                                    float minX,float minZ,float maxX,float maxZ,int argb,int light) {
        float a=Math.max(x0,minX),b=Math.max(z0,minZ),c=Math.min(x1,maxX),d=Math.min(z1,maxZ);
        if(a>=c||b>=d) return;
        TextureAtlasSprite sprite=effect(kind);
        spriteQuad(pose,buffers,sprite,argb,light,0,1,0,false,a,y,b,a,y,d,c,y,d,c,y,b,
                sprite.getU((a-x0)/(x1-x0)*16),sprite.getV((b-z0)/(z1-z0)*16),
                sprite.getU((c-x0)/(x1-x0)*16),sprite.getV((d-z0)/(z1-z0)*16));
    }

    /** Crossed pixel sprites stay visible from the front, sides and observer cameras. */
    public static void upright(PoseStack pose, MultiBufferSource buffers, String kind, float x,float y,float z,float width,float height,int argb,int light) {
        TextureAtlasSprite sprite=effect(kind);
        spriteQuad(pose,buffers,sprite,argb,light,0,0,1,true,
                x-width/2,y+height,z,x-width/2,y,z,x+width/2,y,z,x+width/2,y+height,z);
        spriteQuad(pose,buffers,sprite,argb,light,-1,0,0,true,
                x,y+height,z-width/2,x,y,z-width/2,x,y,z+width/2,x,y+height,z+width/2);
    }

    /** A single upright silhouette; callers turn it toward the camera to avoid intersecting flame planes. */
    public static void uprightPlane(PoseStack pose, MultiBufferSource buffers, String kind, float width, float height, int argb, int light) {
        TextureAtlasSprite sprite = effect(kind);
        // The block translucent shader writes depth even at alpha zero. The entity shader discards those margins,
        // so adjacent tongues cannot erase each other. An upward lighting normal keeps burner brightness stable.
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS));
        spriteVertex(pose,vc,argb,light,-width/2,height,0,sprite.getU0(),sprite.getV0(),0,1,0);
        spriteVertex(pose,vc,argb,light,-width/2,0,0,sprite.getU0(),sprite.getV1(),0,1,0);
        spriteVertex(pose,vc,argb,light,width/2,0,0,sprite.getU1(),sprite.getV1(),0,1,0);
        spriteVertex(pose,vc,argb,light,width/2,height,0,sprite.getU1(),sprite.getV0(),0,1,0);
    }

    private static void spriteQuad(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,int argb,int light,
                                   float nx,float ny,float nz,boolean doubleSided,
                                   float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz) {
        spriteQuad(pose,buffers,sprite,argb,light,nx,ny,nz,doubleSided,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,
                sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());
    }

    /** Same quad/UV order as the authored masks, without temporary point and UV arrays per effect. */
    private static void spriteQuad(PoseStack pose,MultiBufferSource buffers,TextureAtlasSprite sprite,int argb,int light,
                                   float nx,float ny,float nz,boolean doubleSided,
                                   float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,
                                   float u0,float v0,float u1,float v1) {
        // Authored masks need alpha discard: fully transparent margins must not occlude other process details.
        VertexConsumer vc=buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS));
        spriteVertex(pose,vc,argb,light,ax,ay,az,u0,v0,nx,ny,nz);
        spriteVertex(pose,vc,argb,light,bx,by,bz,u0,v1,nx,ny,nz);
        spriteVertex(pose,vc,argb,light,cx,cy,cz,u1,v1,nx,ny,nz);
        spriteVertex(pose,vc,argb,light,dx,dy,dz,u1,v0,nx,ny,nz);
        if(doubleSided) {
            spriteVertex(pose,vc,argb,light,dx,dy,dz,u1,v0,-nx,-ny,-nz);
            spriteVertex(pose,vc,argb,light,cx,cy,cz,u1,v1,-nx,-ny,-nz);
            spriteVertex(pose,vc,argb,light,bx,by,bz,u0,v1,-nx,-ny,-nz);
            spriteVertex(pose,vc,argb,light,ax,ay,az,u0,v0,-nx,-ny,-nz);
        }
    }

    private static void spriteVertex(PoseStack pose,VertexConsumer vc,int argb,int light,float x,float y,float z,float u,float v,
                                      float nx,float ny,float nz) {
        vc.vertex(pose.last().pose(),x,y,z).color(argb>>16&255,argb>>8&255,argb&255,argb>>>24)
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
