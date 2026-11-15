package com.otectus.immersivebrewing.client.presentation;

import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The kettle's liquid as the player sees it (handoff 8.1): a small damped height field over the vessel interior, stirred
 * by the paddle's actual contact point (a depression where the blade is, a wake behind it), struck where a stream or a
 * dose lands, and carried round by the batch's synced circulation. Dose fragments ride that flow, are deflected by the
 * blade and the rim, and shrink as the server reports the dose incorporating. Everything here is cosmetic and bounded:
 * a fixed grid, at most {@link #MAX_FRAGMENTS} representatives, a fixed step rate; nothing here can change a result,
 * because the server never sees it.
 */
public final class KettleSurface {
    public static final int NX = 12, NZ = 14;
    public static final int MAX_FRAGMENTS = 10;
    private static final int MAX_SURFACES = 16;
    private static final float STEP = 1F / 40F;
    private static final float SPEED2 = 38F, DAMPING = 2.6F, RESTORE = 4F;

    /** One cosmetic representative of a dose (never a reagent item: it can not be taken or counted). */
    public static final class Fragment {
        public float x, z, depth, size, spin, angle;
        public int color;
        public PresentationProfiles.Arrival material;
        public ItemStack item = ItemStack.EMPTY;
        /** 1 when it arrived, shrinking with the dose's incorporation. */
        public float remaining = 1;
        public long born;
        boolean alive;
    }

    private static final Map<BlockPos, KettleSurface> SURFACES = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, KettleSurface> eldest) {
            return size() > MAX_SURFACES;
        }
    };

    private final float[] height = new float[NX * NZ];
    private final float[] velocity = new float[NX * NZ];
    public final Fragment[] fragments = new Fragment[MAX_FRAGMENTS];
    private long lastNanos = Util.getNanos();
    private float pending;
    private float swirl;
    private float lastPaddleX = Float.NaN, lastPaddleZ = Float.NaN;
    /** The latest impact point of a stream or dose, for {@link #splash}. */
    private float impactX = WorkshopParts.KETTLE_CX, impactZ = WorkshopParts.KETTLE_CZ - 2;
    /** Recent paddle speed, model pixels per second (drives the stir sound and the wake). */
    private float paddleSpeed;
    private float bubbleTimer;
    private int seed = 1;

    private KettleSurface() {
        for (int i = 0; i < MAX_FRAGMENTS; i++) fragments[i] = new Fragment();
    }

    public static KettleSurface get(BlockPos pos) {
        return SURFACES.computeIfAbsent(pos.immutable(), p -> new KettleSurface());
    }

    @Nullable
    public static KettleSurface peek(BlockPos pos) {
        return SURFACES.get(pos);
    }

    public static void remove(BlockPos pos) {
        SURFACES.remove(pos);
    }

    public static void clear() {
        SURFACES.clear();
    }

    /** A stream or dose landed (an accepted pour or addition): a ring spreads from where it struck. */
    public static void splash(BlockPos pos, double strength) {
        KettleSurface s = SURFACES.get(pos);
        if (s != null) s.impulse(s.impactX, s.impactZ, (float) (0.55 * strength), 1.6F);
    }

    /** Where the current stream meets the liquid (model pixels). */
    public void impact(float x, float z) {
        impactX = Mth.clamp(x, x0() + 0.3F, x1() - 0.3F);
        impactZ = Mth.clamp(z, z0() + 0.3F, z1() - 0.3F);
    }

    public float impactX() {
        return impactX;
    }

    public float impactZ() {
        return impactZ;
    }

    public static float x0() {
        return WorkshopParts.KETTLE_CX - WorkshopParts.KETTLE_HX;
    }

    public static float x1() {
        return WorkshopParts.KETTLE_CX + WorkshopParts.KETTLE_HX;
    }

    public static float z0() {
        return WorkshopParts.KETTLE_CZ - WorkshopParts.KETTLE_HZ;
    }

    public static float z1() {
        return WorkshopParts.KETTLE_CZ + WorkshopParts.KETTLE_HZ;
    }

    /** Pushes the surface down (positive {@code amount}) in a soft disc around a model-pixel point. */
    public void impulse(float x, float z, float amount, float radius) {
        float cw = (x1() - x0()) / (NX - 1), cd = (z1() - z0()) / (NZ - 1);
        for (int j = 0; j < NZ; j++) {
            for (int i = 0; i < NX; i++) {
                float dx = x0() + i * cw - x, dz = z0() + j * cd - z;
                float d2 = (dx * dx + dz * dz) / (radius * radius);
                if (d2 >= 1) continue;
                float w = (1 - d2) * (1 - d2);
                velocity[j * NX + i] -= amount * w * 6F;
            }
        }
    }

    /**
     * Advances the cosmetic liquid by real time. {@code paddle} is the blade's contact {x, z, deep} in model pixels while
     * it is in the liquid (null otherwise); {@code circulation}, {@code heat} (0..4 band) and {@code undissolved} come
     * from the server's snapshot.
     */
    public void step(@Nullable float[] paddle, double circulation, int heat, double undissolved, double motion) {
        step(paddle, circulation, heat, undissolved, motion, false);
    }

    /** As above; {@code coarse} (a distant kettle) advances at most 20 times a second, catching up on the elapsed time. */
    public void step(@Nullable float[] paddle, double circulation, int heat, double undissolved, double motion, boolean coarse) {
        long now = Util.getNanos();
        if (coarse && now - lastNanos < 50_000_000L) return;
        float dt = Math.min((now - lastNanos) / 1.0e9F, 0.1F);
        lastNanos = now;
        if (motion <= 0) {
            // Reduced motion keeps the level, contact and material but no rippling of its own.
            java.util.Arrays.fill(height, 0);
            java.util.Arrays.fill(velocity, 0);
            paddleSpeed = 0;
            updateFragments(dt, paddle, circulation, undissolved, 0);
            return;
        }
        pending += dt;
        int steps = 0;
        while (pending >= STEP && steps < 6) {
            pending -= STEP;
            steps++;
            simulate(STEP, paddle, circulation, heat, (float) motion);
        }
        if (steps == 6) pending = 0;
        updateFragments(dt, paddle, circulation, undissolved, motion);
    }

    private void simulate(float dt, @Nullable float[] paddle, double circulation, int heat, float motion) {
        if (paddle != null) {
            float px = paddle[0], pz = paddle[1];
            if (!Float.isNaN(lastPaddleX)) {
                float vx = (px - lastPaddleX) / dt, vz = (pz - lastPaddleZ) / dt;
                float speed = (float) Math.sqrt(vx * vx + vz * vz);
                paddleSpeed += (speed - paddleSpeed) * 0.2F;
                // The blade presses a hollow where it is and heaps the liquid in front of it: a wake that trails.
                float push = Math.min(1.5F, speed / 18F) * motion;
                impulse(px, pz, 0.05F * push + 0.012F, 1.3F);
                if (speed > 0.5F) impulse(px + vx / speed * 1.2F, pz + vz / speed * 1.2F, -0.035F * push, 1.0F);
            }
            lastPaddleX = px;
            lastPaddleZ = pz;
        } else {
            lastPaddleX = Float.NaN;
            paddleSpeed *= 0.9F;
        }
        // Heat: bubbles rise and break where the flame is, more and larger as it boils.
        if (heat >= 2) {
            bubbleTimer -= dt * heat * heat * 0.9F;
            if (bubbleTimer <= 0) {
                bubbleTimer = 1;
                seed = seed * 1103515245 + 12345;
                float bx = x0() + 0.8F + (seed >>> 8 & 1023) / 1023F * (x1() - x0() - 1.6F);
                seed = seed * 1103515245 + 12345;
                float bz = z0() + 0.8F + (seed >>> 8 & 1023) / 1023F * (z1() - z0() - 1.6F);
                impulse(bx, bz, -0.025F * heat, 0.7F);
            }
        }
        swirl += (float) circulation * dt * 2.4F;
        float cw2 = 1F;
        for (int j = 0; j < NZ; j++) {
            for (int i = 0; i < NX; i++) {
                int k = j * NX + i;
                float c = height[k];
                float l = i > 0 ? height[k - 1] : c, r = i < NX - 1 ? height[k + 1] : c;
                float u = j > 0 ? height[k - NX] : c, d = j < NZ - 1 ? height[k + NX] : c;
                float lap = (l + r + u + d - 4 * c) / cw2;
                velocity[k] += (lap * SPEED2 - c * RESTORE) * dt;
                velocity[k] *= 1 - Math.min(1, DAMPING * dt);
            }
        }
        for (int k = 0; k < height.length; k++) height[k] = Mth.clamp(height[k] + velocity[k] * dt, -0.6F, 0.6F);
    }

    /** Dose representatives: drift with the circulation, are pushed aside by the blade, keep off the rim, sink and shrink. */
    private void updateFragments(float dt, @Nullable float[] paddle, double circulation, double undissolved, double motion) {
        float cx = WorkshopParts.KETTLE_CX, cz = WorkshopParts.KETTLE_CZ;
        for (Fragment f : fragments) {
            if (!f.alive) continue;
            f.remaining += ((float) undissolved - f.remaining) * Math.min(1, dt * 3);
            if (f.remaining < 0.04F) {
                f.alive = false;
                continue;
            }
            float rx = f.x - cx, rz = f.z - cz;
            float r = (float) Math.sqrt(rx * rx + rz * rz) + 1e-3F;
            float flow = (float) (circulation * 2.6 * motion) * Math.min(1, r / 3F);
            float vx = -rz / r * flow, vz = rx / r * flow;
            if (paddle != null) {
                float dx = f.x - paddle[0], dz = f.z - paddle[1], d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d < 1.4F && d > 1e-3F) {
                    float shove = (1.4F - d) * 4F * (float) Math.max(0.3, motion);
                    vx += dx / d * shove;
                    vz += dz / d * shove;
                }
            }
            f.x += vx * dt;
            f.z += vz * dt;
            // The rim turns them back.
            f.x = Mth.clamp(f.x, x0() + 0.4F, x1() - 0.4F);
            f.z = Mth.clamp(f.z, z0() + 0.4F, z1() - 0.4F);
            float sink = switch (f.material) {
                case CRYSTAL -> 0.9F;
                case FIBROUS -> 0.25F;
                case PASTE -> 0.35F;
                case LIQUID -> 0.0F;
                case PARTICULATE -> 0.06F;
                case GENERIC -> 0.4F;
            };
            f.depth = Math.min(1, f.depth + sink * dt);
            f.angle += f.spin * dt * (float) Math.max(0.2, motion);
        }
    }

    /** A dose arrived: a bounded handful of representatives at the impact point, shaped by its material. */
    public void addDose(ItemStack item, PresentationProfiles.Reagent reagent, int count, double scale) {
        int n = Math.max(1, Math.min(MAX_FRAGMENTS, count));
        long now = Util.getMillis();
        for (int a = 0; a < n; a++) {
            Fragment f = free();
            seed = seed * 1103515245 + 12345;
            float ox = ((seed >>> 8 & 255) / 255F - 0.5F) * 1.6F;
            seed = seed * 1103515245 + 12345;
            float oz = ((seed >>> 8 & 255) / 255F - 0.5F) * 1.6F;
            f.x = impactX + ox;
            f.z = impactZ + oz;
            f.depth = 0;
            f.size = (float) (reagent.size() * scale * (reagent.material() == PresentationProfiles.Arrival.PASTE ? 1.6 : 1.0));
            f.spin = (a % 2 == 0 ? 1 : -1) * (0.6F + (seed >>> 20 & 7) * 0.2F);
            f.angle = a * 1.7F;
            f.color = reagent.color();
            f.material = reagent.material();
            f.item = item.copyWithCount(1);
            f.remaining = 1;
            f.born = now;
            f.alive = true;
        }
        impulse(impactX, impactZ, 0.35F, 1.4F);
    }

    private Fragment free() {
        Fragment oldest = fragments[0];
        for (Fragment f : fragments) {
            if (!f.alive) return f;
            if (f.born < oldest.born) oldest = f;
        }
        return oldest;
    }

    public boolean anyFragments() {
        for (Fragment f : fragments) if (f.alive) return true;
        return false;
    }

    /** The surface offset (model pixels) at grid point (i, j). */
    public float height(int i, int j) {
        return height[Mth.clamp(j, 0, NZ - 1) * NX + Mth.clamp(i, 0, NX - 1)];
    }

    /** The surface offset at a model-pixel point (bilinear). */
    public float heightAt(float x, float z) {
        float fi = (x - x0()) / (x1() - x0()) * (NX - 1), fj = (z - z0()) / (z1() - z0()) * (NZ - 1);
        int i = Mth.clamp((int) Math.floor(fi), 0, NX - 2), j = Mth.clamp((int) Math.floor(fj), 0, NZ - 2);
        float a = Mth.clamp(fi - i, 0, 1), b = Mth.clamp(fj - j, 0, 1);
        return Mth.lerp(b, Mth.lerp(a, height(i, j), height(i + 1, j)), Mth.lerp(a, height(i, j + 1), height(i + 1, j + 1)));
    }

    /** The phase the circulation has carried the surface patterns round by (radians). */
    public float swirl() {
        return swirl;
    }

    public float paddleSpeed() {
        return paddleSpeed;
    }
}
