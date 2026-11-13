package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.presentation.KettleSignatureVisuals;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.client.presentation.StationVisualCache;
import com.otectus.immersivebrewing.client.sound.StationSoundManager;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;

import static com.otectus.immersivebrewing.client.render.WorkshopRenderBits.mirror;

/**
 * The Kettle in the world (handoff 5, 7, 19). One apparatus for every viewer, drawn from the synced workshop state: the
 * bottles in the rack (staged, emptied into the vessel, ready to receive the brew), the liquid rising with each poured
 * serving and changing colour as each reaction progresses, heat shown by the burner knob, flame and surface, the
 * family's visible trouble, undissolved dose pieces, the paddle where its holder holds it, the dose cups on the tray,
 * the spout's stream and the lid. A batch from an earlier version keeps its legacy presentation until it is finished.
 */
public class KettleRenderer implements BlockEntityRenderer<BrewersKettleBlockEntity> {
    private static final int[] FAMILY_SIGNATURE = {0, 3, 4, 5, 6, 7, 8, 9, 10};

    public KettleRenderer(BlockEntityRendererProvider.Context context) {}

    /** Called by client lifecycle wiring on disconnect/resource teardown. */
    public static void clearPresentationCache() {
        StationVisualCache.clear();
    }

    @Override
    public void render(BrewersKettleBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        WorkshopTargets.View view = WorkshopTargets.view(be, WorkshopKind.KETTLE);
        if (view.physical() || view.state() == null) renderWorkshop(be, view, partialTick, pose, buffers, light);
        else renderLegacy(be, partialTick, pose, buffers, light);
        pose.popPose();
    }

    // ---- the hands-on apparatus ----

    private void renderWorkshop(BrewersKettleBlockEntity be, WorkshopTargets.View view, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var level = be.getLevel();
        long time = level == null ? 0 : level.getGameTime();
        double motion = PresentationPolicy.current().decorativeMotion();
        CompoundTag k = view.kettle();
        boolean brewing = k.contains("stage");
        boolean local = be.getBlockPos().equals(ClientWorkshop.focus());
        int carried = local ? ClientWorkshop.carried() : -1;

        // Rack: public stacks, or each reserved serving's state at its own position.
        int charged = 0;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            double x = 1.92 + i * 2.43;
            ItemStack shown = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            int portion = brewing ? WorkshopTargets.portionAt(k, i) : finishingPortion(view, i);
            if (shown.isEmpty() && portion >= 0) {
                int state = brewing ? k.getByteArray("portions")[portion] : 0;
                if (state == KettleProcess.Portion.STAGED.ordinal()) shown = WorkshopTargets.portionItem(view, portion);
                else shown = new ItemStack(Items.GLASS_BOTTLE);
                if (state != KettleProcess.Portion.STAGED.ordinal()) charged++;
            }
            if (carried == WorkshopParts.K_RACK + i) {
                // Lifted over the vessel mouth, tipped toward it.
                WorkshopRenderBits.item(pose, buffers, level, shown, 8, 17.5, 6, 0.3F, 0, -35, light, 40 + i);
                continue;
            }
            WorkshopRenderBits.item(pose, buffers, level, shown, x, 9.9, 14.7, 0.24F, 0, 0, light, (int) be.getBlockPos().asLong() + i);
        }
        if (!brewing && view.physical()) charged = 0;

