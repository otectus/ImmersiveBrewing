package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Alembic in the world (handoff 9.2), followed through the machine: the charge boils behind the boiler's sight
 * window; vapour rises through the neck into the glass condenser jacket, where the copper coil runs down through the
 * coolant (its level visible); the condensate reaches the diverter, whose lever sends it to the receiver flask or the
 * separate waste crock. Clarity is told by the drops themselves: cloudy early drops are milky, large and slow; the clear
 * heart falls in small bright drops at a steady rhythm; the late haze is faint and irregular. The coolant handwheel, the
 * burner dial, the clamp lever and the purify / reclaim fitting have different shapes as well as colours.
 */
public class AlembicRenderer implements BlockEntityRenderer<AlembicBlockEntity> {
    /** Per condensate stage (none, cloudy, clearing, clear, haze rising, hazy): colour and opacity, drop size, period in ticks. */
    private static final int[] CONDENSATE = {0x00000000, 0xD8D8D0C0, 0xC4E0E8E2, 0xB0E8F6FF, 0xA8D8D0A8, 0x88C8B888};
    private static final double[] DROP_SIZE = {0, 0.34, 0.26, 0.18, 0.24, 0.22};
    private static final double[] DROP_PERIOD = {1, 16, 11, 7, 10, 13};

    public AlembicRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AlembicBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        Level level = be.getLevel();
        long time = level == null ? 0 : level.getGameTime();
        WorkshopTargets.View view = WorkshopTargets.view(be, WorkshopKind.ALEMBIC);
        boolean active = view.state() == JobState.RUNNING && be.visual().active();
        double motion = active ? PresentationPolicy.current().decorativeMotion() : 0;
        boolean animate = motion > 0;
        CompoundTag a = view.alembic();
        CompoundTag snap = view.snapshot();
        TextureAtlasSprite glass = ApparatusBits.plain();

