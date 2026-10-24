package com.otectus.immersivebrewing.minigame;

/**
 * All three games run in fixed 50 ms logical steps, one per server game tick (spec 21.2; ADR-2 in docs/ARCHITECTURE.md).
 * Jewelry's games use 20 ms steps; brewing reads slower physical cues, so one step per tick is enough and lets the server
 * clock (not the client's frame rate or wall clock) authorise every elapsed step.
 */
public final class StepClock {
    public static final int STEP_MS = 50;
    public static final double DT = STEP_MS / 1000.0;
    public static final int STEPS_PER_SECOND = 1000 / STEP_MS;

    public static int seconds(double seconds) {
        return (int) Math.round(seconds * STEPS_PER_SECOND);
    }

    private StepClock() {}
}
