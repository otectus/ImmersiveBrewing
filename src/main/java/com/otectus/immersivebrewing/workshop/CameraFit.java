package com.otectus.immersivebrewing.workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure framing math for the close-up working view (handoff 5.1, 5.2). A shot is authored in a north-facing station's
 * model pixels (a target, a pitch, a yaw offset and the parts that must stay readable) and turned with the block's
 * facing, never keyed to world north. The camera distance is the smallest one at which every bound point lies inside
 * both the horizontal and the vertical field of view with padding, so a narrow window pulls back instead of cropping
 * the bottle rail and an ultrawide one keeps the work in the middle.
 *
 * <p>Conventions are Minecraft's: yaw 0 looks toward +Z (south) and grows toward -X; positive pitch looks down. No
 * client classes: the client rig and the unit tests share this.
 */
public final class CameraFit {
    /** A camera orientation as three unit vectors. */
    public record Basis(Vec3 forward, Vec3 right, Vec3 up) {}

    /**
     * An authored composition.
     *
     * @param kind       the station kind whose part table {@code parts} names
     * @param target     what the camera looks at, model pixels
     * @param pitch      degrees below horizontal
     * @param yawOffset  degrees turned from straight-on (the camera stays on the player's side)
     * @param fov        the preferred vertical field of view, degrees
     * @param parts      the parts whose volumes must stay inside the picture
     * @param padding    the share of the half-frame kept free around them (0..0.4)
     */
    public record Shot(WorkshopKind kind, Vec3 target, double pitch, double yawOffset, double fov, int[] parts, double padding) {
        /** The same composition seen from another angle or closer (an alternative when the first is obstructed). */
        public Shot varied(double pitchDelta, double yawDelta, double fovDelta) {
            return new Shot(kind, target, Math.max(15, Math.min(85, pitch + pitchDelta)), yawOffset + yawDelta, fov + fovDelta, parts, padding);
        }
    }

    /** The rig's result: where the camera goes, how it looks and the field of view it needs. */
    public record Pose(Vec3 position, float yaw, float pitch, double fov, double distance) {}

    /** The yaw a station's working camera looks along: from its front toward its back. */
    public static float stationYaw(Direction facing) {
        return (float) wrap(facing.toYRot() + 180.0);
    }

    public static Basis basis(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-9) right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        return new Basis(forward, right, up);
    }

    /**
     * The smallest distance from {@code target}, back along {@code basis.forward}, at which every point is inside a
     * frustum of vertical field {@code vfov} and the window's {@code aspect} with {@code padding} kept free.
     */
    public static double fit(List<Vec3> points, Vec3 target, Basis basis, double vfov, double aspect, double padding) {
        return fit(points, target, basis, vfov, aspect, padding, 0);
    }

    /**
     * {@link #fit(List, Vec3, Basis, double, double, double)} keeping the bottom {@code reserve} share of the frame's
     * height free (the hotbar and status bars stay visible over the working view and must not cover the apparatus).
     */
    public static double fit(List<Vec3> points, Vec3 target, Basis basis, double vfov, double aspect, double padding, double reserve) {
        double kv = Math.tan(Math.toRadians(vfov) / 2) * (1 - padding);
        double kh = kv * Math.max(0.2, aspect);
        double below = kv * Math.max(0.05, 1 - 2 * reserve);
        double distance = 0;
        for (Vec3 p : points) {
            Vec3 d = p.subtract(target);
            double x = d.dot(basis.right()), y = d.dot(basis.up()), z = d.dot(basis.forward());
            double vertical = y >= 0 ? y / kv : -y / below;
            distance = Math.max(distance, Math.max(Math.abs(x) / kh, vertical) - z);
        }
        return distance;
    }

    /**
     * Fits a shot: the preferred field of view first; if that needs the camera further away than {@code maxDistance},
     * a wider field (up to {@code maxFov}) instead, so a narrow window widens before it pulls back out of reach.
     */
    public static Pose solve(Shot shot, BlockPos pos, Direction facing, double aspect, double minDistance, double maxDistance, double maxFov) {
        return solve(shot, pos, facing, aspect, minDistance, maxDistance, maxFov, 0);
    }

    /** {@link #solve(Shot, BlockPos, Direction, double, double, double, double)} with a bottom band kept free. */
    public static Pose solve(Shot shot, BlockPos pos, Direction facing, double aspect, double minDistance, double maxDistance, double maxFov,
                             double reserve) {
        Vec3 target = WorkshopGeometry.toWorld(pos, facing, shot.target().x, shot.target().y, shot.target().z);
        float yaw = (float) wrap(stationYaw(facing) + shot.yawOffset());
        Basis basis = basis(yaw, shot.pitch());
        List<Vec3> points = bounds(shot, pos, facing, shot.kind());
        // Centre the framed parts in the picture: the authored target only fixes the depth the camera looks into.
        if (!points.isEmpty()) target = centred(points, target, basis);
        // The reserved band: lift the work in the frame by aiming a little below its middle.
        if (reserve > 0 && !points.isEmpty()) target = target.subtract(basis.up().scale(reserve * extentUp(points, target, basis)));
        double fov = shot.fov();
        double distance = fit(points, target, basis, fov, aspect, shot.padding(), reserve);
        while (distance > maxDistance && fov < maxFov) {
            fov = Math.min(maxFov, fov + 2.5);
            distance = fit(points, target, basis, fov, aspect, shot.padding(), reserve);
        }
        distance = Math.max(minDistance, Math.min(maxDistance, distance));
        Vec3 position = target.subtract(basis.forward().scale(distance));
        return new Pose(position, yaw, (float) shot.pitch(), fov, distance);
    }

    /** {@code target} moved across the view (along right and up) to the middle of the points' extent. */
    public static Vec3 centred(List<Vec3> points, Vec3 target, Basis basis) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Vec3 p : points) {
            Vec3 d = p.subtract(target);
            double x = d.dot(basis.right()), y = d.dot(basis.up());
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return target.add(basis.right().scale((minX + maxX) / 2)).add(basis.up().scale((minY + maxY) / 2));
    }

    private static double extentUp(List<Vec3> points, Vec3 target, Basis basis) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Vec3 p : points) {
            double y = p.subtract(target).dot(basis.up());
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return max - min;
    }

    /** The corners of every framed part, in world coordinates. */
    public static List<Vec3> bounds(Shot shot, BlockPos pos, Direction facing, WorkshopKind kind) {
        List<Vec3> out = new ArrayList<>();
        for (int id : shot.parts()) {
            WorkshopPart part = WorkshopParts.get(kind, id);
            if (part == null) continue;
            for (int c = 0; c < 8; c++) {
                double x = (c & 1) == 0 ? part.x0() : part.x1();
                double y = (c & 2) == 0 ? part.y0() : part.y1();
                double z = (c & 4) == 0 ? part.z0() : part.z1();
                out.add(WorkshopGeometry.toWorld(pos, facing, x, y, z));
            }
        }
        return out;
    }

    /** Whether a world point projects inside the frame of a pose (for tests: fitted points are on screen). */
    public static boolean inFrame(Pose pose, Vec3 point, double aspect) {
        Basis b = basis(pose.yaw(), pose.pitch());
        Vec3 d = point.subtract(pose.position());
        double z = d.dot(b.forward());
        if (z <= 0) return false;
        double kv = Math.tan(Math.toRadians(pose.fov()) / 2);
        return Math.abs(d.dot(b.right())) <= kv * aspect * z + 1e-6 && Math.abs(d.dot(b.up())) <= kv * z + 1e-6;
    }

    /** Degrees wrapped to -180..180. */
    public static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private CameraFit() {}
}