        // The liquid: each charged serving raises it; its colour moves through the reaction's stages.
        if (charged > 0) {
            int[] colors = view.snapshot().getIntArray("stageColors");
            int doses = k.getByte("doses");
            double progress = k.getByte("progress") / 100.0;
            int from = colors.length == 0 ? 0x385DC6 : colors[Math.max(0, Math.min(colors.length - 1, doses - 1 < 0 ? 0 : doses - 1))];
            int to = colors.length == 0 ? from : colors[Math.max(0, Math.min(colors.length - 1, doses))];
            int color = doses == 0 ? from : WorkshopRenderBits.mix(from, to, k.getByte("stage") == KettleProcess.Stage.REACTING.ordinal() ? progress : 1);
            if (view.sealed()) color = RenderBits.darken(color, 0.75F);
            double y = WorkshopParts.KETTLE_LIQUID_MIN + 0.2 + Math.min(6.4, charged * 1.05);
            var bowl = StationAnchors.KETTLE_BOWL;
            RenderBits.surface(pose, buffers, bowl.minX(), bowl.minZ(), bowl.maxX(), bowl.maxZ(), (float) y / 16F, 0xC8000000 | color, light);
            renderTrouble(pose, buffers, k, y, time, partialTick, motion, light);
            renderHeat(pose, buffers, k, y, time, partialTick, motion, light);
            // Undissolved pieces of the latest dose ride the circulation until the paddle carries them through.
            double undissolved = k.getByte("undissolved") / 100.0;
            if (undissolved > 0.05 && doses > 0) {
                ItemStack dose = WorkshopTargets.doseItem(view, doses - 1);
                int pieces = (int) Math.ceil(undissolved * 3);
                double spin = motion > 0 ? (time + partialTick) * (0.02 + k.getByte("circ") / 100.0 * 0.25) : 0;
                for (int i = 0; i < pieces; i++) {
                    double a = spin + i * 2.1;
                    WorkshopRenderBits.item(pose, buffers, level, dose, 8 + Math.cos(a) * 2.6, y + 0.1, 7 + Math.sin(a) * 2.0,
                            (float) (0.07 + 0.06 * undissolved), (float) Math.toDegrees(a), 90, light, 60 + i);
                }
            }
        }

