package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.StationNotices;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.workshop.CameraFit;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopShots;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The close-up working camera (handoff 5): a client-only focus controller that owns the render camera's pose while the
 * player works at a station, and nothing else. The player's entity stays where it is, keeps its permissions and its
 * vulnerability; the server never hears of the camera and validates every action from the player's eyes.
 *
 * <pre>
 *   WORLD -> ENTERING -> WORKING <-> INSPECTING
 *              |            |            |
 *              +--------> LEAVING <------+ -> WORLD
 * </pre>
 *
 * <ul>
 * <li>The pose starts from wherever vanilla or a camera mod placed the camera this frame (first or third person) and
 * glides on real time to a station-relative rig fitted to the apparatus ({@link CameraFit}); leaving glides back to the
 * live ordinary camera. INSTANT cuts both ways; EYE never moves the camera.</li>
 * <li>The rig is checked against the blocks around it: the destination needs clearance for the near plane, the view to
 * the apparatus must be open, and the travel path must be clear or the move becomes a cut. When no authored alternative
 * is clear the view works from the eyes and says why, once.</li>
 * <li>Every interruption (the screen closing or replaced, death, a teleport, a dimension change, a disconnect, the
 * station removed, a resource reload, another camera entity) ends it; a hard reset leaves no override behind.</li>
 * <li>No roll, no shake, no bob of its own; the framing never moves while an action is under way (it only changes on an
 * explicit inspect or station change).</li>
 * </ul>
 */
public final class WorkshopCamera {
    public enum Phase { WORLD, ENTERING, WORKING, INSPECTING, LEAVING }

    /** Distances (blocks) the fitted rig may use, and the widest field of view a narrow window may get. */
    public static final double MIN_DISTANCE = 0.75, MAX_DISTANCE = 2.4, MAX_FOV = 62;
    /** Minimum bottom share of the frame; larger GUI scales also reserve room for status bars and guidance. */
    public static final double HUD_RESERVE = 0.11;
    /** Clearance the camera keeps from any block (the near plane plus a margin), blocks. */
    public static final double CLEARANCE = 0.16;
    private static final double ARC = 0.08;

    private static Phase phase = Phase.WORLD;
    @Nullable
    private static BlockPos pos;
    @Nullable
    private static WorkshopKind kind;
    private static Direction facing = Direction.NORTH;
    /** 0 at the eyes .. 1 at the rig. */
    private static float progress;
    /** 0 working shot .. 1 inspect shot. */
    private static float inspect;
    private static boolean inspectWanted;
    private static long lastNanos;
    @Nullable
    private static CameraFit.Pose working;
    @Nullable
    private static CameraFit.Pose close;
    private static boolean cut;
    private static boolean fallback;
    private static float startYaw;
    private static float startPitch;
    /** Where the camera was at the start of a station change (the glide over starts there). */
    @Nullable
    private static CameraFit.Pose from;
    @Nullable
    private static CameraFit.Pose last;
    private static double lastFov = 70;
    private static int solvedWidth = -1;
    private static int solvedHeight = -1;
    private static int solvedGuiHeight = -1;
    private static int solvedFov = -1;
    /** The player's own rotation at entry, restored on the way out (observers see the player face the work meanwhile). */
    private static float savedYaw;
    private static float savedPitch;
    private static boolean turned;
    private static Vec3 anchor = Vec3.ZERO;

    // ---- lifecycle ----

