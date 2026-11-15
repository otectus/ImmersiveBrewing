package com.otectus.immersivebrewing.client.workshop;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The camera the world was actually drawn with in the last frame (handoff 5.4): its projection and view rotation as the
 * level render used them, and its position. Cursor rays, projected labels, outlines and liquid-plane intersections all go
 * through this, so picking agrees with the picture whatever moved the camera (the close-up rig, view bobbing, a field of
 * view change, a camera mod) and at any window size, GUI scale or aspect ratio.
 */
public final class WorkshopProjector {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean valid;

    /** Captures the level render's matrices (after the solid blocks: the pose stack holds just the camera rotation). */
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        capture(event.getProjectionMatrix(), event.getPoseStack().last().pose(), event.getCamera().getPosition());
    }

    public static void capture(Matrix4f projection, Matrix4f view, Vec3 position) {
        projection.mul(view, VIEW_PROJECTION);
        VIEW_PROJECTION.invert(INVERSE);
        camera = position;
        valid = true;
    }

    public static boolean valid() {
        return valid;
    }

    public static Vec3 camera() {
        return camera;
    }

    /** The world ray under a GUI point: {origin, unit direction}. Falls back to the main camera before the first frame. */
    public static Vec3[] ray(double guiX, double guiY) {
        var mc = Minecraft.getInstance();
        double w = Math.max(1, mc.getWindow().getGuiScaledWidth()), h = Math.max(1, mc.getWindow().getGuiScaledHeight());
        return rayNdc(guiX / w * 2 - 1, 1 - guiY / h * 2);
    }

    /** The world ray through normalised device coordinates. */
    public static Vec3[] rayNdc(double ndcX, double ndcY) {
        if (!valid) {
            var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            var look = cam.getLookVector();
            return new Vec3[] {cam.getPosition(), new Vec3(look.x(), look.y(), look.z())};
        }
        Vector4f near = INVERSE.transform(new Vector4f((float) ndcX, (float) ndcY, -1, 1));
        Vector4f far = INVERSE.transform(new Vector4f((float) ndcX, (float) ndcY, 1, 1));
        Vec3 a = new Vec3(near.x / near.w, near.y / near.w, near.z / near.w);
        Vec3 b = new Vec3(far.x / far.w, far.y / far.w, far.z / far.w);
        return new Vec3[] {camera.add(a), b.subtract(a).normalize()};
    }

    /** A world point in GUI coordinates, or null when it is behind the camera. */
    @Nullable
    public static double[] project(Vec3 world) {
        if (!valid) return null;
        Vector4f clip = VIEW_PROJECTION.transform(new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y),
                (float) (world.z - camera.z), 1));
        if (clip.w <= 1e-4F) return null;
        var mc = Minecraft.getInstance();
        double x = (clip.x / clip.w + 1) / 2 * mc.getWindow().getGuiScaledWidth();
        double y = (1 - clip.y / clip.w) / 2 * mc.getWindow().getGuiScaledHeight();
        return new double[] {x, y};
    }

    public static void clear() {
        valid = false;
    }

    private WorkshopProjector() {}
}
