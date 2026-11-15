package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.presentation.KettleSignatureVisuals;
import com.otectus.immersivebrewing.client.presentation.KettleSurface;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.PresentationProfiles;
import com.otectus.immersivebrewing.client.presentation.StationAnchors;
import com.otectus.immersivebrewing.client.presentation.StationVisualCache;
import com.otectus.immersivebrewing.client.presentation.ToolMotion;
import com.otectus.immersivebrewing.client.sound.StationSoundManager;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.client.workshop.WorkshopTransfers;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import java.util.UUID;

import static com.otectus.immersivebrewing.client.render.WorkshopRenderBits.mirror;

/**
 * The Kettle in the world (handoff 4, 7, 8): one apparatus for every viewer, drawn from the synced workshop state at the
 * same coordinates as the server's part table. The bottles stand on the foreground rail (staged, emptied into the
 * vessel, filled at the spout), the liquid's level is the owned, unbottled servings and its surface answers the paddle's
 * actual contact, every stream and dose that lands, the circulation and the heat; the reaction family shows in the
 * material itself (froth, sediment, shimmer, ribbons, trapped gas, whorls, glints, motes). Bottles and cups travel,
 * tip and return through {@link WorkshopTransfers}; the spout's carriage slides over the bottle it fills; a player's
 * arm grips what they hold. The recipe page on the book stand shows what a tipped bottle would start. A batch from an
 * earlier version keeps its legacy presentation until it is finished.
 */
public class KettleRenderer implements BlockEntityRenderer<BrewersKettleBlockEntity> {
    private static final int[] FAMILY_SIGNATURE = {0, 3, 4, 5, 6, 7, 8, 9, 10};
    private static final float[] COVER_Z = {2.5F, 2.8F, 5.75F, 6, 9.25F, 9.5F, 13.55F, 13.8F, 15.3F};
    private static final float[] COVER_Y = {10.25F, 10.75F, 10.75F, 11.5F, 11.5F, 12.25F, 12.25F, 12.85F, 12.85F};
    /** Where the paddle rests: its blade tip, and the direction up its shaft through the handle loop. */
    private static final Vec3 REST_TIP = new Vec3(1.3, 4.5, 12.6), REST_SHAFT = new Vec3(0, 10.7, -7.6).normalize();
    /** Beyond this many blocks from the camera a kettle is drawn at reduced detail (see renderWorkshop). */
    private static final double FAR = 10;
    private static final Vec3 HELD_SHAFT = new Vec3(-0.28, 1, -0.66).normalize();
    /** The forearm reaches in from the player's side, low and from the right, never toward the lens. */
    private static final Vec3 ARM_TOWARD = new Vec3(-0.5, 0.22, -1).normalize();

    public KettleRenderer(BlockEntityRendererProvider.Context context) {}

    /** Called by client lifecycle wiring on disconnect/resource teardown. */
    public static void clearPresentationCache() {
        StationVisualCache.clear();
        KettleSurface.clear();
        ToolMotion.clear();
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
        Level level = be.getLevel();
        long time = level == null ? 0 : level.getGameTime();
        double motion = PresentationPolicy.current().decorativeMotion();
        CompoundTag k = view.kettle();
        boolean brewing = k.contains("stage");
        var pos = be.getBlockPos();
        PresentationProfiles.Profile profile = PresentationProfiles.forStation(pos);
        if (profile == null) profile = PresentationProfiles.GENERIC;
        // Level of detail: a kettle more than FAR blocks from the camera that nobody here is working at keeps its state
        // and material but simulates its liquid at 20 Hz, draws it flat and leaves the page's small print undrawn.
        boolean far = !pos.equals(com.otectus.immersivebrewing.client.workshop.ClientWorkshop.focus())
                && Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5) > FAR * FAR;

