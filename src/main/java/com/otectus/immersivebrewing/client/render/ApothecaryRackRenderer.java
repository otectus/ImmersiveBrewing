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
    private record Key(String dimension, long position) {}
    private record Shelf(ItemStack stack, boolean reserved, String marker, long acceptedAt) {}
    private record Display(long revision, Shelf[] shelves) {}
    private static final java.util.Map<Key, Display> DISPLAYS = new java.util.HashMap<>();
    private static final java.util.Map<Key, Look> LOOKS = new java.util.HashMap<>();
    private static final class Look { final float[] amount = new float[6]; long time = System.nanoTime(); }
    public static void clear() { DISPLAYS.clear(); LOOKS.clear(); }
    public static void remove(ApothecaryRackBlockEntity rack) {
        Key key=new Key(rack.getLevel().dimension().location().toString(),rack.getBlockPos().asLong());
        DISPLAYS.remove(key);LOOKS.remove(key);
    }
    private static Display display(ApothecaryRackBlockEntity rack, Font font) {
        Key key = new Key(rack.getLevel().dimension().location().toString(), rack.getBlockPos().asLong());
        Display old = DISPLAYS.get(key);
        if (old != null && old.revision() == rack.displayRevision()) return old;
        Shelf[] shelves = new Shelf[ApothecaryRackBlockEntity.SLOTS];
        long now = net.minecraft.Util.getMillis();
        for (int i = 0; i < shelves.length; i++) {
            ItemStack stack = rack.inventory().getStackInSlot(i).copy();
            ItemStack reservation = stack.isEmpty() ? rack.filter(i) : ItemStack.EMPTY;
            ItemStack described = stack.isEmpty() ? reservation : stack;
            var metadata = BrewingMetadata.read(described);
            Grade grade = metadata.map(BrewingMetadata::grade).orElse(null);
            String label = rack.label(i);
            if (label.isEmpty()) label = metadata.map(BrewingMetadata::label).orElse("");
            String symbol = grade == null ? "" : String.valueOf(grade.symbol());
            String marker = label.isEmpty() ? symbol : symbol.isEmpty() ? fitLabel(font, label, 40)
                    : symbol + " " + fitLabel(font, label, Math.max(1, 40 - font.width(symbol + " ")));
            boolean changed = old != null && !ItemStack.matches(stack, old.shelves()[i].stack());
            long acceptedAt = changed && !stack.isEmpty() ? now : old == null ? -1 : old.shelves()[i].acceptedAt();
            shelves[i] = new Shelf(stack, !reservation.isEmpty(), marker, acceptedAt);
        }
        Display value = new Display(rack.displayRevision(), shelves);
        DISPLAYS.put(key, value);
        return value;
    }
    public ApothecaryRackRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ApothecaryRackBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(ApothecaryRackBlock.FACING));
        Minecraft mc = Minecraft.getInstance();
        Display display = display(be, mc.font);
        boolean near=mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(be.getBlockPos().getX()+.5,be.getBlockPos().getY()+.5,be.getBlockPos().getZ()+.5)<100;
        // The position under the cursor (or selected) in this rack's working view turns its bottle and label toward the
        // player, so the label reads without opening anything (handoff 9.3); no bottle turns on its own.
        int looked = -1;
        if (mc.screen instanceof com.otectus.immersivebrewing.client.workshop.WorkshopScreen view && view.pos().equals(be.getBlockPos())
                && view.target() != null) {
            int id = view.target().part().id() - com.otectus.immersivebrewing.workshop.WorkshopParts.R_POSITION;
            if (id >= 0 && id < ApothecaryRackBlockEntity.SLOTS) looked = id;
        }
        Key key=new Key(be.getLevel().dimension().location().toString(),be.getBlockPos().asLong());
        Look look=LOOKS.computeIfAbsent(key,k->new Look());
        long now=System.nanoTime();
        float blend=com.otectus.immersivebrewing.client.presentation.PresentationPolicy.current().animate()
                ? (float)(1-Math.exp(-Math.min(.1,(now-look.time)/1_000_000_000.0)*13)) : 1;
        look.time=now;
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) {
            look.amount[i]+=((i==looked?1:0)-look.amount[i])*blend;
            float turn=look.amount[i];
            Shelf shelf = display.shelves()[i];
            ItemStack stack = shelf.stack();
            int column = i % 3;
            int row = i / 3;
            float x = 0.18F + column * 0.32F;
            float y = row == 0 ? 9.1F/16 : 2.1F/16;
            boolean reserved = shelf.reserved();
            long age = shelf.acceptedAt() < 0 ? 150 : net.minecraft.Util.getMillis() - shelf.acceptedAt();
            float settle = com.otectus.immersivebrewing.client.presentation.PresentationPolicy.current().animate()
                    ? Math.max(0, 1 - age / 150F) * .5F / 16 : 0;
            if (!stack.isEmpty()) {
                pose.pushPose();
                pose.translate(x,y+settle+turn*0.008F,0.29F+turn*0.02F);
                pose.mulPose(Axis.YP.rotationDegrees(turn*16));
                if(near&&(stack.getItem() instanceof net.minecraft.world.item.PotionItem || stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE))) {
                    ApparatusBits.bottle(pose,buffers,be.getLevel(),stack,0,0,0,
                            stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE)?0:1,!stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE),0,0,light);
                } else {
                    pose.translate(0,2.0/16F,0);pose.scale(.28F,.28F,.28F);
                    mc.getItemRenderer().renderStatic(stack,ItemDisplayContext.FIXED,light,OverlayTexture.NO_OVERLAY,pose,buffers,be.getLevel(),(int)be.getBlockPos().asLong()+i);
                }
                pose.popPose();
            }

            String marker = shelf.marker();
            if (reserved) {
                // A small outlined bottle tag, deliberately much smaller and flatter than a real displayed bottle.
                RenderBits.prism(pose, buffers, x-.045F,y+.015F,.115F,x+.045F,y+.09F,.125F,0xFF6B5339,light);
                RenderBits.prism(pose, buffers, x-.025F,y+.03F,.108F,x+.025F,y+.065F,.114F,0xFFE2D4B6,light);
                RenderBits.prism(pose, buffers, x-.012F,y+.065F,.108F,x+.012F,y+.085F,.114F,0xFFE2D4B6,light);
                RenderBits.prism(pose, buffers, x-.016F,y+.037F,.103F,x+.016F,y+.06F,.107F,0xFF6B5339,light);
            }
            if (!marker.isEmpty()) {
                float labelY=row==0?8.0F:1.0F;
                WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.PAPER,x*16-1.8,labelY-.5,1.85,x*16+1.8,labelY+.5,1.94,light);
                pose.pushPose();
                pose.translate(x,(labelY+.33F)/16,1.84F/16);
                pose.mulPose(Axis.YP.rotationDegrees(180));
                float labelScale=.0046F*(1+.18F*turn);
                pose.scale(labelScale,-labelScale,labelScale);
                int width=mc.font.width(marker);
                mc.font.drawInBatch(marker,-width/2F,0,0xFF433729,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,light);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    private static String fitLabel(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 0) {
            end = value.offsetByCodePoints(0, Math.max(0, value.codePointCount(0, end) - 1));
            String candidate = value.substring(0, end) + ellipsis;
            if (font.width(candidate) <= maxWidth) return candidate;
        }
        return ellipsis;
    }
}
