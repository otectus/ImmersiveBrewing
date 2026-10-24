package com.otectus.immersivebrewing.potion;

/**
 * Every documented formula of spec 6, 8.2, 9.1 and 11 in one place, in integer arithmetic where the spec promises an
 * exact value (Standard at 50 matches the source recipe exactly, 32 drinking ticks at 50). Pure: no Minecraft imports,
 * so the unit tests check the boundary values directly.
 */
public final class QualityMath {
    public static final int MIN = 0;
    public static final int MAX = 100;
    /** Extraction used when preparation was skipped or does not apply (spec 6, 8.2). */
    public static final int RAW_EXTRACTION = 50;
    /** Score at which a potion behaves exactly like its source recipe. */
    public static final int BASELINE = 50;
    /** Vanilla drinking time this mod assumes; {@link #useTicks} shifts from whatever the running game reports. */
    public static final int VANILLA_USE_TICKS = 32;

    /** {@code E = round(100 x (0.70 x endpoint_accuracy + 0.30 x restraint))}, both terms clamped to 0..1. */
    public static int extraction(double endpointAccuracy, double restraint) {
        return score(0.70 * clamp01(endpointAccuracy) + 0.30 * clamp01(restraint));
    }

    /** {@code C = round(100 x (0.40 x thermal + 0.35 x circulation + 0.25 x dosing))}. */
    public static int control(double thermal, double circulation, double dosing) {
        return score(0.40 * clamp01(thermal) + 0.35 * clamp01(circulation) + 0.25 * clamp01(dosing));
    }

    /** {@code D = round(100 x (0.50 x collection + 0.30 x thermal + 0.20 x cooling))}. */
    public static int distillation(double collection, double thermal, double cooling) {
        return score(0.50 * clamp01(collection) + 0.30 * clamp01(thermal) + 0.20 * clamp01(cooling));
    }

    /** {@code Q = round(0.25 x E + 0.75 x C)}, half up: E=50, C=100 gives 88. */
    public static int quality(int extraction, int control) {
        int e = clampScore(extraction);
        int c = clampScore(control);
        // 0.25E + 0.75C = (E + 3C) / 4; round half up in integers.
        return clampScore((e + 3 * c + 2) / 4);
    }

    /**
     * Purification result (spec 9.1): with {@code D >= 35} the output is {@code max(Q_in, min(cap, round(50 + 0.2D)))};
     * a weaker run keeps {@code Q_in}.
     */
    public static int purified(int qualityIn, int distillation, int cap) {
        int q = clampScore(qualityIn);
        int d = clampScore(distillation);
        if (d < 35) return q;
        int candidate = (250 + d + 2) / 5; // round(50 + 0.2D) = round((250 + D) / 5), half up
        return Math.max(q, Math.min(clampScore(cap), candidate));
    }

    /**
     * {@code 0.80 + 0.004Q} as an exact fraction {@code (200 + Q) / 250}, so quality 50 is exactly 1.
     */
    public static double durationMultiplier(int quality) {
        return (200 + clampScore(quality)) / 250.0;
    }

    /**
     * Scales a finite duration by the quality multiplier, bounded to {@code [min, max]}, rounding half up. Quality 50
     * returns {@code ticks} unchanged whatever the bounds, as long as they contain 1.
     */
    public static int scaleDuration(int ticks, int quality, double min, double max) {
        if (ticks <= 0) return ticks;
        int q = clampScore(quality);
        if (q == BASELINE && min <= 1.0 && max >= 1.0) return ticks;
        double multiplier = Math.max(min, Math.min(max, durationMultiplier(q)));
        long scaled = Math.round(ticks * multiplier);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }

    /**
     * Drinking time {@code round(36 - 0.08Q)} relative to the running game's baseline: {@code baseline + round(4 - 0.08Q)},
     * which is 36/32/28 ticks at 0/50/100 when the baseline is vanilla's 32.
     */
    public static int useTicks(int quality, int baseline) {
        int q = clampScore(quality);
        // 4 - 0.08Q = (100 - 2Q) / 25; round half up with floor((100 - 2Q + 12.5) / 25).
        int offset = Math.floorDiv(2 * (100 - 2 * q) + 25, 50);
        return Math.max(1, baseline + offset);
    }

    public static Grade grade(int quality) {
        return Grade.of(quality);
    }

    public static int clampScore(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    public static double clamp01(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }

    /** {@code round(100 x value)} for a 0..1 value, half up. */
    public static int score(double value) {
        return clampScore((int) Math.floor(clamp01(value) * 100.0 + 0.5 + 1e-9));
    }

    private QualityMath() {}
}
