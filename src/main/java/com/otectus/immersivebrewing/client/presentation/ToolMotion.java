package com.otectus.immersivebrewing.client.presentation;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a held tool moves as a physical object (handoff 7.2): its working tip follows the hand with a little weight, it
 * leans into the direction it is pushed and is slow to swing round on a reversal, and it eases back to its rest when let
 * go instead of jumping there. Presentation only: the server receives the hand's unsmoothed samples, so this adds no
 * input latency and no work.
 */
public final class ToolMotion {
    private static final int MAX = 64;
    private static final Map<Long, ToolMotion> ALL = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ToolMotion> eldest) {
            return size() > MAX;
        }
    };

    /** The tip, model pixels. */
    public float x, y, z;
    /** The lean, degrees about the station's x and z axes. */
    public float leanX, leanZ;
    /** 0 at rest .. 1 in the hand. */
    public float held;
    private float vx, vz;
    private long lastNanos = Util.getNanos();
    private boolean placed;

    public static ToolMotion of(BlockPos pos, int part) {
        return ALL.computeIfAbsent(pos.asLong() * 64 + part, k -> new ToolMotion());
    }

    public static void remove(BlockPos pos) {
        ALL.keySet().removeIf(k -> Math.floorDiv(k, 64) == pos.asLong());
    }

    public static void clear() {
        ALL.clear();
    }

    /**
     * Advances to this frame. {@code tx, ty, tz} is where the tip should be (in the hand, or at the rest); {@code local}
     * follows the player's own hand more tightly than an observed one.
     */
    public void update(boolean holding, float tx, float ty, float tz, boolean local, double motion) {
        long now = Util.getNanos();
        float dt = Math.min((now - lastNanos) / 1.0e9F, 0.1F);
        lastNanos = now;
        if (!placed || motion <= 0) {
            x = tx;
            y = ty;
            z = tz;
            placed = true;
            held = holding ? 1 : 0;
            leanX = leanZ = 0;
            return;
        }
        float follow = 1 - (float) Math.exp(-dt * (local ? 22 : 10));
        float px = x, pz = z;
        x += (tx - x) * follow;
        y += (ty - y) * (1 - (float) Math.exp(-dt * 14));
        z += (tz - z) * follow;
        if (dt > 0) {
            float ivx = (x - px) / dt, ivz = (z - pz) / dt;
            // A lagged velocity: the lean builds and swings round over a fraction of a second.
            float lag = 1 - (float) Math.exp(-dt * 5);
            vx += (ivx - vx) * lag;
            vz += (ivz - vz) * lag;
        }
        float scale = (float) Math.min(1, motion);
        leanZ = Mth.clamp(-vx * 0.9F, -14, 14) * scale;
        leanX = Mth.clamp(vz * 0.9F, -14, 14) * scale;
        held = Mth.clamp(held + (holding ? dt : -dt) / 0.25F, 0, 1);
    }

    /** Recent tip speed, model pixels per second (drives stir sound and wake strength). */
    public float speed() {
        return (float) Math.sqrt(vx * vx + vz * vz);
    }

    private ToolMotion() {}
}
