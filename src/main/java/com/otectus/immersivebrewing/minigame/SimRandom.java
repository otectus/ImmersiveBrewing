package com.otectus.immersivebrewing.minigame;

/**
 * SplitMix64: a tiny deterministic generator, identical on every JVM, used for disturbance schedules and receiver
 * jitter. The seed is chosen by the server at reservation and never rerolled on resume (spec 20.4).
 */
public final class SimRandom {
    private long state;

    public SimRandom(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in [min, max]. */
    public double range(double min, double max) {
        return min + (max - min) * nextDouble();
    }

    /** Uniform in [0, bound). */
    public int nextInt(int bound) {
        if (bound <= 0) return 0;
        return (int) Math.floorMod(nextLong(), (long) bound);
    }

    /** Uniform in [min, max] inclusive. */
    public int range(int min, int max) {
        if (max <= min) return min;
        return min + nextInt(max - min + 1);
    }
}
