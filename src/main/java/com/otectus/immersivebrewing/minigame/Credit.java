package com.otectus.immersivebrewing.minigame;

/**
 * Continuous error scoring (spec 8.1): full credit inside a band, then a smooth fall to zero over {@code falloff}, so a
 * tiny excursion costs a little and never fails a batch.
 */
public final class Credit {

    /** 1 inside {@code [low, high]}; smoothly 0 at {@code falloff} outside it. */
    public static double band(double value, double low, double high, double falloff) {
        double error;
        if (value < low) error = low - value;
        else if (value > high) error = value - high;
        else return 1.0;
        if (falloff <= 0) return 0.0;
        double t = Math.min(1.0, error / falloff);
        // smoothstep from 1 down to 0
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    /** 1 at or below {@code free}; 0 at or above {@code zero}; smooth in between. */
    public static double below(double value, double free, double zero) {
        if (value <= free) return 1.0;
        if (value >= zero) return 0.0;
        double t = (value - free) / (zero - free);
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private Credit() {}
}