        // The bottles waiting to be charged, on their stand in front.
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.INPUT + i);
            ApparatusBits.bottleScaled(pose,buffers,level,s,1.6+(i%3)*2.0,3.4,1.6+(i/3)*2.0,.85F,light);
        }

        // The charge in the boiler, seen through the window: its colour, its level falling as it distils, bubbling with heat.
        ItemStack charge = snap.contains("charge") ? ItemStack.of(snap.getCompound("charge")) : ItemStack.EMPTY;
        boolean purifying = view.operation() == Operation.PURIFY && !charge.isEmpty();
        boolean reclaiming = view.operation() == Operation.RECLAIM;
        boolean activeReclaim = reclaiming && active;
        double temp = a.getByte("temp") / 100.0;
        if (purifying || reclaiming) {
            int color = charge.isEmpty() ? 0x8A6A9A : PotionUtils.getColor(charge);
            color = RenderBits.darken(color, (float) (0.7 + temp * 0.3));
            double fraction = a.getByte("fraction") / 100.0;
            double fill = 1.0 + Math.min(3.8, snap.getInt("chargeCount") * 0.7) * (1 - fraction * 0.6);
            ApparatusBits.clearBox(pose, buffers, glass, 2.3, 3.6, 5.7, 7.3, 3.6 + fill, 10.9, 0xD0000000 | color, light);
            if (temp > 0.45 || activeReclaim) {
                int bubbles = activeReclaim ? 3 : (int) Math.round((temp - 0.45) * 12);
                for (int i = 0; i < bubbles; i++) {
                    double phase = animate ? ((time + partialTick) * 0.05 * motion + i * 0.29) % 1.0 : 0.5;
                    double bx = 3 + (i * 1.3) % 4, by = 3.8 + phase * fill;
                    RenderBits.upright(pose,buffers,"bubble",(float)bx/16,(float)by/16,5.75F/16,.8F/16,.8F/16,0x90E8F4F5,light);
                }
            }
            // Vapour along the neck while it boils.
            if (purifying && temp > 0.55) {
                for (int i = 0; i < 3; i++) {
                    // Reduced motion keeps a fixed process-path cue instead of hiding the vapour entirely.
                    double phase = animate ? ((time + partialTick) * 0.04 * motion + i / 3.0) % 1.0 : (i + 0.5) / 3.0;
                    double vx = 6.6 + phase * 4.6, vy = phase < 0.2 ? 12 + phase * 10 : 14.0;
                    ApparatusBits.clearBox(pose, buffers, glass, vx - 0.3, vy - 0.25, 8.1, vx + 0.3, vy + 0.25, 8.7, (int) ((1 - phase) * 90) << 24 | 0xF4F8FF, light);
                }
            }
        } else if (view.state() != null) {
            renderLegacyCharge(be, pose, buffers, light);
        }

        // The charge funnel, the clamp lever on the hood and the purify / reclaim fitting beside it.
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,4.3,12.4,7.7,5.5,13.8,8.9,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,4.15,13.8,7.55,5.65,13.84,9.05,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,3.8,13.8,7.2,6.0,14.6,7.55,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,3.8,13.8,9.05,6.0,14.6,9.4,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,3.8,13.8,7.55,4.15,14.6,9.05,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,5.65,13.8,7.55,6.0,14.6,9.05,light);
        boolean clamped = a.getBoolean("clamped");
        pose.pushPose();
        pose.translate(1.8 / 16, 10.4 / 16, 8.3 / 16);
        pose.mulPose(Axis.ZP.rotationDegrees(clamped ? 0 : 55));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.STEEL,-0.35,-0.35,-1.2,0.35,2.4,1.2,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-0.5,2.0,-0.4,0.5,2.6,0.4,light);
        pose.popPose();
        // While a job exists its operation is authoritative; reclaimMode is only the idle setup preference.
        boolean reclaimMode = view.state() == null ? snap.getBoolean("reclaimMode") : reclaiming;
        pose.pushPose();
        pose.translate(7.9 / 16, 11.4 / 16, 8.4 / 16);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,0,-1.1,-1.1,0.4,1.1,1.1,light);
        pose.mulPose(Axis.XP.rotationDegrees(reclaimMode ? 90 : 0));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,0.4,-0.15,-0.15,0.7,1.0,0.15,light);
        pose.popPose();
        if (reclaimMode) {
            // The salt sieve sits in the funnel when the fitting reads "reclaim".
            WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.CERAMIC,4.0,14.2,7.4,5.8,14.5,9.2,light);
            if (reclaiming) {
                double grown = snap.getByte("reclaimProgress") / 100.0;
                for (int i = 0; i < 4; i++) {
                    double sx = 4.3 + (i % 2) * 0.9, sz = 7.7 + (i / 2) * 0.9, h = 0.15 + grown * 0.45;
                    WorkshopRenderBits.box(pose, buffers, sx - 0.2, 14.5, sz - 0.2, sx + 0.2, 14.5 + h, sz + 0.2, 0xFFF6F4EE, light);
                }
                if (activeReclaim) renderReclaimFlakes(pose, buffers, grown, time, partialTick, motion, light);
            }
        }

        // The burner dial on the base and the coolant handwheel on the jacket: a knob and a wheel, never confused.
        dial(pose, buffers, 8.5, 3.0, 2.0, a.getByte("burner"), AlembicProcess.BURNER_STEPS, light);
        handwheel(pose, buffers, 15.2, 8.7, 12.2, a.getByte("valve"), light);

        // Coolant in the glass jacket, and the copper coil running down through it.
        int capacity = Math.max(1, snap.getInt("coolantCapacity"));
        double water = Math.min(1, snap.getInt("coolantMb") / (double) capacity);
        if (water > 0) {
            double waterY = 7.45 + water * 4.4;
            ApparatusBits.clearBox(pose, buffers, glass, 10.6, 7.45, 9.8, 13.8, waterY, 14.8, 0x387BA9BC, light);
            // The stronger top plane makes the coolant quantity readable through two layers of glass.
            WorkshopRenderBits.surface(pose, buffers, 10.65, 9.85, 13.75, 14.75, waterY + 0.01, 0x887BA9BC, light);
        }
        for(int i=0;i<12;i++) {
            double t0=i/12.0,t1=(i+1)/12.0;
            Vec3 from=new Vec3(12.2+Math.cos(t0*Math.PI*6)*1.1,11.6-t0*4,12.3+Math.sin(t0*Math.PI*6)*1.6);
            Vec3 to=new Vec3(12.2+Math.cos(t1*Math.PI*6)*1.1,11.6-t1*4,12.3+Math.sin(t1*Math.PI*6)*1.6);
            Vec3 delta=to.subtract(from);
            pose.pushPose();pose.translate(from.x/16,from.y/16,from.z/16);
            pose.mulPose(new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0,1,0),new org.joml.Vector3f((float)delta.x,(float)delta.y,(float)delta.z).normalize()));
            WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,-.2,0,-.2,.2,delta.length(),.2,light);
            pose.popPose();
        }

        // The diverter's lever points at where the condensate goes.
        boolean receiver = a.getBoolean("receiver");
        pose.pushPose();
        pose.translate(12.2 / 16, 7.4 / 16, 8.0 / 16);
        // The lever points along the outlet in use: forward to the receiver, or to the right to the waste.
        pose.mulPose(Axis.YP.rotationDegrees(receiver ? 180 : -90));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,-0.2,0,-0.2,0.2,0.3,2.0,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-0.35,0,1.7,0.35,0.6,2.2,light);
        pose.popPose();

        // Condensate drops at the active outlet: clarity in their opacity, size and rhythm, not only their colour.
        int condensate = Math.max(0, Math.min(CONDENSATE.length - 1, a.getByte("condensate")));
        boolean flowing = purifying && clamped && a.getByte("burner") > 0 && a.getByte("valve") > 0 && condensate > 0 && a.getByte("fraction") < 100;
        if (flowing) {
            double period = DROP_PERIOD[condensate];
            // The heart drips at a steady rhythm; the haze comes irregularly.
            boolean haze = condensate >= AlembicProcess.Condensate.HAZE_RISING.ordinal();
            double jitter = haze ? Math.sin(time * 1.7 * motion) * 3 : 0;
            double phase = animate ? ((((time + partialTick) * motion + jitter) % period) + period) % period / period : 0.5;
            double size = DROP_SIZE[condensate];
            double fromX = receiver ? 13.3 : 10.4, fromY = receiver ? 6.5 : 6.3, fromZ = receiver ? 5.2 : 7.9;
            double toY = receiver ? 3.3 + a.getByte("toReceiver") / 100.0 * 2.3 : 3.4 + a.getByte("toWaste") / 100.0 * 1.6;
            double y = fromY - (fromY - toY) * phase * phase;
            RenderBits.upright(pose,buffers,"shard",(float)fromX/16,(float)(y-size*1.3)/16,(float)fromZ/16,(float)(size*3.2)/16,(float)(size*4.2)/16,CONDENSATE[condensate],light);
            if (condensate == AlembicProcess.Condensate.CLEAR.ordinal()) {
                WorkshopRenderBits.box(pose, buffers, fromX - 0.05, y + size * 0.3, fromZ - size - 0.02, fromX + 0.05, y + size * 0.6, fromZ - size, 0xFFFFFFFF, 0xF000F0);
            }
        }
        // The receiver and the waste fill with what was routed to each, never with the whole run.
        double toReceiver = a.getByte("toReceiver") / 100.0, toWaste = a.getByte("toWaste") / 100.0;
        if (toReceiver > 0) {
            var b = StationAnchors.ALEMBIC_RECEIVER_FILL;
            int color = charge.isEmpty() ? 0xE8F4FF : WorkshopRenderBits.mix(PotionUtils.getColor(charge), 0xF4FAFF, 0.35);
            ApparatusBits.clearBox(pose, buffers, glass, b.minX() * 16, b.minY() * 16, b.minZ() * 16, b.maxX() * 16,
                    (b.minY() + (float) toReceiver * (b.maxY() - b.minY())) * 16, b.maxZ() * 16, 0xB0000000 | color, light);
        }
        if (toWaste > 0) ApparatusBits.clearBox(pose, buffers, glass, 9.05, 3.4, 6.65, 10.55, 3.4 + toWaste * 1.7, 8.75, 0xC04A5058, light);

        // Finished output stands in front of the receiver, the reclaimed salt with it.
        int shown = 0;
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE && shown < 4; i++) {
            ItemStack s = be.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i);
            if (s.isEmpty()) continue;
            // Collected output waits beside the receiver it came from (the salt by the waste crock).
            if (s.is(ModItems.SPENT_ALCHEMICAL_SALT.get())) WorkshopRenderBits.item(pose, buffers, level, s, 9.8, 3.05, 10.4, 0.14F, 0, 90, light, 30 + i);
            else ApparatusBits.bottleScaled(pose,buffers,level,s,12.6+(shown%2)*1.8,3.4,8.4+(shown/2)*1.9,.7F,light);
            shown++;
        }
        pose.popPose();
    }

    /** Sparse salt at the actual sieve and waste crock; reduced motion keeps fixed progress cues instead of a loop. */
    private static void renderReclaimFlakes(PoseStack pose, MultiBufferSource buffers, double progress, long time, float partialTick,
                                            double motion, int light) {
        int flakes = 1 + (int) Math.floor(Math.min(1, progress) * 2);
        for (int i = 0; i < flakes; i++) {
            double phase = motion > 0 ? ((time + partialTick) * 0.045 * motion + i / (double) flakes) % 1 : (i + 0.5) / flakes;
            double x = 4.45 + i * 0.45;
            double y = 15.15 - phase * 0.55;
            double z = 7.9 + (i % 2) * 0.65;
            RenderBits.upright(pose, buffers, "flake", (float) x / 16, (float) y / 16, (float) z / 16,
                    0.45F / 16, 0.35F / 16, 0xD8F6F4EE, light);
        }
        double wastePhase = motion > 0 ? ((time + partialTick) * 0.035 * motion) % 1 : Math.min(0.85, 0.2 + progress * 0.65);
        double wasteY = 6.15 - wastePhase * 2.15;
        RenderBits.upright(pose, buffers, "flake", 9.8F / 16, (float) wasteY / 16, 7.7F / 16,
                0.55F / 16, 0.42F / 16, 0xC8D9D1C4, light);
    }

    /** A flat dial knob on a vertical spindle (the burner). */
    private static void dial(PoseStack pose, MultiBufferSource buffers, double x, double y, double z, int setting, int steps, int light) {
        for (int s = 0; s <= steps; s++) {
            double ang = Math.toRadians(-135 + s * (270.0 / steps));
            double mx = x + Math.sin(ang) * 1.15, mz = z - Math.cos(ang) * 1.15;
            WorkshopRenderBits.box(pose, buffers, mx - 0.07, y, mz - 0.07, mx + 0.07, y + 0.05, mz + 0.07, 0xFF3A322C, light);
        }
        pose.pushPose();
        pose.translate(x / 16, y / 16, z / 16);
        pose.mulPose(Axis.YP.rotationDegrees(-(-135F + setting * (270F / steps))));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-0.8,0,-0.8,0.8,1.0,0.8,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,-0.15,1.0,-0.95,0.15,1.25,0.1,light);
        pose.popPose();
    }

    /** A spoked handwheel turning about the jacket's side (the coolant valve). */
    private static void handwheel(PoseStack pose, MultiBufferSource buffers, double x, double y, double z, int setting, int light) {
        pose.pushPose();
        pose.translate(x / 16, y / 16, z / 16);
        pose.mulPose(Axis.XP.rotationDegrees(setting * 70F));
        for (int i=0;i<8;i++) {
            double angle=i*Math.PI/4,next=(i+1)*Math.PI/4;
            double y0=Math.cos(angle)*1.3,z0=Math.sin(angle)*1.3;
            double dy=Math.cos(next)*1.3-y0,dz=Math.sin(next)*1.3-z0;
            pose.pushPose();pose.translate(0,y0/16,z0/16);
            pose.mulPose(Axis.XP.rotation((float)Math.atan2(dz,dy)));
            WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.STEEL,-.18,-.12,-.12,.18,Math.hypot(dy,dz)+.12,.12,light);
            pose.popPose();
        }
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-.15,-1.2,-.12,.15,1.2,.12,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-.15,-.12,-1.2,.15,.12,1.2,light);
        pose.popPose();
    }

    /** A legacy purification or a passive reclamation: the bottles stay in the still while the job owns them. */
    private static void renderLegacyCharge(AlembicBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        if (be.view().state() == null || be.view().material().isEmpty()) return;
        for (int i = 0; i < Math.min(AlembicBlockEntity.INPUT_SIZE, be.view().servings()); i++) {
            ApparatusBits.bottle(pose, buffers, be.getLevel(), be.view().material(), 1.6 + (i % 3) * 2.0, 3.4, 1.6 + (i / 3) * 2.0, 1, true, 0, 0, light);
        }
        StationVisual visual = be.visual();
        if (visual.alembic().collectedFraction() > 0) {
            var bounds = StationAnchors.ALEMBIC_RECEIVER_FILL;
            float level = bounds.minY() + visual.alembic().collectedFraction() * (bounds.maxY() - bounds.minY());
            RenderBits.surface(pose, buffers, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), level, 0xB0E8F4FF, light);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(AlembicBlockEntity be) {
        return false;
    }
}