    /** The server opened a working view here: frame the station (from wherever the camera is now). */
    public static void begin(BlockPos at, WorkshopKind k) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean switching = phase != Phase.WORLD && pos != null && !pos.equals(at) && last != null;
        if (pos != null && pos.equals(at) && phase != Phase.WORLD && phase != Phase.LEAVING) return; // already here
        from = switching ? last : null;
        pos = at.immutable();
        kind = k;
        facing = facingAt(mc.level, at);
        inspect = 0;
        inspectWanted = false;
        fallback = false;
        solvedWidth = -1;
        anchor = mc.player.position();
        ClientConfig.CameraStyle style = ClientConfig.cameraStyle();
        if (style == ClientConfig.CameraStyle.EYE) {
            phase = Phase.WORKING;
            progress = 0;
            working = close = null;
            return;
        }
        if (!switching) {
            var camera = mc.gameRenderer.getMainCamera();
            startYaw = camera.getYRot();
            startPitch = camera.getXRot();
            if (phase == Phase.WORLD) progress = 0;
        } else {
            progress = 0;
        }
        solve(mc);
        if (working == null) {
            phase = Phase.WORKING;
            progress = 0;
            return;
        }
        if (!turned) {
            savedYaw = mc.player.getYRot();
            savedPitch = mc.player.getXRot();
            turned = true;
        }
        faceStation(mc);
        boolean instant = style == ClientConfig.CameraStyle.INSTANT || cut;
        phase = instant ? Phase.WORKING : Phase.ENTERING;
        if (instant) progress = 1;
        lastNanos = Util.getNanos();
    }

    /** The working view closed: glide back (or cut back) to the ordinary camera; control is the player's at once. */
    public static void end() {
        if (phase == Phase.WORLD) return;
        restoreRotation();
        boolean instant = ClientConfig.cameraStyle() != ClientConfig.CameraStyle.CLOSE_UP || cut || working == null;
        if (instant || progress <= 0) {
            reset();
            return;
        }
        phase = Phase.LEAVING;
        lastNanos = Util.getNanos();
    }

    /** Drops everything at once (disconnect, death, teleport, dimension change, reload): nothing stays overridden. */
    public static void reset() {
        restoreRotation();
        phase = Phase.WORLD;
        pos = null;
        kind = null;
        progress = 0;
        inspect = 0;
        inspectWanted = false;
        working = close = from = last = null;
        cut = false;
        solvedWidth = -1;
    }

    private static void restoreRotation() {
        var player = Minecraft.getInstance().player;
        if (turned && player != null) {
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
            player.yRotO = savedYaw;
            player.xRotO = savedPitch;
            player.yHeadRot = savedYaw;
        }
        turned = false;
    }

    /** Turns the player's entity toward the work (temporarily: restored on exit) so the people around see it. */
    private static void faceStation(Minecraft mc) {
        if (mc.player == null || pos == null) return;
        Vec3 d = Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90F;
        float pitch = Mth.clamp((float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG), -80F, 80F);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
    }

    public static Phase phase() {
        return phase;
    }

    public static boolean active() {
        return phase != Phase.WORLD;
    }

    /** Whether the render camera is away from the eyes this frame (the first-person hand is then not drawn). */
    public static boolean overriding() {
        return phase != Phase.WORLD && working != null && progress > 0;
    }

    /** Whether the view works from the eyes (EYE style, or no room for the close-up). */
    public static boolean eyeView() {
        return phase != Phase.WORLD && working == null;
    }

    @Nullable
    public static BlockPos pos() {
        return pos;
    }

    /** The deliberate inspect action: a closer look at the material, and back. */
    public static void toggleInspect() {
        if (phase != Phase.WORKING && phase != Phase.INSPECTING || close == null) return;
        inspectWanted = !inspectWanted;
        phase = inspectWanted ? Phase.INSPECTING : Phase.WORKING;
        lastNanos = Util.getNanos();
    }

    public static boolean inspecting() {
        return inspectWanted;
    }

    // ---- per tick: invalidation ----

    /** Ends or resets the camera when what it frames is gone or no longer this player's view. */
    public static void tick() {
        if (phase == Phase.WORLD) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || pos == null) {
            reset();
            return;
        }
        if (mc.getCameraEntity() != mc.player) {
            reset();
            return;
        }
        // A teleport (or being thrown far) is not a place to keep framing the old station from.
        if (mc.player.position().distanceToSqr(anchor) > 4.0) {
            reset();
            return;
        }
        if (!mc.level.isLoaded(pos) || !(mc.level.getBlockEntity(pos) instanceof com.otectus.immersivebrewing.blockentity.StationBlockEntity
                || mc.level.getBlockEntity(pos) instanceof com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity)) {
            end();
            return;
        }
        if (phase == Phase.LEAVING) return;
        // The view is the screen's: anything that replaced it (chat, a menu, the pause screen) ends the framing, except the
        // station's own drawer or planner opened from the view (the camera keeps the vessel in sight meanwhile).
        if (!(mc.screen instanceof WorkshopScreen) && !ClientWorkshop.returning()) end();
    }

    // ---- per frame: the pose ----

    /**
     * The render camera's pose for this frame, or null to leave vanilla's. {@code eye}, {@code eyeYaw} and
     * {@code eyePitch} are where vanilla (or a camera mod) placed the camera this frame.
     */
    @Nullable
    public static CameraFit.Pose pose(Vec3 eye, float eyeYaw, float eyePitch) {
        if (phase == Phase.WORLD) return null;
        var mc = Minecraft.getInstance();
        advance();
        if (phase == Phase.WORLD || working == null) return null;
        if (mc.getWindow().getWidth() != solvedWidth || mc.getWindow().getHeight() != solvedHeight
                || mc.getWindow().getGuiScaledHeight() != solvedGuiHeight || ClientConfig.get(ClientConfig.CLOSE_UP_FOV) != solvedFov) {
            solve(mc);
            if (working == null) return null;
        }
        float t = ease(progress);
        CameraFit.Pose rig = blend(working, close, ease(inspect));
        CameraFit.Pose start = from != null ? from : new CameraFit.Pose(eye, phase == Phase.LEAVING ? eyeYaw : startYaw,
                phase == Phase.LEAVING ? eyePitch : startPitch, lastFov, 0);
        Vec3 path = rig.position().subtract(start.position());
        Vec3 position = start.position().add(path.scale(t)).add(0, ARC * Math.min(1, path.length()) * Math.sin(Math.PI * t), 0);
        float yaw = start.yaw() + Mth.wrapDegrees(rig.yaw() - start.yaw()) * t;
        float pitch = Mth.lerp(t, start.pitch(), rig.pitch());
        last = new CameraFit.Pose(position, yaw, pitch, rig.fov(), rig.distance());
        return last;
    }

    /** The field of view for this frame: zoomed evenly between the player's and the rig's. */
    public static double fov(double original) {
        if (phase == Phase.WORLD || working == null) {
            lastFov = original;
            return original;
        }
        CameraFit.Pose rig = blend(working, close, ease(inspect));
        double a = Math.tan(Math.toRadians(Mth.clamp(from != null ? from.fov() : original, 1, 170)) / 2);
        double b = Math.tan(Math.toRadians(Mth.clamp(rig.fov(), 1, 170)) / 2);
        double fov = 2 * Math.toDegrees(Math.atan(a * Math.pow(b / a, ease(progress))));
        lastFov = fov;
        return fov;
    }

    private static void advance() {
        long now = Util.getNanos();
        float dt = Math.min((now - lastNanos) / 1.0e9F, 0.1F);
        lastNanos = now;
        float duration = (float) Math.max(0.05, ClientConfig.get(ClientConfig.CAMERA_SECONDS));
        switch (phase) {
            case ENTERING -> {
                progress = Math.min(1, progress + dt / duration);
                if (progress >= 1) {
                    phase = inspectWanted ? Phase.INSPECTING : Phase.WORKING;
                    from = null;
                }
            }
            case LEAVING -> {
                progress = Math.max(0, progress - dt / duration);
                if (progress <= 0) reset();
            }
            default -> { }
        }
        float target = inspectWanted ? 1 : 0;
        boolean instant = ClientConfig.cameraStyle() == ClientConfig.CameraStyle.INSTANT;
        if (instant) inspect = target;
        else if (inspect < target) inspect = Math.min(target, inspect + dt / duration);
        else if (inspect > target) inspect = Math.max(target, inspect - dt / duration);
    }

    private static CameraFit.Pose blend(CameraFit.Pose a, @Nullable CameraFit.Pose b, float t) {
        if (b == null || t <= 0) return a;
        if (t >= 1) return b;
        return new CameraFit.Pose(a.position().lerp(b.position(), t), a.yaw() + Mth.wrapDegrees(b.yaw() - a.yaw()) * t,
                Mth.lerp(t, a.pitch(), b.pitch()), Mth.lerp(t, a.fov(), b.fov()), Mth.lerp(t, a.distance(), b.distance()));
    }

    /** Sets off at once and lands softly; immediately interruptible because progress is the only state. */
    private static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // ---- the rig ----

    /** Fits the working and inspect shots to the window and checks them against the blocks around. */
    private static void solve(Minecraft mc) {
        solvedWidth = mc.getWindow().getWidth();
        solvedHeight = mc.getWindow().getHeight();
        solvedGuiHeight = mc.getWindow().getGuiScaledHeight();
        solvedFov = ClientConfig.get(ClientConfig.CLOSE_UP_FOV);
        if (pos == null || kind == null || mc.level == null) {
            working = close = null;
            return;
        }
        double aspect = solvedHeight <= 0 ? 16.0 / 9.0 : (double) solvedWidth / solvedHeight;
        // Hotbar/status bars plus two lines of guidance occupy logical GUI pixels, not a fixed framebuffer fraction.
        double hudReserve = Mth.clamp(80.0 / Math.max(1, solvedGuiHeight), HUD_RESERVE, 0.34);
        CameraFit.Shot base = adjusted(WorkshopShots.working(kind));
        CameraFit.Pose found = clearPose(mc.level, base, aspect, hudReserve);
        if (found == null) {
            working = close = null;
            if (!fallback) {
                fallback = true;
                StationNotices.show(Component.translatable("workshop.immersive_brewing.camera.no_room"), false);
            }
            return;
        }
        working = found;
        CameraFit.Shot near = adjusted(WorkshopShots.inspect(kind));
        close = clearPose(mc.level, near, aspect, hudReserve);
        Vec3 eye = mc.player == null ? found.position() : mc.player.getEyePosition();
        cut = !pathClear(mc.level, eye, found.position());
    }

    /** The shot with the player's preferred field of view (the authored one is a starting point). */
    private static CameraFit.Shot adjusted(CameraFit.Shot shot) {
        double fov = ClientConfig.get(ClientConfig.CLOSE_UP_FOV) + (shot.fov() - 44);
        var profile = com.otectus.immersivebrewing.client.presentation.PresentationProfiles.forStation(pos);
        double pitch = profile == null ? 0 : profile.cameraPitch();
        double yaw = profile == null ? 0 : profile.cameraYaw();
        return new CameraFit.Shot(shot.kind(), shot.target(), Mth.clamp(shot.pitch() + pitch, 15, 85), shot.yawOffset() + yaw,
                Mth.clamp(fov, 30, 60), shot.parts(), shot.padding());
    }

    /** The first authored alternative whose camera position and view of the apparatus are clear, or null. */
    @Nullable
    private static CameraFit.Pose clearPose(Level level, CameraFit.Shot shot, double aspect, double hudReserve) {
        double[][] variants = {{0, 0, 0}, {10, 0, 0}, {6, 24, 0}, {6, -24, 0}, {18, 0, 4}, {-8, 0, 0}, {24, 0, 8}, {12, 40, 4}, {12, -40, 4}};
        for (double[] v : variants) {
            CameraFit.Shot alt = shot.varied(v[0], v[1], v[2]);
            for (double maxDistance : new double[] {MAX_DISTANCE, 1.5, 1.1}) {
                CameraFit.Pose pose = CameraFit.solve(alt, pos, facing, aspect, MIN_DISTANCE, maxDistance, MAX_FOV, hudReserve);
                if (clear(level, pose)) return pose;
            }
        }
        return null;
    }

    /** Clearance around the camera and an open line from it to the apparatus (the station's own shell is not in the way). */
    private static boolean clear(Level level, CameraFit.Pose pose) {
        if (!roomAt(level, pose.position(), CLEARANCE)) return false;
        Vec3 target = WorkshopGeometry.toWorld(pos, facing, 8, 8, 8);
        return sightLine(level, pose.position(), target);
    }

    /** Nothing but the station itself between the camera and the apparatus (walls are never made transparent). */
    private static boolean sightLine(Level level, Vec3 from, Vec3 to) {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return true;
        return hit.getLocation().distanceToSqr(from) >= to.distanceToSqr(from) - 1e-4;
    }

    /** No block collision shape (other than the station's) within {@code margin} of the point. */
    static boolean roomAt(Level level, Vec3 p, double margin) {
        AABB box = new AABB(p.x - margin, p.y - margin, p.z - margin, p.x + margin, p.y + margin, p.z + margin);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ), max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (BlockPos b : BlockPos.betweenClosed(min, max)) {
            if (b.equals(pos)) continue;
            BlockState state = level.getBlockState(b);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(level, b);
            if (shape.isEmpty()) continue;
            for (AABB part : shape.toAabbs()) if (part.move(b).intersects(box)) return false;
        }
        return true;
    }

    /** Whether the glide from the eyes to the rig keeps clear of blocks; otherwise the camera cuts instead of passing through. */
    private static boolean pathClear(Level level, Vec3 eye, Vec3 rig) {
        for (int i = 1; i < 10; i++) {
            double t = i / 10.0;
            Vec3 p = eye.lerp(rig, t).add(0, ARC * Math.min(1, rig.distanceTo(eye)) * Math.sin(Math.PI * t), 0);
            if (!roomAt(level, p, CLEARANCE * 0.6)) return false;
        }
        return true;
    }

    private static Direction facingAt(Level level, BlockPos at) {
        BlockState state = level.getBlockState(at);
        return state.hasProperty(HorizontalDirectionalBlock.FACING) ? state.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    /** Drops cached framing (resource reload: a presentation profile may have changed the composition). */
    public static void invalidate() {
        solvedWidth = -1;
        if (phase != Phase.WORLD) ImmersiveBrewing.LOGGER.debug("Immersive Brewing: working camera re-framed after a reload");
    }

    private WorkshopCamera() {}
}
