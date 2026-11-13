package com.otectus.immersivebrewing.client.workshop;

import com.mojang.blaze3d.vertex.PoseStack;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.network.packet.WorkshopFocusS2C;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.network.packet.WorkshopSnapshotS2C;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The local player's hand at a station (handoff 4, 15): the focused station, the tool held (a server lease), a dose or
 * bottle carried (client-only until the server accepts the transfer), the predicted tool pose and the chosen effort.
 * Sends intents with a strictly increasing sequence, samples a moving tool at most twenty times a second, and never
 * shows a resource as moved before the server says so: a refused carry simply returns to its source.
 */
public final class ClientWorkshop {
    /** The local hand, as the target table reads it. */
    public record Hand(int tool, int carried) {}

    public static final double SAMPLE_INTERVAL_TICKS = 1;

    @Nullable
    private static BlockPos focus;
    @Nullable
    private static WorkshopKind kind;
    private static int tool = -1;
    private static int carried = -1;
    private static double u;
    private static double v;
    private static double shownU;
    private static double shownV;
    private static boolean contact;
    private static Effort effort = Effort.NORMAL;
    private static boolean batch;
    private static boolean working;
    private static double workAngle;
    private static int sequence;
    private static long lastSampleTick = Long.MIN_VALUE / 2;
    private static double sentU = Double.NaN;
    private static double sentV = Double.NaN;
    private static boolean sentContact;
    private static int toolSince;
    @Nullable
    private static BlockPos returnTo;
    @Nullable
    private static WorkshopKind returnKind;
    /** The final field of view the world was last drawn with (for picking). */
    private static double lastFov = 70;
    private static float cameraTargetYaw;
    private static float cameraTargetPitch;
    private static int cameraTicks;
    private static final Map<Long, long[]> LAST_FILL = new HashMap<>();

    // ---- focus lifecycle ----

