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
    private record Shelf(ItemStack stack, boolean reserved, Grade grade, String marker) {}
    private record Display(long revision, Shelf[] shelves, long acceptedAt) {}
    private static final java.util.Map<Key, Display> DISPLAYS = new java.util.HashMap<>();
    public static void clear() { DISPLAYS.clear(); }
    public static void remove(ApothecaryRackBlockEntity rack) {
        DISPLAYS.remove(new Key(rack.getLevel().dimension().location().toString(), rack.getBlockPos().asLong()));
    }
    private static Display display(ApothecaryRackBlockEntity rack, Font font) {
        Key key = new Key(rack.getLevel().dimension().location().toString(), rack.getBlockPos().asLong());
        Display old = DISPLAYS.get(key);
        if (old != null && old.revision() == rack.displayRevision()) return old;
        Shelf[] shelves = new Shelf[ApothecaryRackBlockEntity.SLOTS];
        boolean changed = false;
        for (int i = 0; i < shelves.length; i++) {
            ItemStack stack = rack.inventory().getStackInSlot(i).copy();
            boolean reserved = stack.isEmpty() && !rack.filter(i).isEmpty();
            Grade grade = BrewingMetadata.read(stack).map(BrewingMetadata::grade).orElse(null);
            String label = rack.label(i);
            String marker = (grade == null ? "" : grade.symbol()) + (label.isEmpty() ? "" : " " + fitLabel(font, label, 34));
            shelves[i] = new Shelf(stack, reserved, grade, marker);
            changed |= old != null && !ItemStack.matches(stack, old.shelves()[i].stack());
        }
        Display value = new Display(rack.displayRevision(), shelves, changed ? net.minecraft.Util.getMillis() : -1);
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
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) {
            Shelf shelf = display.shelves()[i];
            ItemStack stack = shelf.stack();
            int column = i % 3;
            int row = i / 3;
            float x = 0.18F + column * 0.32F;
            float y = row == 0 ? 0.60F : 0.16F;
            boolean reserved = shelf.reserved();
            long age = display.acceptedAt() < 0 ? 150 : net.minecraft.Util.getMillis() - display.acceptedAt();
            float settle = com.otectus.immersivebrewing.client.presentation.PresentationPolicy.current().animate()
                    ? Math.max(0, 1 - age / 150F) * .5F / 16 : 0;
            if (!stack.isEmpty()) {
                pose.pushPose(); pose.translate(x, y + settle, 0.31F); pose.scale(0.36F, 0.36F, 0.36F);
                mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
                pose.popPose();
            }

            Grade grade = shelf.grade();
            String marker = shelf.marker();
            if (reserved) {
                // A small outlined bottle tag, deliberately much smaller and flatter than a real displayed bottle.
                RenderBits.prism(pose, buffers, x-.045F,y+.015F,.115F,x+.045F,y+.09F,.125F,0xFF6B5339,light);
                RenderBits.prism(pose, buffers, x-.025F,y+.03F,.108F,x+.025F,y+.065F,.114F,0xFFE2D4B6,light);
                RenderBits.prism(pose, buffers, x-.012F,y+.065F,.108F,x+.012F,y+.085F,.114F,0xFFE2D4B6,light);
                RenderBits.prism(pose, buffers, x-.016F,y+.037F,.103F,x+.016F,y+.06F,.107F,0xFF6B5339,light);
            }
            pose.pushPose();
            pose.translate(x + 0.09F, y - 0.02F, 0.115F);
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.scale(0.006F, -0.006F, 0.006F);
            int width = mc.font.width(marker);
            mc.font.drawInBatch(marker, -width / 2F, 0, grade == null ? 0xFFE2D4B6 : 0xFF000000 | grade.color(), false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, light);
            pose.popPose();
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
