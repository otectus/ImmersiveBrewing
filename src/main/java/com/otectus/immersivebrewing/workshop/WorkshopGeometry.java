package com.otectus.immersivebrewing.workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Model-pixel to world mapping for north-authored station parts (the same rotation the renderers apply with
 * {@code RenderBits.face}), ray picking against part volumes, and the server's reach and obstruction checks. Pure
 * geometry: no client classes, so the server validates against exactly the volumes the client outlines.
 */
public final class WorkshopGeometry {
    public record Hit(WorkshopPart part, Vec3 point, double distance) {}

    /** A model-pixel point of a north-facing station to world coordinates. */
    public static Vec3 toWorld(BlockPos pos, Direction facing, double px, double py, double pz) {
        double x = px / 16.0 - 0.5, z = pz / 16.0 - 0.5;
        double rx, rz;
        switch (facing) {
            case SOUTH -> { rx = -x; rz = -z; }
            case EAST -> { rx = -z; rz = x; }
            case WEST -> { rx = z; rz = -x; }
            default -> { rx = x; rz = z; }
        }
        return new Vec3(pos.getX() + 0.5 + rx, pos.getY() + py / 16.0, pos.getZ() + 0.5 + rz);
    }

    /** A world point to model pixels of a north-facing station (the inverse of {@link #toWorld}). */
    public static Vec3 toModel(BlockPos pos, Direction facing, Vec3 world) {
        double rx = world.x - pos.getX() - 0.5, rz = world.z - pos.getZ() - 0.5;
        double x, z;
        switch (facing) {
            case SOUTH -> { x = -rx; z = -rz; }
            case EAST -> { x = rz; z = -rx; }
            case WEST -> { x = -rz; z = rx; }
            default -> { x = rx; z = rz; }
        }
        return new Vec3((x + 0.5) * 16.0, (world.y - pos.getY()) * 16.0, (z + 0.5) * 16.0);
    }

    /** A direction in world space to model space (rotation only). */
    public static Vec3 dirToModel(Direction facing, Vec3 dir) {
        return switch (facing) {
            case SOUTH -> new Vec3(-dir.x, dir.y, -dir.z);
            case EAST -> new Vec3(dir.z, dir.y, -dir.x);
            case WEST -> new Vec3(-dir.z, dir.y, dir.x);
            default -> dir;
        };
    }

    public static AABB worldBox(BlockPos pos, Direction facing, WorkshopPart part) {
        Vec3 a = toWorld(pos, facing, part.x0(), part.y0(), part.z0());
        Vec3 b = toWorld(pos, facing, part.x1(), part.y1(), part.z1());
        return new AABB(a, b);
    }

    public static Vec3 center(BlockPos pos, Direction facing, WorkshopPart part) {
        return toWorld(pos, facing, part.cx(), part.cy(), part.cz());
    }

    /** The nearest part along a world ray whose volume it enters within {@code maxDistance}, among the accepted parts. */
    public static Optional<Hit> pick(List<WorkshopPart> parts, BlockPos pos, Direction facing, Vec3 origin, Vec3 direction,
                                     double maxDistance, Predicate<WorkshopPart> accepted) {
        Vec3 end = origin.add(direction.normalize().scale(maxDistance));
        Hit best = null;
        for (WorkshopPart part : parts) {
            if (!accepted.test(part)) continue;
            Optional<Vec3> hit = worldBox(pos, facing, part).clip(origin, end);
            if (hit.isEmpty()) {
                if (worldBox(pos, facing, part).contains(origin)) hit = Optional.of(origin);
                else continue;
            }
            double d = hit.get().distanceTo(origin);
            if (best == null || d < best.distance()) best = new Hit(part, hit.get(), d);
        }
        return Optional.ofNullable(best);
    }

    /** Where a world ray meets the horizontal plane {@code y = py} (model pixels), in model pixels, or null. */
    @Nullable
    public static Vec3 onPlane(BlockPos pos, Direction facing, Vec3 origin, Vec3 direction, double py) {
        Vec3 o = toModel(pos, facing, origin);
        Vec3 d = dirToModel(facing, direction).scale(16.0);
        if (Math.abs(d.y) < 1e-6) return null;
        double t = (py - o.y) / d.y;
        if (t <= 0) return null;
        return o.add(d.scale(t));
    }

    /** Eye distance to the station centre, in blocks. */
    public static double reach(Player player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    /**
     * Whether the part can be seen from the player's eyes: the straight line to its centre (or, with tolerance for the
     * focus framing, to one of its corners' midpoints) meets no block other than the station itself. A wall between the
     * player and the apparatus refuses the action, whatever the client claims it was pointing at.
     */
    public static boolean visible(Level level, Player player, BlockPos pos, Direction facing, WorkshopPart part) {
        Vec3 eye = player.getEyePosition();
        Vec3[] targets = {
                center(pos, facing, part),
                toWorld(pos, facing, part.cx(), part.y1(), part.cz()),
                toWorld(pos, facing, part.x0() + 0.5, part.cy(), part.cz()),
                toWorld(pos, facing, part.x1() - 0.5, part.cy(), part.cz()),
                Vec3.atCenterOf(pos).add(0, 0.45, 0)};
        for (Vec3 target : targets) {
            BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return true;
            // A neighbour hit beyond the target point is not between the eye and the part.
            if (hit.getLocation().distanceToSqr(eye) >= target.distanceToSqr(eye) - 1e-4) return true;
        }
        return false;
    }

    private WorkshopGeometry() {}
}