    public static void onFocus(WorkshopFocusS2C message) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        WorkshopKind k = WorkshopKind.byId(message.kind());
        if (message.open() && k != null) {
            if (focus != null && !focus.equals(message.pos())) resetHand();
            focus = message.pos();
            kind = k;
            returnTo = null;
            if (!(mc.screen instanceof WorkshopScreen screen) || !screen.pos().equals(focus)) {
                mc.setScreen(new WorkshopScreen(focus, k));
                frame();
            }
            PracticeBench.tryStart();
        } else if (focus != null && focus.equals(message.pos())) {
            close(message.reason().isEmpty() ? null : Component.translatable(message.reason()));
        }
    }

    /** Ends the working view locally (the server is told by the screen's own close). */
    static void close(@Nullable Component reason) {
        var mc = Minecraft.getInstance();
        PracticeBench.stop(null);
        resetHand();
        focus = null;
        kind = null;
        if (mc.screen instanceof WorkshopScreen) mc.setScreen(null);
        if (reason != null && mc.player != null) com.otectus.immersivebrewing.client.StationNotices.show(reason, false);
    }

    public static void resetHand() {
        tool = -1;
        carried = -1;
        working = false;
        contact = false;
        sentU = Double.NaN;
        batch = false;
    }

    @Nullable
    public static BlockPos focus() {
        return focus;
    }

    @Nullable
    public static WorkshopKind kind() {
        return kind;
    }

    public static Hand hand() {
        return new Hand(tool, carried);
    }

    public static Effort effort() {
        return effort;
    }

    public static void cycleEffort() {
        effort = effort.next();
    }

    public static boolean batch() {
        return batch;
    }

    public static void toggleBatch() {
        batch = !batch;
    }

    public static boolean working() {
        return working;
    }

    public static void setWorking(boolean value) {
        working = value && tool >= 0;
        if (!working) {
            workCentreU = Double.NaN;
            workCentreV = Double.NaN;
        }
    }

    /** Remembers to return to the working view after the drawer or the book closes. */
    static void returnAfter(BlockPos pos, WorkshopKind k) {
        returnTo = pos;
        returnKind = k;
    }

    // ---- intents ----

    public static void send(int part, WorkshopAction action, int argument, boolean withBatch) {
        sendRaw(part, action, argument, (float) u, (float) v, contact, withBatch);
    }

    private static void sendRaw(int part, WorkshopAction action, int argument, float x, float z, boolean touching, boolean withBatch) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || focus == null) return;
        // A practice at this station takes every apparatus action locally; nothing about it reaches the server.
        if (PracticeBench.act(part, action, argument, x, z, touching, effort, withBatch)) return;
        BlockEntity be = mc.level.getBlockEntity(focus);
        UUID job = be instanceof StationBlockEntity station && station.workshopView().hasUUID("job")
                ? station.workshopView().getUUID("job") : WorkshopIntent.ANY_JOB;
        ModNetwork.sendToServer(new WorkshopIntentC2S(mc.level.dimension().location(), focus, job, ++sequence, part & 63, action.ordinal(),
                Math.max(-1, Math.min(4096, argument)), Mth.clamp(x, -1.5F, 1.5F), Mth.clamp(z, -1.5F, 1.5F),
                WorkshopIntentC2S.flags(touching, false, withBatch, effort.ordinal())));
    }

    // ---- the hand ----

    /** Takes a tool: the pose starts at its working centre; the server lease is requested at once. */
    public static void takeTool(int part) {
        tool = part;
        toolSince = 0;
        u = 0;
        v = 0;
        shownU = 0;
        shownV = 0;
        contact = false;
        sentU = Double.NaN;
        send(part, WorkshopAction.GRAB, 0, false);
        WorkshopTutorial.done(WorkshopTutorial.Stage.TOOL_TAKEN);
    }

    public static void putDown() {
        if (tool < 0) return;
        int part = tool;
        tool = -1;
        working = false;
        contact = false;
        send(part, WorkshopAction.RELEASE, 0, false);
        WorkshopTutorial.done(WorkshopTutorial.Stage.TOOL_PUT_DOWN);
    }

    public static void carry(int part) {
        carried = part;
    }

    public static void dropCarry() {
        carried = -1;
    }

    /** The carried dose or bottle is tipped: one transaction; the display returns to the server's truth either way. */
    public static void commitCarry(WorkshopAction action, int argument) {
        int source = carried;
        carried = -1;
        if (source >= 0) send(source, action, argument, false);
        if (action == WorkshopAction.DOSE) WorkshopTutorial.done(WorkshopTutorial.Stage.DOSE);
    }

    /** Sets the held tool's target on its work plane (normalised), from the mouse or the keys. */
    public static void moveTool(double nu, double nv, boolean inContact) {
        if (tool < 0) return;
        double dead = ClientConfig.get(ClientConfig.TOOL_DEAD_ZONE);
        if (Math.hypot(nu - u, nv - v) < dead) return;
        u = Mth.clamp(nu, -1, 1);
        v = Mth.clamp(nv, -1, 1);
        contact = inContact;
    }

    public static void nudgeTool(double du, double dv) {
        if (tool < 0) return;
        double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
        u = Mth.clamp(u + du * s, -1, 1);
        v = Mth.clamp(v + dv * s, -1, 1);
        contact = true;
    }

    public static double toolU() {
        return u;
    }

    public static double toolV() {
        return v;
    }

    /** The smoothed pose the renderers draw (visual weight only; the server receives the unsmoothed target). */
    public static double shownU() {
        return shownU;
    }

    public static double shownV() {
        return shownV;
    }

    public static boolean contact() {
        return contact;
    }

    public static int tool() {
        return tool;
    }

    public static int carried() {
        return carried;
    }

    /** Per client tick: the keyboard work action, sampling, smoothing, reconciliation and the exits. */
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            focus = null;
            return;
        }
        // Return to the working view when the drawer or the book closes and the station is still at hand.
        if (returnTo != null && mc.screen == null) {
            BlockPos back = returnTo;
            WorkshopKind k = returnKind;
            returnTo = null;
            if (k != null && mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(back)) < 7.5) {
                focus = back;
                kind = k;
                sendRaw(0, WorkshopAction.FOCUS, 0, 0, 0, false, false);
            }
        }
        if (cameraTicks > 0) turnCamera();
        if (focus == null) return;
        PracticeBench.tick();
        if (tool >= 0) {
            toolSince++;
            if (working) generateWork();
            double smoothing = ClientConfig.get(ClientConfig.TOOL_SMOOTHING);
            double follow = PresentationPolicy.current().decorativeMotion() <= 0 ? 1 : 1 - smoothing;
            shownU += (u - shownU) * follow;
            shownV += (v - shownV) * follow;
            boolean moved = Double.isNaN(sentU) || Math.abs(u - sentU) > 1e-3 || Math.abs(v - sentV) > 1e-3 || contact != sentContact;
            long now = mc.level.getGameTime();
            // One sample per tick while moving (20 a second at most); a resting tool sends a keep-alive twice a second.
            if (moved || now - lastSampleTick >= 10) {
                lastSampleTick = now;
                sentU = u;
                sentV = v;
                sentContact = contact;
                sendRaw(tool, WorkshopAction.SAMPLE, 0, (float) u, (float) v, contact, false);
            }
            // The server did not grant the lease (someone else holds the tool): let go locally.
            if (toolSince > 20 && !PracticeBench.active(focus) && mc.level.getBlockEntity(focus) instanceof StationBlockEntity station) {
                var view = WorkshopTargets.view(station, kind);
                UUID holder = view.holderId(tool);
                if (holder == null || !holder.equals(mc.player.getUUID())) {
                    tool = -1;
                    working = false;
                }
            }
        }
    }

    /** The accessible work action: bounded tool movement through the same samples a mouse produces (handoff 7). */
    private static void generateWork() {
        // Target speeds in model pixels per second, the same range a comfortable mouse stroke produces.
        double px = switch (effort) {
            case GENTLE -> 10;
            case NORMAL -> 17;
            case FIRM -> 27;
        };
        if (kind == WorkshopKind.KETTLE) {
            double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
            workAngle += px / radiusPx / 20.0;
            u = Math.cos(workAngle) * radius;
            v = Math.sin(workAngle) * radius;
        } else if (tool == WorkshopParts.M_SCRAPER) {
            double radius = 0.8;
            workAngle += Math.min(px, 14) / (radius * WorkshopParts.MORTAR_R) / 20.0;
            u = Math.cos(workAngle) * radius;
            v = Math.sin(workAngle) * radius;
        } else {
            // Short grinding arcs around wherever the pestle was placed (move it with the arrow keys).
            if (Double.isNaN(workCentreU)) {
                workCentreU = Mth.clamp(u, -0.7, 0.7);
                workCentreV = Mth.clamp(v, -0.7, 0.7);
            }
            double radius = 0.3;
            workAngle += px / (radius * WorkshopParts.MORTAR_R) / 20.0;
            u = Mth.clamp(workCentreU + Math.cos(workAngle) * radius, -1, 1);
            v = Mth.clamp(workCentreV + Math.sin(workAngle) * radius, -1, 1);
        }
        contact = true;
    }

    private static double workCentreU = Double.NaN;
    private static double workCentreV = Double.NaN;

    /** The arrow keys move the centre of a running grinding arc instead of fighting the arc itself. */
    public static void moveWorkCentre(double du, double dv) {
        if (Double.isNaN(workCentreU)) return;
        double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
        workCentreU = Mth.clamp(workCentreU + du * s, -0.75, 0.75);
        workCentreV = Mth.clamp(workCentreV + dv * s, -0.75, 0.75);
    }

    // ---- snapshots and effects ----

    public static void onSnapshot(WorkshopSnapshotS2C message) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.isLoaded(message.pos())) return;
        if (level.getBlockEntity(message.pos()) instanceof StationBlockEntity station) station.acceptWorkshopSnapshot(message.revision(), message.snapshot());
    }

    public static void onEffect(StationEffectEvent event) {
        if (event.kind() == StationEffectEvent.Kind.FILL || event.kind() == StationEffectEvent.Kind.POUR
                || event.kind() == StationEffectEvent.Kind.ADDITION) {
            LAST_FILL.put(event.pos().asLong(), new long[] {event.kind().ordinal(), event.index(), net.minecraft.Util.getMillis()});
            if (LAST_FILL.size() > 64) LAST_FILL.clear();
        }
        if (event.kind() == StationEffectEvent.Kind.VALVE && focus != null && focus.equals(event.pos())) WorkshopTutorial.done(WorkshopTutorial.Stage.BURNER);
    }

    /** The most recent transfer at a station: {kind ordinal, index, millis}, or null. */
    @Nullable
    public static long[] lastTransfer(BlockPos pos) {
        return LAST_FILL.get(pos.asLong());
    }

    public static void clear() {
        PracticeBench.clear();
        focus = null;
        kind = null;
        returnTo = null;
        resetHand();
        LAST_FILL.clear();
    }

    // ---- camera (handoff 4: optional comfortable framing; never through walls, never away from the eyes) ----

    /** Turns the view toward the apparatus when the focus camera is chosen; reduced motion makes it instant. */
    private static void frame() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || focus == null || ClientConfig.get(ClientConfig.CAMERA_MODE) != ClientConfig.CameraMode.FOCUS) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = Vec3.atCenterOf(focus).add(0, 0.15, 0);
        Vec3 d = target.subtract(eye);
        cameraTargetYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F;
        cameraTargetPitch = Mth.clamp((float) (-(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG)), -80F, 80F);
        boolean instant = ClientConfig.get(ClientConfig.REDUCED_MOTION) || PresentationPolicy.current().decorativeMotion() <= 0;
        cameraTicks = instant ? 1 : 6;
    }

    /** Turns toward another station (the explicit Next station action); the player then uses it normally. */
    static void lookAt(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 d = Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition());
        cameraTargetYaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F;
        cameraTargetPitch = Mth.clamp((float) (-(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG)), -80F, 80F);
        cameraTicks = ClientConfig.get(ClientConfig.REDUCED_MOTION) ? 1 : 6;
    }

    private static void turnCamera() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            cameraTicks = 0;
            return;
        }
        float f = 1F / cameraTicks;
        float yaw = player.getYRot() + Mth.wrapDegrees(cameraTargetYaw - player.getYRot()) * f;
        float pitch = player.getXRot() + (cameraTargetPitch - player.getXRot()) * f;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        cameraTicks--;
    }

    /** Records the world's field of view for picking, and narrows it slightly in the focus camera. */
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        if (focus != null && Minecraft.getInstance().screen instanceof WorkshopScreen && ClientConfig.get(ClientConfig.CAMERA_MODE) == ClientConfig.CameraMode.FOCUS
                && !ClientConfig.get(ClientConfig.REDUCED_MOTION)) {
            event.setFOV(event.getFOV() * 0.86);
        }
        lastFov = event.getFOV();
    }

    public static double fov() {
        return lastFov;
    }

    // ---- picking ----

    /** The world ray under a screen point, from the camera the world was drawn with. */
    static Vec3[] ray(double mouseX, double mouseY, int width, int height) {
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();
        double tanHalf = Math.tan(Math.toRadians(lastFov) / 2);
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        double nx = 2.0 * mouseX / Math.max(1, width) - 1, ny = 1 - 2.0 * mouseY / Math.max(1, height);
        Vec3 dir = new Vec3(look.x() + up.x() * ny * tanHalf - left.x() * nx * tanHalf * aspect,
                look.y() + up.y() * ny * tanHalf - left.y() * nx * tanHalf * aspect,
                look.z() + up.z() * ny * tanHalf - left.z() * nx * tanHalf * aspect).normalize();
        return new Vec3[] {origin, dir};
    }

    /** A world point to screen coordinates (GUI scale), or null when it is behind the camera. */
    @Nullable
    static double[] project(Vec3 world, int width, int height) {
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 d = world.subtract(camera.getPosition());
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        var left = camera.getLeftVector();
        double z = d.x * look.x() + d.y * look.y() + d.z * look.z();
        if (z <= 0.05) return null;
        double x = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
        double y = d.x * up.x() + d.y * up.y() + d.z * up.z();
        double tanHalf = Math.tan(Math.toRadians(lastFov) / 2);
        double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        return new double[] {(x / (z * tanHalf * aspect) + 1) / 2 * width, (1 - y / (z * tanHalf)) / 2 * height};
    }

    /**
     * The volume the client picks and outlines for a part: tool rests follow the left-handed presentation, everything
     * else (and every part id the server receives) is unchanged.
     */
    static WorkshopPart pickVolume(WorkshopPart part) {
        if (part.role() != WorkshopPart.Role.TOOL && part.role() != WorkshopPart.Role.LID || !ClientConfig.get(ClientConfig.MIRROR_TOOLS)) return part;
        return new WorkshopPart(part.id(), part.name(), part.role(), 16 - part.x1(), part.y0(), part.z0(), 16 - part.x0(), part.y1(), part.z1());
    }

    static Direction facing(BlockEntity be) {
        return be.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING) ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    // ---- outlines (handoff 4: a subtle outline distinguishes available from disabled parts) ----

    public static void renderOutlines(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var mc = Minecraft.getInstance();
        if (focus == null || !(mc.screen instanceof WorkshopScreen screen) || mc.level == null) return;
        BlockEntity be = mc.level.getBlockEntity(focus);
        if (!(be instanceof StationBlockEntity) && !(be instanceof ApothecaryRackBlockEntity)) return;
        WorkshopTargets.Target target = screen.target();
        if (target == null) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        AABB box = WorkshopGeometry.worldBox(focus, facing(be), pickVolume(target.part())).inflate(0.002).move(-cam.x, -cam.y, -cam.z);
        var palette = ClientConfig.get(ClientConfig.PALETTE);
        float r, g, b;
        if (palette == ClientConfig.Palette.HIGH_CONTRAST) {
            r = target.available() ? 1F : 0.6F;
            g = target.available() ? 0.95F : 0.6F;
            b = target.available() ? 0.1F : 0.6F;
        } else {
            r = target.available() ? 0.95F : 0.55F;
            g = target.available() ? 0.92F : 0.52F;
            b = target.available() ? 0.78F : 0.50F;
        }
        float alpha = target.available() ? 0.85F : 0.45F;
        var buffers = mc.renderBuffers().bufferSource();
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box, r, g, b, alpha);
        if (PresentationPolicy.current().markerThickness() > 1) {
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box.inflate(0.006), r, g, b, alpha);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** Work-plane conversion: a world ray to normalised coordinates over the vessel or bowl, and whether it is over it. */
    @Nullable
    static double[] planePoint(BlockEntity be, Vec3 origin, Vec3 dir) {
        Direction facing = facing(be);
        if (kind == WorkshopKind.KETTLE) {
            Vec3 p = WorkshopGeometry.onPlane(focus, facing, origin, dir, WorkshopParts.KETTLE_LIQUID_MAX - 0.5);
            if (p == null) return null;
            double nu = (p.x - WorkshopParts.KETTLE_CX) / WorkshopParts.KETTLE_HX, nv = (p.z - WorkshopParts.KETTLE_CZ) / WorkshopParts.KETTLE_HZ;
            return new double[] {nu, nv, Math.abs(nu) <= 1 && Math.abs(nv) <= 1 ? 1 : 0};
        }
        if (kind == WorkshopKind.MORTAR) {
            Vec3 p = WorkshopGeometry.onPlane(focus, facing, origin, dir, WorkshopParts.MORTAR_FLOOR + 0.8);
            if (p == null) return null;
            double nu = (p.x - WorkshopParts.MORTAR_CX) / WorkshopParts.MORTAR_R, nv = (p.z - WorkshopParts.MORTAR_CZ) / WorkshopParts.MORTAR_R;
            return new double[] {nu, nv, nu * nu + nv * nv <= 1.0 ? 1 : 0};
        }
        return null;
    }

    private ClientWorkshop() {}
}