        // The paddle: in its holder's hand over the liquid, or leaning on its rest.
        ItemStack paddle = be.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
        if (paddle.isEmpty() && view.practice()) paddle = new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get());
        if (!paddle.isEmpty()) {
            double[] p = WorkshopRenderBits.toolPose(be.getBlockPos(), view.snapshot(), WorkshopParts.K_PADDLE);
            pose.pushPose();
            if (p != null) {
                double x = WorkshopParts.KETTLE_CX + p[0] * WorkshopParts.KETTLE_HX, z = WorkshopParts.KETTLE_CZ + p[1] * WorkshopParts.KETTLE_HZ;
                pose.translate(x / 16, (p[2] > 0 ? 13.4 : 15.5) / 16, z / 16);
                pose.mulPose(Axis.ZP.rotationDegrees((float) (-12 - p[0] * 10)));
                pose.mulPose(Axis.XP.rotationDegrees((float) (p[1] * 10)));
            } else {
                pose.translate(mirror(12.8) / 16, 14.6 / 16, 3.2 / 16);
                pose.mulPose(Axis.ZP.rotationDegrees(-28));
            }
            pose.scale(0.5F, 0.5F, 0.5F);
            Minecraft.getInstance().getItemRenderer().renderStatic(paddle, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, 7);
            pose.popPose();
        }

        // The tray ledge: dose cups while a batch owns the doses, the loading positions otherwise.
        WorkshopRenderBits.box(pose, buffers, 1.6, 4.0, -4.5, 14.4, 4.5, 1.0, 0xFF6B4A2E, light);
        if (view.physical()) {
            CompoundTag process = brewing ? k : view.finishing();
            int steps = process.getByte("steps"), doses = process.getByte("doses");
            for (int i = 0; i < Math.min(steps, WorkshopParts.MAX_CUPS); i++) {
                var part = WorkshopParts.get(WorkshopKind.KETTLE, WorkshopParts.K_CUP + i);
                double cx = part.cx(), cz = part.cz();
                boolean next = i == doses;
                double lift = next && motion > 0 ? Math.sin((time + partialTick) * 0.15) * 0.25 + 0.25 : 0;
                WorkshopRenderBits.box(pose, buffers, cx - 1.1, 4.5, cz - 0.9, cx + 1.1, 5.6 + lift, cz + 0.9, next ? 0xFFC9B38A : 0xFF9A8A70, light);
                if (i >= doses && carried != WorkshopParts.K_CUP + i) {
                    WorkshopRenderBits.item(pose, buffers, level, WorkshopTargets.doseItem(view, i), cx, 6.1 + lift, cz, 0.16F, 0, 0, light, 80 + i);
                } else if (carried == WorkshopParts.K_CUP + i) {
                    WorkshopRenderBits.item(pose, buffers, level, WorkshopTargets.doseItem(view, i), 8, 17, 6.5, 0.22F, 0, -40, light, 90 + i);
                }
            }
        } else {
            for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE; i++) {
                ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + i);
                var part = WorkshopParts.get(WorkshopKind.KETTLE, WorkshopParts.K_TRAY + i);
                WorkshopRenderBits.item(pose, buffers, level, s, part.cx(), 5.6, part.cz(), 0.17F, 0, 0, light, 100 + i);
            }
        }

        // Burner control knob: its turn shows the setting from across the room.
        int burner = brewing ? k.getByte("burner") : 0;
        pose.pushPose();
        pose.translate(12.2 / 16, 2.5 / 16, -0.2 / 16);
        pose.mulPose(Axis.ZP.rotationDegrees(-burner * (270F / KettleProcess.BURNER_STEPS)));
        RenderBits.prism(pose, buffers, -1.1F / 16, -1.1F / 16, -0.5F / 16, 1.1F / 16, 1.1F / 16, 0.4F / 16, 0xFF2F2A26, light);
        RenderBits.prism(pose, buffers, -0.25F / 16, 0.2F / 16, -0.8F / 16, 0.25F / 16, 1.3F / 16, -0.4F / 16, 0xFFD9A441, light);
        pose.popPose();

        // Spout over the rack and the stream of a fill that was just accepted.
        WorkshopRenderBits.box(pose, buffers, 7.2, 12.6, 10.6, 8.8, 13.6, 12.8, 0xFFB87333, light);
        long[] transfer = ClientWorkshop.lastTransfer(be.getBlockPos());
        if (transfer != null && net.minecraft.Util.getMillis() - transfer[2] < 420 && PresentationPolicy.current().animate()) {
            int[] colors = view.snapshot().getIntArray("stageColors");
            int color = colors.length == 0 ? 0x385DC6 : colors[colors.length - 1];
            if (transfer[0] == StationEffectEvent.Kind.FILL.ordinal()) {
                double tx = 1.92 + transfer[1] * 2.43;
                WorkshopRenderBits.stream(pose, buffers, 8, 12.7, 12.6, tx, 11.6, 14.7, 0xD0000000 | color, light);
            } else if (transfer[0] == StationEffectEvent.Kind.POUR.ordinal()) {
                WorkshopRenderBits.stream(pose, buffers, 8, 16.5, 6.5, 8, 12.0, 7, 0xC03F76E4, light);
            }
        }

        // Lid: resting, or tied over the mouth when sealed (linen) or held for recovery (dull red).
        JobState state = view.state();
        if (state == JobState.SEALED || state == JobState.RECOVERY) {
            int cover = state == JobState.RECOVERY ? 0xFF9C4A40 : 0xFFD8C8A4;
            RenderBits.surface(pose, buffers, RenderBits.cloth(), 3F / 16F, 3F / 16F, 13F / 16F, 11F / 16F, 13.6F / 16F, cover, light);
        } else if (view.finishing().getBoolean("closed")) {
            WorkshopRenderBits.box(pose, buffers, 3.2, 13.5, 3.2, 12.8, 14.2, 10.8, 0xFF8A5A36, light);
        } else {
            WorkshopRenderBits.boxM(pose, buffers, 2.4, 13.6, 0.4, 6.0, 14.3, 2.6, 0xFF7A7A80, light);
        }

        // The route book on its stand beside the kettle; by-products waiting on their shelf.
        WorkshopRenderBits.item(pose, buffers, level, new ItemStack(ModItems.BREWERS_NOTEBOOK.get()), 17.5, 6.0, 6.0, 0.32F, 70, -20, light, 120);
        for (int i = 0; i < BrewersKettleBlockEntity.BYPRODUCT_SIZE; i++) {
            WorkshopRenderBits.item(pose, buffers, level, be.inventory().getStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i), -1.5, 2.0,
                    5 + i * 2, 0.18F, 90, 0, light, 130 + i);
        }
    }

    private static int finishingPortion(WorkshopTargets.View view, int slot) {
        byte[] slots = view.finishing().getByteArray("slots");
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }

    /** The family's visible trouble: foam, sediment, ribbons, glints, motes, shown with text and shape too. */
    private static void renderTrouble(PoseStack pose, MultiBufferSource buffers, CompoundTag k, double y, long time, float partialTick, double motion, int light) {
        double trouble = k.getByte("trouble") / 100.0;
        if (trouble < 0.04) return;
        int family = Math.max(0, Math.min(ReactionFamily.values().length - 1, k.getByte("family")));
        var signature = KettleSignatureVisuals.of(FAMILY_SIGNATURE[family]);
        int marks = 2 + (int) Math.round(trouble * 8);
        int color = (int) (90 + trouble * 130) << 24 | (trouble > 0.45 ? signature.troubleColor() : signature.healthyColor());
        double drift = motion > 0 ? (time + partialTick) * (0.01 + k.getByte("circ") / 100.0 * 0.12) : 0;
        var bounds = StationAnchors.KETTLE_BOWL;
        for (int i = 0; i < marks; i++) {
            double a = drift + i * 2.399;
            double r = 0.08 + ((i * 37) % 10) / 10.0 * 0.22;
            float x = (float) (0.5 + Math.cos(a) * r * 1.2), z = (float) (0.44 + Math.sin(a) * r);
            float w = switch (signature.motif()) {
                case FOAM, WHORLS -> .07F;
                case STREAKS -> .11F;
                case SEDIMENT -> .05F;
                case GLINTS -> .022F;
                default -> .04F;
            };
            float h = signature.motif() == KettleSignatureVisuals.Motif.STREAKS ? .016F : w;
            RenderBits.surface(pose, buffers, bounds.clampX(x - w), bounds.clampZ(z - h), bounds.clampX(x + w), bounds.clampZ(z + h),
                    (float) (y / 16 + 0.002), color, light);
        }
        // Heat trouble (a rising head of foam, broken shimmer) covers the surface evenly.
        double heat = k.getByte("heatTrouble") / 100.0;
        if (heat > 0.08) {
            RenderBits.surface(pose, buffers, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), (float) (y / 16 + 0.004 + heat * 0.02),
                    (int) (40 + heat * 120) << 24 | 0xF4F6FF, light);
        }
    }

    /** Heat shows first in the flame (block state), then as rings of bubbles on the liquid. */
    private static void renderHeat(PoseStack pose, MultiBufferSource buffers, CompoundTag k, double y, long time, float partialTick, double motion, int light) {
        double temp = k.getByte("temp") / 100.0;
        int band = KettleProcess.heatBand(temp);
        if (band < 1) return;
        int rings = band * 2;
        double t = motion > 0 ? (time + partialTick) * 0.25 : 0;
        for (int i = 0; i < rings; i++) {
            double phase = (t + i * 1.7) % 4.0 / 4.0;
            double x = 5 + ((i * 53) % 60) / 10.0, z = 4 + ((i * 29) % 50) / 10.0;
            double r = 0.15 + phase * (0.25 + band * 0.12);
            int alpha = (int) ((1 - phase) * (60 + band * 25));
            WorkshopRenderBits.surface(pose, buffers, x - r, z - r, x + r, z + r, y + 0.03, alpha << 24 | 0xFFFFFF, light);
        }
    }

    // ---- the legacy presentation (batches started before the hands-on overhaul) ----

    private void renderLegacy(BrewersKettleBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var mc = Minecraft.getInstance();
        ItemStack sample = ItemStack.EMPTY;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.isEmpty()) continue;
            if (sample.isEmpty()) sample = s;
            pose.pushPose();
            pose.translate(0.12 + i * 0.152, 0.62, 0.92);
            pose.scale(0.24F, 0.24F, 0.24F);
            mc.getItemRenderer().renderStatic(s, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), (int) be.getBlockPos().asLong() + i);
            pose.popPose();
        }
        StationVisual authoritative = be.visual();
        StationVisual visual = StationVisualCache.sample(be.getLevel().dimension().location().toString(), be.getBlockPos().asLong(),
                be.getLevel().getGameTime(), partialTick, authoritative, PresentationPolicy.current().decorativeMotion());
        boolean active = visual.active();
        JobState state = be.view().state();
        int servings = state != null ? Math.max(1, be.view().servings()) : 0;
        if (servings > 0) {
            ItemStack shown = !be.view().output().isEmpty() ? be.view().output() : sample;
            int color = visual.kettle().currentColor() != 0 ? visual.kettle().currentColor() : (shown.isEmpty() ? 0x385DC6 : PotionUtils.getColor(shown));
            if (!active) color = RenderBits.darken(color, 0.7F);
            float level = Math.min(StationAnchors.KETTLE_BOWL.maxY(), StationAnchors.KETTLE_BOWL.minY() + servings * .06F);
            RenderBits.surface(pose, buffers, StationAnchors.KETTLE_BOWL.minX(), StationAnchors.KETTLE_BOWL.minZ(), StationAnchors.KETTLE_BOWL.maxX(),
                    StationAnchors.KETTLE_BOWL.maxZ(), level, 0xC0000000 | color, light);
            long additionAge = StationSoundManager.acknowledgementAge(be, StationEffectEvent.Kind.ADDITION);
            if (PresentationPolicy.current().animate() && additionAge >= 0 && additionAge < 250) {
                float radius = .025F + (additionAge / 250F) * .12F;
                RenderBits.surface(pose, buffers, .5F - radius, .5F - radius, .5F + radius, .5F + radius, level + .004F, 0x70FFFFFF, light);
            }
            if (state == JobState.SEALED || state == JobState.RECOVERY) {
                int cover = state == JobState.RECOVERY ? 0xFF9C4A40 : 0xFFD8C8A4;
                RenderBits.surface(pose, buffers, RenderBits.cloth(), 3F / 16F, 3F / 16F, 13F / 16F, 11F / 16F, 13.6F / 16F, cover, light);
            }
        }
        ItemStack paddle = be.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
        if (!paddle.isEmpty()) {
            pose.pushPose();
            if (state == JobState.SEALED || state == JobState.RECOVERY) pose.translate(1.5 / 16D, 11 / 16D, 9 / 16D);
            else pose.translate(StationAnchors.KETTLE_PADDLE.x, StationAnchors.KETTLE_PADDLE.y, StationAnchors.KETTLE_PADDLE.z);
            long time = be.getLevel() == null ? 0 : be.getLevel().getGameTime();
            float angle = StationVisualCache.paddleAngle(be.getLevel().dimension().location().toString(), be.getBlockPos().asLong(), time, partialTick,
                    visual, PresentationPolicy.current().decorativeMotion());
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            pose.mulPose(Axis.ZP.rotationDegrees(-25));
            pose.scale(0.5F, 0.5F, 0.5F);
            mc.getItemRenderer().renderStatic(paddle, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 7);
            pose.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(BrewersKettleBlockEntity be) {
        // Parts reach a few pixels past the block (the tray ledge, the book stand).
        return true;
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}