        // Rail: public bottles, or each reserved serving's state at its own position (staged full, emptied, filled).
        int charged = 0;
        byte[] portions = k.getByteArray("portions");
        for (byte b : portions) if (b != KettleProcess.Portion.STAGED.ordinal()) charged++;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            int part = WorkshopParts.K_RACK + i;
            double x = WorkshopParts.railX(i);
            if (WorkshopTransfers.away(pos, part)) continue;
            ItemStack shown = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            int portion = brewing ? WorkshopTargets.portionAt(k, i) : finishingPortion(view, i);
            float fill;
            boolean stopper;
            if (!shown.isEmpty()) {
                float filling = WorkshopTransfers.filling(pos, part);
                fill = filling >= 0 ? filling : shown.is(Items.GLASS_BOTTLE) ? 0 : 1;
                stopper = !shown.is(Items.GLASS_BOTTLE) && (filling < 0 || filling > 0.95F);
            } else if (portion >= 0) {
                int state = brewing && portion < portions.length ? portions[portion] : 0;
                if (state == KettleProcess.Portion.STAGED.ordinal()) {
                    shown = WorkshopTargets.portionItem(view, portion);
                    fill = 1;
                    stopper = true;
                } else {
                    // Its serving is in the vessel; the bottle waits, reserved, for the spout.
                    shown = new ItemStack(Items.GLASS_BOTTLE);
                    fill = 0;
                    stopper = false;
                }
            } else {
                ItemStack ghost = WorkshopTargets.ghost(view, part);
                if (!ghost.isEmpty()) ApparatusBits.ghost(pose, buffers, level, ghost, x, WorkshopParts.RAIL_TOP + 2, WorkshopParts.RAIL_Z, 0.28F, 0, time);
                continue;
            }
            if(far) WorkshopRenderBits.item(pose,buffers,level,shown,x,WorkshopParts.RAIL_TOP+2.0,WorkshopParts.RAIL_Z,.25F,0,0,light,30+i);
            else ApparatusBits.bottle(pose, buffers, level, shown, x, WorkshopParts.RAIL_TOP, WorkshopParts.RAIL_Z, fill, stopper, 0, 0, light);
        }
        if (!brewing) charged = 0;

        // The liquid: the owned, unbottled servings, held back while a stream is still running into or out of it.
        double servings = WorkshopTransfers.servings(pos, charged);
        double surfaceY = WorkshopParts.kettleLevel(servings);
        KettleSurface surface = KettleSurface.get(pos);
        ToolMotion paddleMotion = ToolMotion.of(pos, WorkshopParts.K_PADDLE);
        double[] paddlePose = WorkshopRenderBits.toolPose(pos, view.snapshot(), WorkshopParts.K_PADDLE);
        boolean paddleIn = paddlePose != null && paddlePose[2] > 0 && servings > 0.05;
        float[] contact = paddleIn ? new float[] {paddleMotion.x, paddleMotion.z, paddlePose.length > 3 && paddlePose[3] > 0 ? 1 : 0} : null;
        int heat = brewing ? KettleProcess.heatBand(k.getByte("temp") / 100.0) : 0;
        surface.step(contact, brewing ? k.getByte("circ") / 100.0 : 0, heat, brewing ? k.getByte("undissolved") / 100.0 : 0, motion, far);
        if (servings > 0.02) {
            int color = liquidColor(view, k, profile);
            if (view.sealed()) color = RenderBits.darken(color, 0.75F);
            int alpha = (int) (150 + 80 * profile.clarity());
            liquid(pose, buffers, surface, (float) surfaceY, alpha << 24 | color, light, time, partialTick, motion, far);
            int family = Math.max(0, Math.min(ReactionFamily.values().length - 1, k.getByte("family")));
            if (brewing) material(pose, buffers, surface, k, ReactionFamily.values()[family], (float) surfaceY, time, partialTick, motion, light, profile);
            fragments(pose, buffers, level, surface, (float) surfaceY, light);
            renderHeat(pose, buffers, surface, heat, (float) surfaceY, time, partialTick, motion, light, profile.bubbles());
        }

        // The paddle: in its holder's hand at the liquid (or swept along the bottom), else easing back to its rest.
        ItemStack paddle = be.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
        if (paddle.isEmpty() && view.practice()) paddle = new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get());
        if (!paddle.isEmpty()) {
            boolean covered = view.state() == JobState.SEALED || view.state() == JobState.RECOVERY || view.finishing().getBoolean("closed");
            boolean holding = !covered && paddlePose != null;
            float tx = (float) mirror(REST_TIP.x), ty = (float) REST_TIP.y, tz = (float) REST_TIP.z;
            if (holding) {
                tx = (float) (WorkshopParts.KETTLE_CX + paddlePose[0] * WorkshopParts.KETTLE_HX);
                tz = (float) (WorkshopParts.KETTLE_CZ + paddlePose[1] * WorkshopParts.KETTLE_HZ);
                boolean deep = paddlePose.length > 3 && paddlePose[3] > 0;
                ty = paddlePose[2] > 0 ? (deep ? WorkshopParts.KETTLE_FLOOR + 0.35F : (float) surfaceY - 0.9F) : (float) surfaceY + 1.6F;
                ty = Math.max(WorkshopParts.KETTLE_FLOOR + 0.3F, ty);
            }
            boolean local = pos.equals(ClientWorkshop.focus()) && ClientWorkshop.tool() == WorkshopParts.K_PADDLE;
            // Closing the mouth seats the paddle in its external rest immediately, clear of the fitted cover.
            paddleMotion.update(holding, tx, ty, tz, local, covered ? 0 : motion);
            Vec3 shaft = slerp(REST_SHAFT, lean(HELD_SHAFT, paddleMotion), paddleMotion.held);
            Vec3 tip = new Vec3(paddleMotion.x, paddleMotion.y, paddleMotion.z);
            drawPaddle(pose, buffers, level, paddle, tip, shaft, light);
            if (holding) {
                UUID holder = view.holderId(WorkshopParts.K_PADDLE);
                if (holder == null && local && Minecraft.getInstance().player != null) holder = Minecraft.getInstance().player.getUUID();
                ApparatusBits.arm(pose, buffers, holder, tip.add(shaft.scale(10.5)), ARM_TOWARD, light);
            }
        } else {
            ItemStack ghost = WorkshopTargets.ghost(view, WorkshopParts.K_PADDLE);
            if (!ghost.isEmpty()) ApparatusBits.ghost(pose, buffers, level, ghost, mirror(1.4), 10.5, 9.0, 0.45F, 0, time);
        }

        // The dose tray: cups while a batch owns the doses, the loading positions (and what the page still needs) otherwise.
        if (view.physical()) {
            CompoundTag process = brewing ? k : view.finishing();
            int steps = process.getByte("steps"), doses = process.getByte("doses");
            for (int i = 0; i < Math.min(steps, WorkshopParts.MAX_CUPS); i++) {
                int part = WorkshopParts.K_CUP + i;
                var cup = WorkshopParts.get(WorkshopKind.KETTLE, part);
                double cx = cup.cx(), cz = cup.cz();
                boolean next = i == doses;
                double lift = next && !WorkshopTransfers.away(pos, part) ? (Math.sin((time + partialTick) * 0.15) + 1) * 0.12 * motion : 0;
                if (WorkshopTransfers.away(pos, part)) continue;
                drawCup(pose, buffers, level, i >= doses ? WorkshopTargets.doseItem(view, i) : ItemStack.EMPTY, cx, 4.4 + lift, cz, next, light);
            }
        } else {
            for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE; i++) {
                int part = WorkshopParts.K_TRAY + i;
                var spot = WorkshopParts.get(WorkshopKind.KETTLE, part);
                if (WorkshopTransfers.away(pos, part)) continue;
                ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + i);
                if (!s.isEmpty()) WorkshopRenderBits.item(pose, buffers, level, s, spot.cx(), 4.55, spot.cz(), 0.13F, 0, 90, light, 100 + i);
                else {
                    ItemStack ghost = WorkshopTargets.ghost(view, part);
                    if (!ghost.isEmpty()) ApparatusBits.ghost(pose, buffers, level, ghost, spot.cx(), 4.6, spot.cz(), 0.13F, 90, time);
                }
            }
        }

        // The burner: a dial on the deck turning on a vertical spindle (readable from above), and the flame at the grate.
        int burner = brewing ? k.getByte("burner") : 0;
        float flame = brewing ? k.getByte("flame") / 100F : 0;
        drawBurner(pose, buffers, k, burner, light);
        float flameYaw = be.getBlockState().getValue(StationBlock.FACING).toYRot()
                - Minecraft.getInstance().gameRenderer.getMainCamera().getYRot();
        drawFlame(pose, buffers, flame, time, partialTick, motion, flameYaw);
        ItemStack fuel = be.inventory().getStackInSlot(BrewersKettleBlockEntity.FUEL);
        if (!fuel.isEmpty()) WorkshopRenderBits.item(pose, buffers, level, fuel, 3.0, 4.75, 1.9, 0.1F, 0, 90, light, 140);
        else {
            ItemStack ghost = WorkshopTargets.ghost(view, WorkshopParts.K_FUEL);
            if (!ghost.isEmpty()) ApparatusBits.ghost(pose, buffers, level, ghost, 3.0, 5.0, 1.9, 0.12F, 90, time);
        }

        // The spout carriage slides along its pipe to the bottle being filled.
        float carriage = WorkshopTransfers.carriageX(pos);
        drawCarriage(pose, buffers, carriage, light);

        // Lid: leaning behind, tied over the mouth when sealed (linen) or held for recovery (dull red), fitted when finishing.
        JobState state = view.state();
        if (state == JobState.SEALED || state == JobState.RECOVERY) {
            cover(pose, buffers, RenderBits.cloth(), state == JobState.RECOVERY ? 0xFF9C4A40 : 0xFFD8C8A4, light);
        } else if (view.finishing().getBoolean("closed")) {
            cover(pose, buffers, RenderBits.MaterialKind.WOOD.sprite(), 0xFFFFFFFF, light);
        } else {
            pose.pushPose();
            pose.translate(5.5 / 16, 9.6 / 16, 15.2 / 16);
            pose.mulPose(Axis.XP.rotationDegrees(-78));
            WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-2.5,-0.25,0,2.5,0.25,5.4,light);
            WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-0.5,0.25,2.2,0.5,0.9,3.2,light);
            pose.popPose();
        }

        // The recipe page on the book stand, and the by-products in their basket.
        drawBook(pose, buffers, level, view, light, time, far);
        for (int i = 0; i < BrewersKettleBlockEntity.BYPRODUCT_SIZE; i++) {
            WorkshopRenderBits.item(pose, buffers, level, be.inventory().getStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i), 1.2, 4.6, 12.4 + i * 1.1,
                    0.12F, 0, 90, light, 130 + i);
        }

        // Everything moving through the scene: lifted, carried, tipping, pouring, returning, settling.
        drawTransfers(be, view, pose, buffers, level, surface, (float) surfaceY, light, motion);
    }

    private static int finishingPortion(WorkshopTargets.View view, int slot) {
        byte[] slots = view.finishing().getByteArray("slots");
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }

    /** The liquid's colour: the reaction's stage colours as it progresses, toward a presentation profile's tint if any. */
    private static int liquidColor(WorkshopTargets.View view, CompoundTag k, PresentationProfiles.Profile profile) {
        int[] colors = view.snapshot().getIntArray("stageColors");
        int doses = k.getByte("doses");
        double progress = k.getByte("progress") / 100.0;
        int from = colors.length == 0 ? 0x385DC6 : colors[Math.max(0, Math.min(colors.length - 1, doses - 1 < 0 ? 0 : doses - 1))];
        int to = colors.length == 0 ? from : colors[Math.max(0, Math.min(colors.length - 1, doses))];
        int color = doses == 0 ? from : WorkshopRenderBits.mix(from, to, k.getByte("stage") == KettleProcess.Stage.REACTING.ordinal() ? progress : 1);
        if (profile.tinted() && doses > 0) color = WorkshopRenderBits.mix(color, profile.tint(), 0.35 * progress);
        return color;
    }

    // ---- the liquid ----

    /** The surface mesh: the height field over the interior, lighter on crests and darker in hollows. */
    private static void liquid(PoseStack pose, MultiBufferSource buffers, KettleSurface s, float y, int argb, int light, long time, float partialTick,
                               double motion, boolean flat) {
        VertexConsumer vc = buffers.getBuffer(RenderBits.translucent());
        TextureAtlasSprite sprite = RenderBits.water();
        Matrix4f m = pose.last().pose();
        float x0 = KettleSurface.x0(), x1 = KettleSurface.x1(), z0 = KettleSurface.z0(), z1 = KettleSurface.z1();
        float a = (argb >>> 24) / 255F, r = (argb >> 16 & 0xFF) / 255F, g = (argb >> 8 & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
        if (flat) {
            vertex(vc, m, sprite, x0, y, z0, r, g, b, a, 0, light);
            vertex(vc, m, sprite, x0, y, z1, r, g, b, a, 0, light);
            vertex(vc, m, sprite, x1, y, z1, r, g, b, a, 0, light);
            vertex(vc, m, sprite, x1, y, z0, r, g, b, a, 0, light);
            return;
        }
        int nx = KettleSurface.NX, nz = KettleSurface.NZ;
        for (int j = 0; j < nz - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                float xa = x0 + (x1 - x0) * i / (nx - 1), xb = x0 + (x1 - x0) * (i + 1) / (nx - 1);
                float za = z0 + (z1 - z0) * j / (nz - 1), zb = z0 + (z1 - z0) * (j + 1) / (nz - 1);
                vertex(vc, m, sprite, xa, y + s.height(i, j), za, r, g, b, a, s.height(i, j), light);
                vertex(vc, m, sprite, xa, y + s.height(i, j + 1), zb, r, g, b, a, s.height(i, j + 1), light);
                vertex(vc, m, sprite, xb, y + s.height(i + 1, j + 1), zb, r, g, b, a, s.height(i + 1, j + 1), light);
                vertex(vc, m, sprite, xb, y + s.height(i + 1, j), za, r, g, b, a, s.height(i + 1, j), light);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, TextureAtlasSprite sprite, float x, float y, float z, float r, float g, float b, float a,
                               float h, int light) {
        float shade = Mth.clamp(1 + h * 0.9F, 0.7F, 1.3F);
        vc.vertex(m, x / 16F, y / 16F, z / 16F).color(Math.min(1, r * shade), Math.min(1, g * shade), Math.min(1, b * shade), a)
                .uv(sprite.getU(x), sprite.getV(z)).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(-h * 0.4F, 1, 0).endVertex();
    }

    /**
     * The reaction family in the material itself (handoff 8.3): what the trouble is, where it is, and how it answers
     * the paddle and the heat. Counts are bounded; reduced motion freezes the patterns but keeps them.
     */
    private static void material(PoseStack pose, MultiBufferSource buffers, KettleSurface s, CompoundTag k, ReactionFamily family, float y, long time,
                                 float partialTick, double motion, int light, PresentationProfiles.Profile profile) {
        double trouble = k.getByte("trouble") / 100.0, heat = k.getByte("heatTrouble") / 100.0, mix = k.contains("mix") ? k.getByte("mix") / 100.0 : trouble;
        double circ = k.getByte("circ") / 100.0;
        float swirl = motion > 0 ? s.swirl() : 0;
        var signature = KettleSignatureVisuals.of(FAMILY_SIGNATURE[family.ordinal()]);
        float cx = WorkshopParts.KETTLE_CX, cz = WorkshopParts.KETTLE_CZ;
        TextureAtlasSprite plain = ApparatusBits.plain();
        switch (family) {
            case LIGHT_FOAM -> {
                // Froth gathers at turbulent contact and the hot edges; it thins as the folds slow and the heat eases.
                int n = (int) Math.round(Math.min(1, mix + heat) * 14);
                for (int i = 0; i < n; i++) {
                    double a = swirl * 0.6 + i * 2.399;
                    double r = 3.2 + (i % 3) * 0.5;
                    float fx = (float) (cx + Math.cos(a) * r * 0.95), fz = (float) (cz + Math.sin(a) * r * 1.1);
                    fx = Mth.clamp(fx, KettleSurface.x0() + 0.3F, KettleSurface.x1() - 0.3F);
                    fz = Mth.clamp(fz, KettleSurface.z0() + 0.3F, KettleSurface.z1() - 0.3F);
                    float sz = 0.35F + (i % 4) * 0.12F;
                    decal(pose,buffers,"foam",fx,y+s.heightAt(fx,fz)+0.035,fz,sz*1.7,
                            (int)(150+90*Math.min(1,mix+heat))<<24|profile.foam(),light);
                }
            }
            case DENSE_SEDIMENT -> {
                // Heavy grains on the floor under clearer liquid; a sweep along the bottom lifts them into the flow.
                int n = 6 + (int) Math.round(trouble * 12);
                for (int i = 0; i < n; i++) {
                    double a = swirl * 0.35 + i * 1.91;
                    double r = 0.8 + (i * 37 % 10) / 10.0 * 3.4;
                    float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.2);
                    float lift = (float) (1 - trouble) * (float) circ * 2.2F;
                    float fy = WorkshopParts.KETTLE_FLOOR + 0.12F + lift * ((i % 3) / 2F);
                    WorkshopRenderBits.box(pose, buffers, fx - 0.18, fy, fz - 0.18, fx + 0.18, fy + 0.16, fz + 0.18, 0xFF000000 | RenderBits.darken(signature.troubleColor(), 0.6F), light);
                }
            }
            case DELICATE_SHIMMER -> {
                // Thin coherent streaks along the flow; harsh treatment breaks them into ragged pieces.
                int n = 7;
                for (int i = 0; i < n; i++) {
                    double a = swirl * 0.5 + i * (Math.PI * 2 / n);
                    double r = 1.4 + (i % 3) * 0.9;
                    float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.2);
                    float len = (float) (1.6 * (1 - trouble) + 0.3);
                    float ang = (float) (a + Math.PI / 2 + trouble * Math.sin(i * 3.1) * 1.4);
                    streak(pose, buffers, plain, fx, y + s.heightAt(fx, fz) + 0.03F, fz, ang, len, 0.12F,
                            (int) (90 + 120 * (1 - trouble)) << 24 | signature.healthyColor(), light);
                }
            }
            case MAGMA_STREAKS -> {
                // Thick warm ribbons that soften as warmth reaches them and fold in as they are worked.
                int n = 3 + (int) Math.round(trouble * 4);
                for (int i = 0; i < n; i++) {
                    double a = swirl * 0.25 + i * 2.2;
                    float fx = (float) (cx + Math.cos(a) * 2.2), fz = (float) (cz + Math.sin(a) * 2.6);
                    float thick = (float) (0.2 + trouble * 0.35);
                    streak(pose, buffers, plain, fx, y + s.heightAt(fx, fz) + 0.02F, fz, (float) (a + 1.2), 2.2F, thick,
                            (int) (130 + 110 * trouble) << 24 | signature.troubleColor(), light);
                }
            }
            case SUSPENDED_BUBBLES -> {
                // Trapped pockets swell under the surface and break; calm movement and rest let them go.
                int n = 3 + (int) Math.round(trouble * 9);
                for (int i = 0; i < n; i++) {
                    double phase = ((time + partialTick) * 0.04 * motion + i * 0.37) % 1.0;
                    double a = i * 2.7 + swirl * 0.3;
                    float fx = (float) (cx + Math.cos(a) * (0.8 + i % 4)), fz = (float) (cz + Math.sin(a) * (1 + i % 3) * 1.2);
                    float r = (float) (0.15 + phase * 0.45 * (0.4 + trouble));
                    float by = y - 0.9F + (float) phase * 0.9F;
                    decal(pose,buffers,"bubble",fx,by,fz,r*1.6,0xB0D8EDF0,light);
                }
            }
            case DARK_WHORLS -> {
                // Dense dark clouds trail each addition and thin out along the flow.
                int n = (int) Math.round(trouble * 10);
                for (int i = 0; i < n; i++) {
                    double a = swirl * 0.8 + i * 0.62;
                    double r = 0.6 + i * 0.32;
                    float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.25);
                    float sz = (float) (0.4 + 0.25 * (i % 3));
                    decal(pose,buffers,"foam",fx,y+s.heightAt(fx,fz)+0.025,fz,sz*1.5,(int)(90+120*trouble)<<24|0x24182D,light);
                }
            }
            case ALIGNING_GLINTS -> {
                // Grains dissolve, then glints line up along the flow once agitation stops.
                int n = 10;
                double order = 1 - Math.min(1, mix * 1.5 + circ * 0.6);
                for (int i = 0; i < n; i++) {
                    double a = i * 0.628 + swirl * 0.4;
                    double r = 1 + (i % 4) * 0.8;
                    float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.2);
                    float ang = (float) (order * (a + Math.PI / 2) + (1 - order) * i * 1.3);
                    streak(pose, buffers, plain, fx, y + s.heightAt(fx, fz) + 0.04F, fz, ang, 0.45F, 0.09F, 0xD0FFF4C0, light);
                }
            }
            case FLOATING_MOTES -> {
                // Light flakes ride the surface and collect at the edges until gentle surface strokes draw them under.
                int n = (int) Math.round(trouble * 16) + 2;
                for (int i = 0; i < n; i++) {
                    double a = i * 2.399 + swirl * 0.2;
                    double edge = 0.55 + 0.4 * Math.min(1, trouble * 1.5);
                    float fx = (float) (cx + Math.cos(a) * WorkshopParts.KETTLE_HX * edge), fz = (float) (cz + Math.sin(a) * WorkshopParts.KETTLE_HZ * edge);
                    decal(pose,buffers,"flake",fx,y+s.heightAt(fx,fz)+0.035,fz,0.4,0xE0000000|signature.healthyColor(),light);
                }
            }
            case GENERIC -> { }
        }
        // The batch's presentation accents (bounded): glints riding the flow and motes on the surface.
        int glints = (int) Math.round(profile.glints() * 6), motes = (int) Math.round(profile.motes() * 8);
        for (int i = 0; i < glints; i++) {
            double a = i * 1.047 + swirl * 0.45;
            double r = 1.2 + (i % 3) * 0.9;
            float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.2);
            streak(pose, buffers, plain, fx, y + s.heightAt(fx, fz) + 0.05F, fz, (float) (a + Math.PI / 2), 0.35F, 0.07F, 0xB0FFF8D8, light);
        }
        for (int i = 0; i < motes; i++) {
            double a = i * 2.399 + swirl * 0.25 + 0.7;
            double r = 1.0 + (i % 4) * 0.8;
            float fx = (float) (cx + Math.cos(a) * r), fz = (float) (cz + Math.sin(a) * r * 1.2);
            ApparatusBits.clearBox(pose, buffers, plain, fx - 0.12, y + s.heightAt(fx, fz), fz - 0.12, fx + 0.12, y + s.heightAt(fx, fz) + 0.05,
                    fz + 0.12, 0xC0000000 | (signature.healthyColor() & 0xFFFFFF), light);
        }
        // Heat trouble over any family: a rising head of froth over the whole surface.
        if (heat > 0.08) {
            for(int i=0;i<(int)Math.round(heat*12);i++) {
                double fx=KettleSurface.x0()+.6+(i*1.73)%(KettleSurface.x1()-KettleSurface.x0()-1.2);
                double fz=KettleSurface.z0()+.6+(i*2.37)%(KettleSurface.z1()-KettleSurface.z0()-1.2);
                decal(pose,buffers,"foam",fx,y+s.heightAt((float)fx,(float)fz)+.065,fz,1.25,(int)(70+heat*140)<<24|0xF4F1E7,light);
            }
        }
    }

    private static void decal(PoseStack pose,MultiBufferSource buffers,String kind,double x,double y,double z,double radius,int argb,int light) {
        RenderBits.decalClipped(pose,buffers,kind,(float)(x-radius)/16F,(float)(z-radius)/16F,(float)(x+radius)/16F,(float)(z+radius)/16F,
                (float)y/16F,(KettleSurface.x0()+.02F)/16F,(KettleSurface.z0()+.02F)/16F,(KettleSurface.x1()-.02F)/16F,(KettleSurface.z1()-.02F)/16F,argb,light);
    }

    /** A thin flat streak on the surface: centre, angle (radians), length and width in model pixels. */
    private static void streak(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, float x, float y, float z, float angle, float length,
                               float width, int argb, int light) {
        pose.pushPose();
        pose.translate(x / 16F, y / 16F, z / 16F);
        pose.mulPose(Axis.YP.rotation(-angle));
        ApparatusBits.clearBox(pose, buffers, sprite, -length / 2, 0, -width / 2, length / 2, 0.05, width / 2, argb, light);
        pose.popPose();
    }

    /** Dose representatives riding the flow, shaped by their material, shrinking as the dose incorporates. */
    private static void fragments(PoseStack pose, MultiBufferSource buffers, Level level, KettleSurface s, float y, int light) {
        TextureAtlasSprite plain = ApparatusBits.plain();
        int seed = 0;
        for (KettleSurface.Fragment f : s.fragments) {
            if (f.remaining < 0.04F || f.material == null) continue;
            seed++;
            float depthY = Mth.lerp(f.depth, y + s.heightAt(f.x, f.z), WorkshopParts.KETTLE_FLOOR + 0.25F);
            float size = f.size * (0.35F + 0.65F * f.remaining);
            int color = 0xFF000000 | f.color;
            switch (f.material) {
                case CRYSTAL -> {
                    float g = 0.16F * size;
                    WorkshopRenderBits.box(pose, buffers, f.x - g, depthY, f.z - g, f.x + g, depthY + g * 1.4, f.z + g, color, light);
                    if (f.depth < 0.9F) streak(pose, buffers, plain, f.x, y + 0.03F, f.z, f.angle, 0.8F * f.remaining, 0.25F, 0x40FFFFFF, light);
                }
                case FIBROUS, GENERIC -> {
                    pose.pushPose();
                    pose.translate(f.x / 16F, depthY / 16F, f.z / 16F);
                    pose.mulPose(Axis.YP.rotation(f.angle));
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                    float sc = 0.075F * size;
                    pose.scale(sc, sc, sc);
                    Minecraft.getInstance().getItemRenderer().renderStatic(f.item, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, seed);
                    pose.popPose();
                    if (f.material == PresentationProfiles.Arrival.FIBROUS) {
                        streak(pose, buffers, plain, f.x, y + 0.025F, f.z, f.angle + 1.57F, 1.2F * f.remaining, 0.3F, 0x50000000 | f.color, light);
                    }
                }
                case PASTE -> streak(pose, buffers, plain, f.x, depthY + 0.05F, f.z, f.angle, 1.6F * size, 0.5F * size, 0xE0000000 | f.color, light);
                case LIQUID -> {
                    float r = 0.6F + (1 - f.remaining) * 2.2F;
                    decal(pose,buffers,"foam",f.x,y+.03F,f.z,r,(int)(160*f.remaining)<<24|f.color,light);
                }
                case PARTICULATE -> {
                    for (int i = 0; i < 3; i++) {
                        float ox = (float) Math.cos(f.angle + i * 2.1) * 0.5F, oz = (float) Math.sin(f.angle + i * 2.1) * 0.5F;
                        ApparatusBits.clearBox(pose, buffers, plain, f.x + ox - 0.12, depthY, f.z + oz - 0.12, f.x + ox + 0.12, depthY + 0.05,
                                f.z + oz + 0.12, 0xE0000000 | f.color, light);
                    }
                }
            }
        }
    }

    /** Heat shows first in the flame, then as bubbles breaking at the surface (the height field carries their rings). */
    private static void renderHeat(PoseStack pose, MultiBufferSource buffers, KettleSurface s, int band, float y, long time, float partialTick,
                                   double motion, int light, double bubbles) {
        if (band < 1) return;
        // A presentation profile's bubbles (0..1, 0.5 by default) scale how many break at once.
        int rings = (int) Math.round(band * 2 * (0.5 + bubbles));
        double t = (time + partialTick) * 0.25 * motion;
        for (int i = 0; i < rings; i++) {
            double phase = (t + i * 1.7) % 4.0 / 4.0;
            float x = (float) (KettleSurface.x0() + 1 + ((i * 53) % 60) / 10.0), z = (float) (KettleSurface.z0() + 1 + ((i * 29) % 80) / 10.0);
            double r = 0.15 + phase * (0.25 + band * 0.12);
            int alpha = (int) ((1 - phase) * (60 + band * 25));
            decal(pose,buffers,"bubble",x,y+s.heightAt(x,z)+0.045,z,r*1.5,alpha<<24|0xDCECEE,light);
        }
    }

    // ---- tools, cups, controls ----

    /** A paddle as an object: its shaft and blade (the item's own model for a paddle this does not know). */
    private static void drawPaddle(PoseStack pose, MultiBufferSource buffers, Level level, ItemStack paddle, Vec3 tip, Vec3 shaft, int light) {
        boolean iron = paddle.is(ModItems.IRON_BREWING_PADDLE.get());
        boolean wooden = paddle.is(ModItems.WOODEN_BREWING_PADDLE.get());
        pose.pushPose();
        pose.translate(tip.x / 16, tip.y / 16, tip.z / 16);
        pose.mulPose(new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 1, 0), new org.joml.Vector3f((float) shaft.x, (float) shaft.y, (float) shaft.z)));
        if (!iron && !wooden) {
            pose.translate(0, 6 / 16F, 0);
            pose.scale(0.55F, 0.55F, 0.55F);
            Minecraft.getInstance().getItemRenderer().renderStatic(paddle, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, 7);
            pose.popPose();
            return;
        }
        RenderBits.MaterialKind blade = iron ? RenderBits.MaterialKind.STEEL : RenderBits.MaterialKind.WOOD;
        // The same broad, stepped mixing blade and wooden handle as the inventory sprite.
        WorkshopRenderBits.material(pose,buffers,blade,-0.9,0,-0.22,0.9,0.65,0.22,light);
        WorkshopRenderBits.material(pose,buffers,blade,-1.25,0.65,-0.22,1.25,3.5,0.22,light);
        WorkshopRenderBits.material(pose,buffers,blade,-0.9,3.5,-0.22,0.9,4.15,0.22,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-0.4,4.15,-0.4,0.4,13.0,0.4,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-0.48,9.8,-0.48,0.48,12.2,0.48,light);
        pose.popPose();
    }

    /** A pale ceramic dose cup with its dose (until it has been tipped). */
    private static void drawCup(PoseStack pose, MultiBufferSource buffers, Level level, ItemStack dose, double cx, double y, double cz, boolean next, int light) {
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.CERAMIC,cx-0.62,y,cz-0.7,cx+0.62,y+1.0,cz+0.7,light);
        WorkshopRenderBits.box(pose, buffers, cx - 0.48, y + 1.0, cz - 0.56, cx + 0.48, y + 1.02, cz + 0.56, 0xFF7A6A58, light);
        if (!dose.isEmpty()) WorkshopRenderBits.item(pose, buffers, level, dose, cx, y + 1.08, cz, 0.1F, 0, 90, light, 80);
    }

    /** The burner dial: a knurled knob on a vertical spindle, its pointer, and the dial's steps (the ideal ones longer). */
    private static void drawBurner(PoseStack pose, MultiBufferSource buffers, CompoundTag k, int burner, int light) {
        double cx = 13.9, cz = 1.9, y = 4.3;
        int low = k.contains("idealLow") ? (int) Math.round(k.getByte("idealLow") / 100.0 * KettleProcess.BURNER_STEPS) : -1;
        int high = k.contains("idealHigh") ? (int) Math.round(k.getByte("idealHigh") / 100.0 * KettleProcess.BURNER_STEPS) : -1;
        for (int s = 0; s <= KettleProcess.BURNER_STEPS; s++) {
            double a = Math.toRadians(-135 + s * (270.0 / KettleProcess.BURNER_STEPS));
            boolean ideal = s >= low && s <= high && low >= 0;
            double r0 = 1.05, r1 = ideal ? 1.45 : 1.3;
            double mx = cx + Math.sin(a) * (r0 + r1) / 2, mz = cz - Math.cos(a) * (r0 + r1) / 2;
            WorkshopRenderBits.box(pose, buffers, mx - 0.07, y, mz - 0.07, mx + 0.07, y + 0.05, mz + 0.07, ideal ? 0xFFE8D7A0 : 0xFF3A322C, light);
        }
        pose.pushPose();
        pose.translate(cx / 16, y / 16, cz / 16);
        pose.mulPose(Axis.YP.rotationDegrees(-(-135F + burner * (270F / KettleProcess.BURNER_STEPS))));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.IRON,-0.8,0,-0.8,0.8,1.0,0.8,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.COPPER,-0.15,1.0,-0.95,0.15,1.25,0.1,light);
        pose.popPose();
    }

    /** Flame tongues at the grate: immediate on the burner (the liquid follows with its thermal delay). */
    private static void drawFlame(PoseStack pose, MultiBufferSource buffers, float flame, long time, float partialTick, double motion, float yaw) {
        if (flame < 0.03F) return;
        flame = Mth.clamp(flame, 0, 1);
        float t = (time + partialTick) * 0.22F;
        for (int i = 0; i < 5; i++) {
            float x = 5.7F + i * .9F;
            float flicker = 1 + (float) motion * (.065F * Mth.sin(t + i * 1.9F) + .025F * Mth.sin(t * 1.7F + i));
            float height = (.55F + flame * 2.45F) * (i % 2 == 0 ? .91F : 1) * flicker;
            // Keep a clean silhouette from every facing; crossed flame planes turn into stars in the overhead view.
            pose.pushPose();
            pose.translate(x / 16F, 4.16F / 16F, 2F / 16F);
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            RenderBits.uprightPlane(pose,buffers,"flame",(1.5F+flame*.3F)/16F,height/16F,0xFFFFFFFF,0xF000F0);
            pose.popPose();
        }
    }

    /** The spout's carriage on its pipe, its arm forward over the rail and the nozzle that pours into a bottle's mouth. */
    private static void drawCarriage(PoseStack pose, MultiBufferSource buffers, float x, int light) {
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.COPPER, x - 0.6, 8.9, 0.5, x + 0.6, 10.2, 1.9, light);
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.COPPER, x - 0.25, 9.45, -1.4, x + 0.25, 9.8, 0.5, light);
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.COPPER, x - 0.3, WorkshopParts.SPOUT_OUTLET_Y, -1.45, x + 0.3, 9.45, -0.75, light);
    }

    /** A cover over the open mouth, following the rim from the back down to the lowered near side. */
    private static void cover(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, int argb, int light) {
        VertexConsumer vc = buffers.getBuffer(RenderBits.translucent());
        Matrix4f m = pose.last().pose();
        float a = (argb >>> 24) / 255F, r = (argb >> 16 & 0xFF) / 255F, g = (argb >> 8 & 0xFF) / 255F, b = (argb & 0xFF) / 255F;
        // Panels follow the rolled lip, rising just before each step instead of cutting through its exposed faces.
        for (int segment = 0; segment < COVER_Z.length - 1; segment++) {
            float dy = COVER_Y[segment + 1] - COVER_Y[segment], dz = COVER_Z[segment + 1] - COVER_Z[segment];
            float length = Mth.sqrt(dy * dy + dz * dz);
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 4; i++) {
                    int at = pass == 0 ? i : 3 - i;
                    float x = at < 2 ? 2.2F : 11.8F;
                    int end = segment + (at == 1 || at == 2 ? 1 : 0);
                    float sign = pass == 0 ? 1 : -1;
                    vc.vertex(m, x / 16F, COVER_Y[end] / 16F, COVER_Z[end] / 16F).color(r, g, b, a)
                            .uv(sprite.getU(x), sprite.getV(COVER_Z[end])).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                            .normal(pose.last().normal(), 0, sign * dz / length, -sign * dy / length).endVertex();
                }
            }
        }
    }

    // ---- the recipe page (handoff 6.2: the batch a tipped bottle starts, in icons first and words on hover) ----

    private static void drawBook(PoseStack pose, MultiBufferSource buffers, Level level, WorkshopTargets.View view, int light, long time, boolean far) {
        pose.pushPose();
        // The stand sits half a pixel further back than the page's coordinates below (clear of the dose tray's last row).
        pose.translate(0, 0, 0.5 / 16F);
        pose.translate(14 / 16F, 8.3 / 16F, 13.5 / 16F);
        pose.mulPose(Axis.XP.rotationDegrees(-22.5F));
        pose.translate(-14 / 16F, -8.3 / 16F, -13.5 / 16F);
        // The open book: cover, two pages and a spine.
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.WOOD, 12.4, 8.6, 11.8, 15.6, 8.75, 15.2, light);
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.PAPER, 12.55, 8.75, 11.95, 13.95, 8.95, 15.05, light);
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.PAPER, 14.05, 8.75, 11.95, 15.45, 8.95, 15.05, light);
        WorkshopRenderBits.material(pose, buffers, RenderBits.MaterialKind.WOOD, 13.95, 8.75, 11.95, 14.05, 8.85, 15.05, light);
        if (far) {
            // Too far to read: the open book without its icons and print.
            pose.popPose();
            return;
        }
        double top = 8.97;
        ItemStack output = view.state() == null ? WorkshopTargets.pageOutput(view) : WorkshopTargets.jobOutput(view);
        CompoundTag page = view.setup();
        // Pages turn toward the viewer: the right-hand page (viewer's right is low x) holds the potion, the left the steps.
        if (!output.isEmpty()) {
            WorkshopRenderBits.item(pose, buffers, level, output, 13.25, top, 14.1, 0.085F, 0, 90, light, 150);
            int servings = view.state() == null ? page.getByte("servings") : view.kettle().getByteArray("portions").length;
            ApparatusBits.text(pose, buffers, Component.literal("×" + servings), 13.25, top + 0.02, 12.9, 0.55F, 0xFF3A2A1A, true, light);
            boolean ready = view.state() != null || page.getBoolean("ready");
            ApparatusBits.text(pose, buffers, Component.literal(ready ? "✔" : "✘"), 13.25, top + 0.02, 12.25, 0.5F, ready ? 0xFF2E6B2E : 0xFF9A2A20, true, light);
        } else if (view.state() == null) {
            ApparatusBits.text(pose, buffers, Component.literal("?"), 13.25, top + 0.02, 13.6, 1.2F, 0xFF6A5A48, true, light);
        }
        ListTag ids = view.state() == null ? page.getList("steps", Tag.TAG_STRING) : view.snapshot().getList("doseItems", Tag.TAG_STRING);
        byte[] counts = page.getByteArray("counts");
        int done = view.state() == null ? -1 : view.kettle().contains("doses") ? view.kettle().getByte("doses") : view.finishing().getByte("doses");
        int rows = Math.min(3, ids.size());
        int first = done < 0 ? 0 : Math.min(Math.max(0,done-1),Math.max(0,ids.size()-rows));
        for (int row = 0; row < rows; row++) {
            int i = first + row;
            ResourceLocation id = ResourceLocation.tryParse(ids.getString(i));
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null) continue;
            double z = 14.8 - row * 0.9;
            WorkshopRenderBits.item(pose, buffers, level, new ItemStack(item), 15.0, top, z, 0.0625F, 0, 90, light, 160 + i);
            String note = done >= 0 ? (i < done ? "✔" : i == done ? "▶" : "") : "×" + (i < counts.length ? counts[i] : 1);
            ApparatusBits.text(pose, buffers, Component.literal(note), 14.3, top + 0.02, z - 0.15, 0.58F, 0xFF3A2A1A, true, light);
        }
        if (view.state() == null && page.contains("fuel") && page.getShort("fuel") > 0) {
            WorkshopRenderBits.item(pose, buffers, level, new ItemStack(Items.BLAZE_POWDER), 15.0, top, 11.85 + 0.3, 0.035F, 0, 90, light, 170);
            ApparatusBits.text(pose, buffers, Component.literal(String.valueOf(page.getShort("fuel"))), 14.45, top + 0.02, 12.0, 0.4F,
                    page.getShort("fuelHave") >= page.getShort("fuel") ? 0xFF3A2A1A : 0xFF9A2A20, true, light);
        }
        int pages = page.getByte("pages");
        if (view.state() == null && pages > 1) {
            ApparatusBits.text(pose, buffers, Component.literal((page.getByte("page") + 1) + "/" + pages), 14.0, top + 0.02, 11.55, 0.4F, 0xFF5A4A38, true, light);
        }
        pose.popPose();
    }

    // ---- what moves: transfers ----

    private static void drawTransfers(BrewersKettleBlockEntity be, WorkshopTargets.View view, PoseStack pose, MultiBufferSource buffers, Level level,
                                      KettleSurface surface, float surfaceY, int light, double motion) {
        var pos = be.getBlockPos();
        WorkshopTransfers.Transfer local = WorkshopTransfers.local();
        for (WorkshopTransfers.Transfer t : WorkshopTransfers.at(pos)) {
            if (t == local && t.pos.equals(pos)) WorkshopTransfers.followLocal(t, ClientWorkshop.carryX(), ClientWorkshop.carryZ(), 0);
            else WorkshopTransfers.pose(t);
            switch (t.kind) {
                case POUR -> drawPour(be, view, t, pose, buffers, level, surface, surfaceY, light);
                case DOSE -> drawDose(view, t, pose, buffers, level, surface, surfaceY, light);
                case FILL -> drawFill(be, view, t, pose, buffers, surfaceY, light);
                case PLACE -> drawPlace(be, t, pose, buffers, level, light);
            }
        }
    }

    private static void drawPour(BrewersKettleBlockEntity be, WorkshopTargets.View view, WorkshopTransfers.Transfer t, PoseStack pose, MultiBufferSource buffers,
                                 Level level, KettleSurface surface, float surfaceY, int light) {
        int slot = t.part - WorkshopParts.K_RACK;
        CompoundTag k = view.kettle();
        int portion = WorkshopTargets.portionAt(k, slot);
        ItemStack stack = portion >= 0 ? WorkshopTargets.portionItem(view, portion) : be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + slot);
        if (stack.isEmpty()) stack = PotionUtils.setPotion(new ItemStack(Items.POTION), net.minecraft.world.item.alchemy.Potions.WATER);
        float fill = t.committed ? 1 - t.flow() : 1;
        boolean emptied = t.committed && (t.phase == WorkshopTransfers.Phase.RETURN || t.phase == WorkshopTransfers.Phase.SETTLE);
        ItemStack shown = emptied ? new ItemStack(Items.GLASS_BOTTLE) : stack;
        ApparatusBits.bottle(pose, buffers, level, shown, t.x, t.y, t.z, emptied ? 0 : fill, !emptied && t.phase.ordinal() < WorkshopTransfers.Phase.TIP.ordinal(),
                t.tilt, 0, light);
        if (t.phase == WorkshopTransfers.Phase.FLOW && t.committed) {
            // The stream leaves the neck and lands where the liquid is; the surface rings from that point.
            Vec3 neck = neck(t, 4.0);
            surface.impact((float) neck.x, (float) neck.z + 0.4F);
            int color = PotionUtils.getColor(stack);
            WorkshopRenderBits.stream(pose, buffers, neck.x, neck.y, neck.z, surface.impactX(), surfaceY + 0.05, surface.impactZ(), 0xD8000000 | color, light);
            surface.impulse(surface.impactX(), surface.impactZ(), 0.05F, 0.9F);
        }
        if (WorkshopTransfers.showsHand(t, view)) {
            UUID holder = t.local ? (Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID()) : t.holder;
            if (holder != null) ApparatusBits.arm(pose, buffers, holder, objectPoint(t, 1.5, -0.9, 0.8), ARM_TOWARD, light);
        }
    }

    private static void drawDose(WorkshopTargets.View view, WorkshopTransfers.Transfer t, PoseStack pose, MultiBufferSource buffers, Level level,
                                 KettleSurface surface, float surfaceY, int light) {
        int step = t.part - WorkshopParts.K_CUP;
        ItemStack dose = WorkshopTargets.doseItem(view, step);
        boolean emptied = t.committed && t.phase.ordinal() >= WorkshopTransfers.Phase.FLOW.ordinal();
        pose.pushPose();
        pose.translate(t.x / 16F, t.y / 16F, t.z / 16F);
        if (t.tilt != 0) {
            pose.translate(0, 0, 0.7F / 16F);
            pose.mulPose(Axis.XP.rotationDegrees(t.tilt));
            pose.translate(0, 0, -0.7F / 16F);
        }
        drawCup(pose, buffers, level, emptied ? ItemStack.EMPTY : dose, 0, 0, 0, true, light);
        pose.popPose();
        if (t.phase == WorkshopTransfers.Phase.FLOW && t.committed) {
            // The material leaves the lip as itself: grains, pieces, a dollop, a stream, a cluster of flakes. A dose the
            // reagent table does not know arrives as the batch's presentation profile says, if it names a family.
            PresentationProfiles.Profile profile = PresentationProfiles.forStation(view.be().getBlockPos());
            PresentationProfiles.Reagent reagent = PresentationProfiles.reagent(dose);
            if (reagent.material() == PresentationProfiles.Arrival.GENERIC && profile != null && profile.material() != PresentationProfiles.Arrival.GENERIC) {
                reagent = new PresentationProfiles.Reagent(profile.material(), reagent.color(), reagent.size());
            }
            Vec3 lip = objectPoint(t, 1.0, 0.7, 0.7);
            surface.impact((float) lip.x, (float) lip.z + 0.5F);
            if (!t.spawned) {
                t.spawned = true;
                int count = switch (reagent.material()) {
                    case CRYSTAL -> 6;
                    case FIBROUS, GENERIC -> 3;
                    case PASTE -> 2;
                    case LIQUID -> 1;
                    case PARTICULATE -> 4;
                };
                if (profile != null && profile.fragments() > 0) count = Math.min(KettleSurface.MAX_FRAGMENTS, profile.fragments() * count / 3);
                surface.addDose(profile != null && profile.fragmentItem() != null ? new ItemStack(profile.fragmentItem()) : dose, reagent, count,
                        profile == null ? 1 : profile.fragmentScale());
            }
            float f = t.flow();
            if (reagent.material() == PresentationProfiles.Arrival.LIQUID) {
                WorkshopRenderBits.stream(pose, buffers, lip.x, lip.y, lip.z, surface.impactX(), surfaceY + 0.05, surface.impactZ(), 0xD0000000 | reagent.color(), light);
            } else {
                for (int i = 0; i < 4; i++) {
                    float p = (f * 2 + i * 0.25F) % 1F;
                    double y = Mth.lerp(p * p, (float) lip.y, surfaceY);
                    double x = Mth.lerp(p, (float) lip.x, surface.impactX()), z = Mth.lerp(p, (float) lip.z, surface.impactZ());
                    WorkshopRenderBits.box(pose, buffers, x - 0.12, y, z - 0.12, x + 0.12, y + 0.18, z + 0.12, 0xFF000000 | reagent.color(), light);
                }
            }
        }
        if (WorkshopTransfers.showsHand(t, view)) {
            UUID holder = t.local ? (Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID()) : t.holder;
            if (holder != null) ApparatusBits.arm(pose, buffers, holder, objectPoint(t, 0.8, -0.6, 0.7), ARM_TOWARD, light);
        }
    }

    private static void drawFill(BrewersKettleBlockEntity be, WorkshopTargets.View view, WorkshopTransfers.Transfer t, PoseStack pose, MultiBufferSource buffers,
                                 float surfaceY, int light) {
        if (t.phase != WorkshopTransfers.Phase.FLOW || !t.committed) return;
        float x = WorkshopTransfers.carriageX(be.getBlockPos());
        int[] colors = view.snapshot().getIntArray("stageColors");
        ItemStack inSlot = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + t.part - WorkshopParts.K_RACK);
        int color = !inSlot.isEmpty() && inSlot.getItem() instanceof net.minecraft.world.item.PotionItem ? PotionUtils.getColor(inSlot)
                : colors.length == 0 ? 0x385DC6 : colors[colors.length - 1];
        double mouth = WorkshopParts.RAIL_TOP + 4.25 + 0.6;
        WorkshopRenderBits.stream(pose, buffers, x, WorkshopParts.SPOUT_OUTLET_Y, WorkshopParts.RAIL_Z, x, mouth, WorkshopParts.RAIL_Z, 0xE0000000 | color, light);
    }

    private static void drawPlace(BrewersKettleBlockEntity be, WorkshopTransfers.Transfer t, PoseStack pose, MultiBufferSource buffers, Level level, int light) {
        int part = t.part;
        if (part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + 6) {
            ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + part - WorkshopParts.K_RACK);
            ApparatusBits.bottle(pose, buffers, level, s, t.x, t.y, t.z, s.is(Items.GLASS_BOTTLE) ? 0 : 1, !s.is(Items.GLASS_BOTTLE), 0, 0, light);
        } else if (part >= WorkshopParts.K_TRAY && part < WorkshopParts.K_TRAY + BrewersKettleBlockEntity.TRAY_SIZE) {
            ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + part - WorkshopParts.K_TRAY);
            WorkshopRenderBits.item(pose, buffers, level, s, t.x, t.y + 0.15, t.z, 0.13F, 0, 90, light, 100);
        } else if (part == WorkshopParts.K_FUEL) {
            WorkshopRenderBits.item(pose, buffers, level, be.inventory().getStackInSlot(BrewersKettleBlockEntity.FUEL), 3.0, t.y + 1, 1.9, 0.1F, 0, 90, light, 140);
        }
    }

    /** A point on a tipped object's upright axis (its neck or lip), after the tilt, in model pixels. */
    private static Vec3 neck(WorkshopTransfers.Transfer t, double height) {
        // The tip turns about the base's far edge (0.8 px toward the vessel), so the neck swings out over the liquid.
        return objectPoint(t, height, 0, 0.8);
    }

    private static Vec3 objectPoint(WorkshopTransfers.Transfer t, double height, double z, double pivot) {
        double a = Math.toRadians(t.tilt);
        double dy = height * Math.cos(a) - (z - pivot) * Math.sin(a);
        double dz = pivot + height * Math.sin(a) + (z - pivot) * Math.cos(a);
        return new Vec3(t.x, t.y + dy, t.z + dz);
    }

    private static Vec3 lean(Vec3 shaft, ToolMotion motion) {
        double ax = Math.toRadians(motion.leanX), az = Math.toRadians(motion.leanZ);
        Vec3 v = shaft.xRot((float) ax).zRot((float) az);
        return v.normalize();
    }

    private static Vec3 slerp(Vec3 a, Vec3 b, float t) {
        return a.scale(1 - t).add(b.scale(t)).normalize();
    }

    // ---- the legacy presentation (batches started before the hands-on overhaul) ----

    private void renderLegacy(BrewersKettleBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var mc = Minecraft.getInstance();
        ItemStack sample = ItemStack.EMPTY;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = be.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.isEmpty()) continue;
            if (sample.isEmpty()) sample = s;
            ApparatusBits.bottle(pose, buffers, be.getLevel(), s, WorkshopParts.railX(i), WorkshopParts.RAIL_TOP, WorkshopParts.RAIL_Z, 1, true, 0, 0, light);
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
            float level = Math.min(StationAnchors.KETTLE_BOWL.maxY(), StationAnchors.KETTLE_BOWL.minY() + servings * .045F);
            RenderBits.surface(pose, buffers, StationAnchors.KETTLE_BOWL.minX(), StationAnchors.KETTLE_BOWL.minZ(), StationAnchors.KETTLE_BOWL.maxX(),
                    StationAnchors.KETTLE_BOWL.maxZ(), level, 0xC0000000 | color, light);
            long additionAge = StationSoundManager.acknowledgementAge(be, StationEffectEvent.Kind.ADDITION);
            if (PresentationPolicy.current().animate() && additionAge >= 0 && additionAge < 250) {
                float radius = .025F + (additionAge / 250F) * .12F;
                float cx = WorkshopParts.KETTLE_CX / 16F, cz = WorkshopParts.KETTLE_CZ / 16F;
                RenderBits.decal(pose,buffers,"ripple",cx-radius,cz-radius,cx+radius,cz+radius,level+.004F,0xA0FFFFFF,light);
            }
            if (state == JobState.SEALED || state == JobState.RECOVERY) {
                cover(pose, buffers, RenderBits.cloth(), state == JobState.RECOVERY ? 0xFF9C4A40 : 0xFFD8C8A4, light);
            }
        }
        ItemStack paddle = be.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
        if (!paddle.isEmpty()) drawPaddle(pose, buffers, be.getLevel(), paddle, REST_TIP, REST_SHAFT, light);
    }

    @Override
    public boolean shouldRenderOffScreen(BrewersKettleBlockEntity be) {
        // Culled on the apparatus's real extent instead (the block entity's render box covers the rail and the paddle).
        return false;
    }

    @Override
    public int getViewDistance() {
        return 48;
    }
}
