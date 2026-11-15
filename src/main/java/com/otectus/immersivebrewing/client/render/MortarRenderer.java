package com.otectus.immersivebrewing.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.client.presentation.ToolMotion;
import com.otectus.immersivebrewing.client.sound.StationSoundManager;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.otectus.immersivebrewing.client.render.WorkshopRenderBits.mirror;

/**
 * The Mortar in the world (handoff 9.1): the reagent in the bowl as its material, worked where the tool is. Five zones
 * (the server's) set how far each part of the bowl is processed; within them the pieces are the family's own: shards
 * that crack and spread (crystalline), strands that shorten and mat (fibrous), pieces that flatten into pulp (soft),
 * flakes that break fast (brittle), streaks that blend and froth (liquid). A press lands as a dent where the pestle is,
 * grinding leaves a fading track, the scraper sweeps the rim's paste back in, and finishing gathers the material into
 * the dose cup. Cosmetic and bounded: the zone model stays the truth.
 */
public class MortarRenderer implements BlockEntityRenderer<ApothecaryMortarBlockEntity> {
    private static final int[] FAMILY_COLOR = {0xFFE0E3E8, 0xFFC9A66E, 0xFFC98272, 0xFFE2D8AD, 0xFF8CB9C9};
    private static final Vec3 PESTLE_REST = new Vec3(11.6, 5.6, 11.6), PESTLE_REST_AXIS = new Vec3(0.18, 1, 0.18).normalize();
    private static final Vec3 ARM_TOWARD = new Vec3(-0.5, 0.22, -1).normalize();
    /** Recent pestle contact points per mortar: the grinding track (bounded ring). */
    private static final int TRACK = 24;
    private static final class Track {
        final float[] x = new float[TRACK], z = new float[TRACK];
        final long[] at = new long[TRACK];
        int head;
    }
    private record TrackKey(ResourceKey<Level> dimension, long pos, UUID incarnation, UUID job) {}
    private static final Map<TrackKey, Track> TRACKS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TrackKey, Track> eldest) {
            return size() > 32;
        }
    };

    public MortarRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ApothecaryMortarBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        RenderBits.face(pose, be.getBlockState().getValue(StationBlock.FACING));
        Level level = be.getLevel();
        double motion = PresentationPolicy.current().decorativeMotion();
        WorkshopTargets.View view = WorkshopTargets.view(be, WorkshopKind.MORTAR);
        CompoundTag m = view.mortar();
        BlockPos pos = be.getBlockPos();
        TrackKey trackKey = level == null ? null : new TrackKey(level.dimension(), pos.asLong(), be.visual().incarnation(), view.job());
        if (trackKey != null) TRACKS.keySet().removeIf(key -> key.dimension().equals(trackKey.dimension()) && key.pos() == trackKey.pos() && !key.equals(trackKey));
        ItemStack input = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT);
        ItemStack reagent = view.snapshot().contains("reagent") ? ItemStack.of(view.snapshot().getCompound("reagent")) : input;

        // The pestle first (the material answers where it is).
        double[] pestle = WorkshopRenderBits.toolPose(pos, view.snapshot(), WorkshopParts.M_PESTLE);
        long pressAge = StationSoundManager.acknowledgementAge(be, StationEffectEvent.Kind.PRESS);
        double press = PresentationPolicy.current().animate() && pressAge >= 0 && pressAge < 260 ? Math.sin(Math.PI * (pressAge / 260.0)) : 0;
        float px = (float) mirror(PESTLE_REST.x), py = (float) PESTLE_REST.y, pz = (float) PESTLE_REST.z;
        if (pestle != null) {
            px = (float) (WorkshopParts.MORTAR_CX + pestle[0] * WorkshopParts.MORTAR_R);
            pz = (float) (WorkshopParts.MORTAR_CZ + pestle[1] * WorkshopParts.MORTAR_R);
            double layer = m.contains("zones") ? materialLayer(m.getByteArray("zones"), Math.max(1, m.getByte("units")), px, pz)
                    : m.contains("processed") ? 0.3 + m.getByte("processed") / 100.0 * 0.5 : 0.3;
            py = (float) (pestle[2] > 0 ? WorkshopParts.MORTAR_FLOOR + Math.max(0.02, layer - press * 0.45)
                    : WorkshopParts.MORTAR_FLOOR + 2.6);
        }
        boolean localPestle = pos.equals(ClientWorkshop.focus()) && ClientWorkshop.tool() == WorkshopParts.M_PESTLE;
        ToolMotion pm = ToolMotion.of(pos, WorkshopParts.M_PESTLE);
        pm.update(pestle != null, px, py, pz, localPestle, motion);
        if (trackKey != null && pestle != null && pestle[2] > 0) track(trackKey, pm.x, pm.z);

        double[] scraper = WorkshopRenderBits.toolPose(pos, view.snapshot(), WorkshopParts.M_SCRAPER);
        boolean localScraper = pos.equals(ClientWorkshop.focus()) && ClientWorkshop.tool() == WorkshopParts.M_SCRAPER;
        ToolMotion sm = ToolMotion.of(pos, WorkshopParts.M_SCRAPER);
        float sx = (float) mirror(1.7), sy = 6.4F, sz = 1.8F;
        if (scraper != null) {
            sx = (float) (WorkshopParts.MORTAR_CX + scraper[0] * WorkshopParts.MORTAR_R);
            sz = (float) (WorkshopParts.MORTAR_CZ + scraper[1] * WorkshopParts.MORTAR_R);
            sy = (float) (scraper[2] > 0 ? WorkshopParts.MORTAR_FLOOR + 0.4 : WorkshopParts.MORTAR_FLOOR + 2.4);
        }
        sm.update(scraper != null, sx, sy, sz, localScraper, motion);

        if (m.contains("zones")) {
            material(pose, buffers, level, pos, trackKey, m, reagent, pm, pestle != null && pestle[2] > 0,
                    scraper != null && scraper[2] > 0, pressAge, light);
        } else if (!input.isEmpty()) {
            // Reagents set out in the bowl, not yet worked: whole pieces, one per unit placed.
            for (int i = 0; i < Math.min(ApothecaryMortarBlockEntity.BOWL_UNITS, input.getCount()); i++) {
                double a = i * 1.57 + 0.4;
                WorkshopRenderBits.item(pose, buffers, level, input, 8 + Math.cos(a) * 1.8, WorkshopParts.MORTAR_FLOOR + 0.3, 8 + Math.sin(a) * 1.8,
                        0.18F, (float) Math.toDegrees(a), 90, light, 2 + i);
            }
        } else if (view.state() != null && !reagent.isEmpty()) {
            WorkshopRenderBits.item(pose, buffers, level, reagent, 8, WorkshopParts.MORTAR_FLOOR + 0.3, 8, 0.2F, 0, 90, light, 9);
        }

        // Pestle: a stone head on a turned handle, upright in the hand (leaning into the stroke), leaning in its corner at rest.
        boolean mirrored = com.otectus.immersivebrewing.config.ClientConfig.get(com.otectus.immersivebrewing.config.ClientConfig.MIRROR_TOOLS);
        Vec3 axis = pestle != null ? lean(new Vec3(mirrored ? -0.12 : 0.12, 1, -0.35).normalize(), pm)
                : new Vec3(mirrored ? -PESTLE_REST_AXIS.x : PESTLE_REST_AXIS.x, PESTLE_REST_AXIS.y, PESTLE_REST_AXIS.z).normalize();
        drawPestle(pose, buffers, new Vec3(pm.x, pm.y, pm.z), axis, light);
        if (pestle != null) {
            UUID holder = holder(view, WorkshopParts.M_PESTLE, localPestle);
            ApparatusBits.arm(pose, buffers, holder, new Vec3(pm.x, pm.y, pm.z).add(axis.scale(8.6)), ARM_TOWARD, light);
        }

        // Scraper: a flat wooden blade, sweeping in the bowl or resting on its peg.
        pose.pushPose();
        pose.translate(sm.x / 16, sm.y / 16, sm.z / 16);
        float scraperYaw = scraper != null ? (float) Math.toDegrees(Math.atan2(sm.x - 8, sm.z - 8)) : 35;
        float scraperPitch = scraper != null ? -25 + sm.leanX : -60;
        pose.mulPose(Axis.YP.rotationDegrees(scraperYaw));
        pose.mulPose(Axis.XP.rotationDegrees(scraperPitch));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-1.1,0,-0.18,1.1,1.5,0.18,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-0.3,1.5,-0.25,0.3,6.0,0.25,light);
        pose.popPose();
        if (scraper != null) {
            UUID holder = holder(view, WorkshopParts.M_SCRAPER, localScraper);
            org.joml.Vector3f grip = new org.joml.Vector3f(0, 5.4F, 0)
                    .rotateX((float) Math.toRadians(scraperPitch)).rotateY((float) Math.toRadians(scraperYaw));
            ApparatusBits.arm(pose, buffers, holder, new Vec3(sm.x + grip.x, sm.y + grip.y, sm.z + grip.z), ARM_TOWARD, light);
        }

        // The dose cup on its ledge (the gathered material when finishing), and the kits on their shelf.
        long finishAge = StationSoundManager.acknowledgementAge(be, StationEffectEvent.Kind.COMPLETE);
        double gathered = finishAge >= 0 && finishAge < 900 ? Math.min(1, finishAge / 500.0) : m.contains("zones") ? 0 : -1;
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.CERAMIC,13.0,0.8,-1.0,15.2,2.6,1.2,light);
        WorkshopRenderBits.box(pose, buffers, 13.25, 2.6, -0.75, 14.95, 2.65, 0.95, 0xFF6A5640, light);
        if (gathered > 0) {
            int color = materialColor(reagent, Math.max(0, Math.min(FAMILY_COLOR.length - 1, m.getByte("family"))));
            WorkshopRenderBits.box(pose, buffers, 13.5, 2.1, -0.5, 14.7, 2.1 + 0.8 * gathered, 0.7, color, light);
        }
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            ItemStack kit = be.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
            if (kit.isEmpty()) continue;
            var spot = WorkshopParts.get(WorkshopKind.MORTAR, WorkshopParts.M_SHELF + i);
            WorkshopRenderBits.item(pose, buffers, level, kit, spot.cx(), 0.85, spot.cz(), 0.16F, 0, 90, light, 10 + i);
        }
        pose.popPose();
    }

    private static UUID holder(WorkshopTargets.View view, int part, boolean local) {
        UUID holder = view.holderId(part);
        if (holder == null && local && Minecraft.getInstance().player != null) holder = Minecraft.getInstance().player.getUUID();
        return holder;
    }

    /** The worked material: zone layers and the family's pieces, the press dent and the grinding track. */
    private static void material(PoseStack pose, MultiBufferSource buffers, Level level, BlockPos pos, TrackKey trackKey, CompoundTag m,
                                 ItemStack reagent, ToolMotion pestle,
                                 boolean contact, boolean scraperContact, long pressAge, int light) {
        byte[] zones = m.getByteArray("zones");
        byte[] fines = m.getByteArray("fines");
        int family = Math.max(0, Math.min(FAMILY_COLOR.length - 1, m.getByte("family")));
        int color = materialColor(reagent, family);
        int units = Math.max(1, m.getByte("units"));
        double clump = m.getByte("clump") / 100.0;
        float floor = WorkshopParts.MORTAR_FLOOR;
        WorkshopRenderBits.decal(pose,buffers,"foam",8,floor+0.02,8,5.3,color,light);
        for (int z = 0; z < MortarProcess.ZONES; z++) {
            double processed = z < zones.length ? zones[z] / 100.0 : 0;
            double dust = z < fines.length ? fines[z] / 100.0 : 0;
            double[] c = zoneCentre(z);
            double layer = 0.25 + processed * 0.5 * Math.min(1.5, 0.7 + units * 0.2);
            WorkshopRenderBits.box(pose, buffers, c[0] - 1.4, floor, c[1] - 1.4, c[0] + 1.4, floor + layer, c[1] + 1.4, RenderBits.darken(color & 0xFFFFFF, 0.92F) | 0xFF000000, light);
            int pieces = (int) Math.round((1 - processed) * (family == 3 ? 2 : 4));
            for (int i = 0; i < pieces; i++) {
                double a = i * 2.1 + z * 0.9;
                double fx = c[0] + Math.cos(a) * 0.75, fz = c[1] + Math.sin(a) * 0.75;
                double size = 0.3 + (1 - processed) * 0.45;
                double y = floor + layer;
                // Pieces near the pestle are pushed aside as it works.
                double dx = fx - pestle.x, dz = fz - pestle.z, d = Math.sqrt(dx * dx + dz * dz);
                if (contact && d < 1.4 && d > 1e-3) {
                    fx += dx / d * (1.4 - d) * 0.6;
                    fz += dz / d * (1.4 - d) * 0.6;
                }
                piece(pose, buffers, family, fx, y, fz, size, a, color, processed, light);
            }
            if (dust > 0.05) {
                String texture = family == 0 || family == 3 ? "shard" : family == 4 ? "bubble" : "foam";
                WorkshopRenderBits.decal(pose,buffers,texture,c[0],floor+layer+0.055,c[1],1.6,
                        (int)(80+dust*140)<<24|(color&0xFFFFFF),light);
            }
        }
        // The family's correctable mistake at the rim: paste creeping up (soft), matted strands (fibrous), froth (liquid).
        if (clump > 0.08) {
            int ring = family == 4 ? 0xF0F4F8 : color & 0xFFFFFF;
            int alpha = (int) (90 + clump * 150) << 24;
            // A scraper in contact clears the stretch of rim it passes.
            ToolMotion scraper = ToolMotion.of(pos, WorkshopParts.M_SCRAPER);
            boolean sweeping = scraperContact && scraper.held > 0.5F;
            double[][] sides = {{8, 3.9}, {8, 12.1}, {3.9, 8}, {12.1, 8}};
            for (int side = 0; side < 4; side++) {
                double cx = sides[side][0], cz = sides[side][1];
                if (sweeping && Math.hypot(scraper.x - cx, scraper.z - cz) < 2.2) continue;
                boolean alongX = side < 2;
                WorkshopRenderBits.box(pose, buffers, alongX ? 4.4 : cx - 0.25, floor + 1.4, alongX ? cz - 0.25 : 4.4, alongX ? 11.6 : cx + 0.25,
                        floor + 1.4 + clump * 1.3, alongX ? cz + 0.25 : 11.6, alpha | ring, light);
            }
        }
        // The press dent: where the pestle landed, a short compression ring.
        if (pressAge >= 0 && pressAge < 600) {
            boolean animate = PresentationPolicy.current().animate();
            float progress = animate ? pressAge / 600F : 0.25F;
            float r = 0.5F + progress * 0.9F;
            int alpha = animate ? (int) ((1 - progress) * 120) : 90;
            double y = floor + materialLayer(zones, units, pestle.x, pestle.z) + 0.055;
            WorkshopRenderBits.decal(pose, buffers, "ripple", pestle.x, y, pestle.z, r, alpha << 24 | 0x2A241C, light);
        }
        // The grinding track: recent contact points, fading.
        Track track = trackKey == null ? null : TRACKS.get(trackKey);
        if (track != null) {
            long now = Util.getMillis();
            for (int i = 0; i < TRACK; i++) {
                float tx = track.x[i], tz = track.z[i];
                long age = now - track.at[i];
                if (track.at[i] == 0 || age < 0 || age > 1500) continue;
                int alpha = (int) ((1 - age / 1500F) * 90);
                double y = floor + materialLayer(zones, units, tx, tz) + 0.06;
                WorkshopRenderBits.decal(pose,buffers,"ripple",tx,y,tz,0.55,alpha<<24|RenderBits.darken(color&0xFFFFFF,.65F),light);
            }
        }
        WorkshopRenderBits.item(pose, buffers, level, reagent, 2.6, 7.6, 2.9, 0.09F, 0, 0, light, 1);
    }

    private static double materialLayer(byte[] zones, int units, double x, double z) {
        int zone = MortarProcess.zoneAt((x - WorkshopParts.MORTAR_CX) / WorkshopParts.MORTAR_R,
                (z - WorkshopParts.MORTAR_CZ) / WorkshopParts.MORTAR_R);
        double processed = zone < zones.length ? zones[zone] / 100.0 : 0;
        return 0.25 + processed * 0.5 * Math.min(1.5, 0.7 + units * 0.2);
    }

    private static int materialColor(ItemStack reagent, int family) {
        int color=com.otectus.immersivebrewing.client.presentation.PresentationProfiles.reagent(reagent).color();
        return 0xFF000000|(color==0xB8A890?FAMILY_COLOR[family]&0xFFFFFF:color);
    }

    /** One piece of the material, by family. */
    private static void piece(PoseStack pose, MultiBufferSource buffers, int family, double x, double y, double z, double size, double angle, int color,
                              double processed, int light) {
        pose.pushPose();
        pose.translate(x / 16, y / 16, z / 16);
        pose.mulPose(Axis.YP.rotation((float) angle));
        switch (family) {
            case 0 -> { // crystalline: angular shards, tilted
                pose.mulPose(Axis.ZP.rotationDegrees(25));
                WorkshopRenderBits.box(pose, buffers, -size * 0.6, 0, -size * 0.4, size * 0.6, size * 0.9, size * 0.4, color | 0xFF000000, light);
            }
            case 1 -> // fibrous: strands that shorten as they are worked
                    WorkshopRenderBits.box(pose, buffers, -size * (1.6 - processed), 0, -0.08, size * (1.6 - processed), 0.16, 0.08, color | 0xFF000000, light);
            case 2 -> // soft: pieces flattening into pulp
                    WorkshopRenderBits.box(pose, buffers, -size * 0.8, 0, -size * 0.7, size * 0.8, size * 0.5 * (1 - processed * 0.6), size * 0.7,
                            color | 0xFF000000, light);
            case 3 -> // brittle: thin flakes
                    WorkshopRenderBits.box(pose, buffers, -size * 0.7, 0, -size * 0.5, size * 0.7, 0.08, size * 0.5, color | 0xFF000000, light);
            default -> // liquid: separate streaks on the surface that blend
                    WorkshopRenderBits.surface(pose, buffers, -size * 1.4, -0.12, size * 1.4, 0.12, 0.05, (int) (200 * (1 - processed * 0.8)) << 24 | (color & 0xFFFFFF), light);
        }
        pose.popPose();
    }

    private static void drawPestle(PoseStack pose, MultiBufferSource buffers, Vec3 tip, Vec3 axis, int light) {
        pose.pushPose();
        pose.translate(tip.x / 16, tip.y / 16, tip.z / 16);
        pose.mulPose(new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 1, 0), new org.joml.Vector3f((float) axis.x, (float) axis.y, (float) axis.z)));
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.PESTLE,-0.85,0,-0.85,0.85,1.4,0.85,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.PESTLE,-0.6,1.4,-0.6,0.6,2.0,0.6,light);
        WorkshopRenderBits.material(pose,buffers,RenderBits.MaterialKind.WOOD,-0.4,2.0,-0.4,0.4,9.4,0.4,light);
        pose.popPose();
    }

    private static Vec3 lean(Vec3 axis, ToolMotion motion) {
        return axis.xRot((float) Math.toRadians(motion.leanX)).zRot((float) Math.toRadians(motion.leanZ)).normalize();
    }

    /** Records a contact point of the pestle for the grinding track. */
    private static void track(TrackKey key, float x, float z) {
        Track t = TRACKS.computeIfAbsent(key, k -> new Track());
        int prev = Math.floorMod(t.head - 1, TRACK);
        if (t.at[prev] != 0 && Math.hypot(t.x[prev] - x, t.z[prev] - z) < 0.35) return;
        t.x[t.head] = x;
        t.z[t.head] = z;
        t.at[t.head] = Util.getMillis();
        t.head = (t.head + 1) % TRACK;
    }

    /** Drops presentation left by a removed/replaced station at this coordinate in any client dimension. */
    public static void remove(BlockPos pos) {
        TRACKS.keySet().removeIf(key -> key.pos() == pos.asLong());
    }

    /** Clears dimension/session presentation state; lifecycle wiring calls this alongside the other client caches. */
    public static void clearPresentationCache() {
        TRACKS.clear();
    }

    /** A zone's centre in model pixels (the same zones the server works). */
    static double[] zoneCentre(int zone) {
        if (zone == 0) return new double[] {WorkshopParts.MORTAR_CX, WorkshopParts.MORTAR_CZ};
        double angle = -Math.PI + (zone - 1 + 0.5) * Math.PI / 2;
        return new double[] {WorkshopParts.MORTAR_CX + Math.cos(angle) * 2.5, WorkshopParts.MORTAR_CZ + Math.sin(angle) * 2.5};
    }

    @Override
    public boolean shouldRenderOffScreen(ApothecaryMortarBlockEntity be) {
        return false;
    }
}
