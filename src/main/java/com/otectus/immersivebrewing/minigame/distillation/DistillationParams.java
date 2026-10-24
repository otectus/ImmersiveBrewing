package com.otectus.immersivebrewing.minigame.distillation;

import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.minigame.SimRandom;
import com.otectus.immersivebrewing.minigame.StepClock;

/**
 * Parameters of one purification run (spec 9.1). The heads/heart and heart/tails boundaries are the nominal receiver
 * markings plus a small seeded offset, so the condensate's own appearance (cloudy, clear, hazy) is the precise cue and
 * the markings are a generous guide.
 */
public record DistillationParams(int durationSteps, double headsEnd, double tailsStart, double heatLow, double heatHigh,
                                 double coolLow, double coolHigh, double rate, double tempResponse, double coolResponse,
                                 int warmupSteps) {
    public static final int VERSION = 1;
    public static final double NOMINAL_HEADS_END = 0.22;
    public static final double NOMINAL_TAILS_START = 0.70;
    public static final double[] HEAT_TARGETS = {0.15, 0.40, 0.62, 0.78, 0.95};
    public static final double[] COOL_TARGETS = {0.0, 0.25, 0.55, 0.72, 0.92};

    public DistillationParams {
        if (durationSteps < StepClock.seconds(10) || durationSteps > StepClock.seconds(40)) throw new IllegalArgumentException("duration");
        if (!(0 < headsEnd && headsEnd < tailsStart && tailsStart < 1)) throw new IllegalArgumentException("boundaries");
        if (!(heatLow < heatHigh && coolLow < coolHigh)) throw new IllegalArgumentException("bands");
        if (!(rate > 0 && rate < 1 && tempResponse > 0 && coolResponse > 0)) throw new IllegalArgumentException("rates");
    }

    public static DistillationParams create(long seed, double assistTime, double assistBand) {
        SimRandom random = new SimRandom(seed);
        double t = Math.max(1.0, Math.min(1.5, assistTime));
        double b = Math.max(1.0, Math.min(1.25, assistBand));
        double heads = NOMINAL_HEADS_END + random.range(-0.025, 0.025);
        double tails = NOMINAL_TAILS_START + random.range(-0.025, 0.025);
        double hc = 0.635;
        double hh = 0.085 * b;
        double cc = 0.60;
        double ch = 0.15 * b;
        return new DistillationParams((int) Math.round(StepClock.seconds(22) * t), heads, tails, hc - hh, hc + hh, cc - ch, cc + ch,
                0.16 / t, 0.9 / t, 2.5 / t, StepClock.seconds(3));
    }

    public long[] encode() {
        return new SimData.Writer().putInt(VERSION).putInt(durationSteps).putDouble(headsEnd).putDouble(tailsStart)
                .putDouble(heatLow).putDouble(heatHigh).putDouble(coolLow).putDouble(coolHigh).putDouble(rate)
                .putDouble(tempResponse).putDouble(coolResponse).putInt(warmupSteps).toArray();
    }

    public static DistillationParams decode(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, VERSION);
        if (version != VERSION) throw new IllegalArgumentException("distillation params version " + version);
        return new DistillationParams(r.getInt(), r.getDouble(0, 1), r.getDouble(0, 1), r.getDouble(0, 1), r.getDouble(0, 1),
                r.getDouble(0, 1), r.getDouble(0, 1), r.getDouble(), r.getDouble(), r.getDouble(), r.getInt(0, 2000));
    }
}
