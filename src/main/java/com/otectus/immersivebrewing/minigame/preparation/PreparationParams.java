package com.otectus.immersivebrewing.minigame.preparation;

import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.minigame.StepClock;

/**
 * Everything the preparation simulation needs, fixed when the session starts (and stored with a sealed job, so a later
 * datapack change can not alter a running preparation). All values are validated finite and bounded.
 */
public record PreparationParams(PreparationMode mode, int durationSteps, double endpointLow, double endpointHigh,
                                double pressureLimit, double overworkThreshold, double workRate, double clumpRate,
                                double foldRelief, int kits) {
    public static final int VERSION = 1;
    /** Most pestle travel one step can count, in bowl radii: fast circles and high DPI can not process faster. */
    public static final double MOTION_CAP = 0.10;
    public static final int MAX_KITS = 4;
    /** Bulk preparation adds this much time per extra kit (spec 6: a modest increment). */
    public static final int STEPS_PER_EXTRA_KIT = StepClock.seconds(1.0);

    public PreparationParams {
        if (mode == null) throw new IllegalArgumentException("mode");
        if (durationSteps < StepClock.seconds(5) || durationSteps > StepClock.seconds(40)) throw new IllegalArgumentException("durationSteps " + durationSteps);
        if (!(endpointLow > 0 && endpointLow <= endpointHigh && endpointHigh < overworkThreshold && overworkThreshold < 1)) {
            throw new IllegalArgumentException("endpoint " + endpointLow + ".." + endpointHigh + " / overwork " + overworkThreshold);
        }
        check(pressureLimit, 0.1, 1.0, "pressureLimit");
        check(workRate, 0.005, 1.0, "workRate");
        check(clumpRate, 0.0, 5.0, "clumpRate");
        check(foldRelief, 0.0, 1.0, "foldRelief");
        if (kits < 1 || kits > MAX_KITS) throw new IllegalArgumentException("kits " + kits);
    }

    /**
     * Profile values plus the mode's tuning defaults. {@code durationTicks} is the profile's base time; extra kits and
     * an approved time assist lengthen it.
     */
    public static PreparationParams of(PreparationMode mode, int durationTicks, double endpointLow, double endpointHigh,
                                       double pressureLimit, double overworkThreshold, int kits, double assistTime) {
        double workRate = switch (mode) {
            case CRYSTALLINE -> 0.075;
            case FIBROUS -> 0.070;
            case SOFT -> 0.068;
            case BRITTLE -> 0.080;
            case LIQUID -> 0.060;
        };
        double clumpRate = switch (mode) {
            case CRYSTALLINE -> 1.1;
            case FIBROUS -> 2.0;
            case SOFT -> 1.2;
            case BRITTLE -> 0.8;
            case LIQUID -> 0.6;
        };
        double foldRelief = mode == PreparationMode.FIBROUS || mode == PreparationMode.LIQUID ? 0.75 : 0.6;
        int k = Math.max(1, Math.min(MAX_KITS, kits));
        double assist = Math.max(1.0, Math.min(1.5, assistTime));
        int steps = (int) Math.round((durationTicks + (k - 1) * STEPS_PER_EXTRA_KIT) * assist);
        steps = Math.max(StepClock.seconds(5), Math.min(StepClock.seconds(40), steps));
        return new PreparationParams(mode, steps, endpointLow, endpointHigh, pressureLimit, overworkThreshold,
                workRate / assist, clumpRate, foldRelief, k);
    }

    public long[] encode() {
        return new SimData.Writer().putInt(VERSION).putInt(mode.ordinal()).putInt(durationSteps).putDouble(endpointLow)
                .putDouble(endpointHigh).putDouble(pressureLimit).putDouble(overworkThreshold).putDouble(workRate)
                .putDouble(clumpRate).putDouble(foldRelief).putInt(kits).toArray();
    }

    public static PreparationParams decode(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, VERSION);
        if (version != VERSION) throw new IllegalArgumentException("preparation params version " + version);
        PreparationMode mode = PreparationMode.values()[r.getInt(0, PreparationMode.values().length - 1)];
        return new PreparationParams(mode, r.getInt(), r.getDouble(), r.getDouble(), r.getDouble(), r.getDouble(),
                r.getDouble(), r.getDouble(), r.getDouble(), r.getInt());
    }

    private static void check(double v, double min, double max, String name) {
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException(name + " " + v);
    }
}
