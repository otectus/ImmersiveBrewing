package com.otectus.immersivebrewing.workshop;

/**
 * Server time spent on hands-on workshop work in one server tick: physical process steps, lease upkeep and snapshot
 * encoding in station ticks, and intent validation. For the performance GameTest and the operator report; it measures,
 * it never limits. Server thread only.
 */
public final class WorkshopMetrics {
    private static long current;
    private static int stationsThisTick;
    private static int intentsThisTick;
    private static int snapshotsThisTick;
    private static volatile int lastSnapshots;
    private static volatile long totalSnapshots;
    private static volatile long lastNanos;
    private static volatile int lastStations;
    private static volatile int lastIntents;

    public static void station(long nanos) {
        current += nanos;
        stationsThisTick++;
    }

    public static void intent(long nanos) {
        current += nanos;
        intentsThisTick++;
    }

    /** A compact snapshot went to the players tracking a station. */
    public static void snapshot() {
        snapshotsThisTick++;
        totalSnapshots++;
    }

    /** Called once at the end of every server tick. */
    public static void endTick() {
        lastSnapshots = snapshotsThisTick;
        snapshotsThisTick = 0;
        lastNanos = current;
        lastStations = stationsThisTick;
        lastIntents = intentsThisTick;
        current = 0;
        stationsThisTick = 0;
        intentsThisTick = 0;
    }

    public static long lastTickNanos() {
        return lastNanos;
    }

    public static int lastStations() {
        return lastStations;
    }

    public static int lastIntents() {
        return lastIntents;
    }

    public static int lastSnapshots() {
        return lastSnapshots;
    }

    /** Snapshots sent since the server started (the GameTest compares two readings). */
    public static long totalSnapshots() {
        return totalSnapshots;
    }

    private WorkshopMetrics() {}
}
