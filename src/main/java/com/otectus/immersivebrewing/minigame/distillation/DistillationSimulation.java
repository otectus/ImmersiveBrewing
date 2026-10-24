package com.otectus.immersivebrewing.minigame.distillation;

import com.otectus.immersivebrewing.minigame.Credit;
import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.minigame.StepClock;
import com.otectus.immersivebrewing.potion.QualityMath;

/**
 * "Catch the Heart" (spec 9.1): a routing and control game, not a timing ring. The still temperature T follows the
 * heat valve, the coolant flow K follows the coolant valve. Vapour rises with T; only what the coil condenses advances
 * the run, so too little coolant (or a choked, over-cold coil) slows it. Condensate passes through heads (cloudy),
 * heart (clear) and tails (hazy); the player routes the heart to the receiver and everything else to waste.
 *
 * <p>Score: {@code D = round(100 x (0.50 collection + 0.30 thermal + 0.20 cooling))}, with collection the heart caught
 * minus 1.5x any heads or tails collected, relative to the whole heart. No input at all resolves with D = 0.
 */
public final class DistillationSimulation {
    public static final int STATE_VERSION = 1;

    public enum Condensate { CLOUDY, CLEARING, CLEAR, HAZE_RISING, HAZY }

    private final DistillationParams params;
    private int step;
    private int heatLevel;
    private int coolLevel;
    private double temperature = 0.25;
    private double coolant;
    private double fraction;
    private boolean collecting;
    private double heart;
    private double heads;
    private double tails;
    private double thermalSum;
    private double coolingSum;
    private int scoredSteps;
    private int inputs;
    private double lastCondensed;
    private boolean finished;
    private boolean completed;

    public DistillationSimulation(DistillationParams params) {
        this.params = params;
    }

    public DistillationParams params() {
        return params;
    }

    public void step(DistillationInput input) {
        if (finished) return;
        if (input.heat() != heatLevel || input.coolant() != coolLevel || input.collect() != collecting) inputs++;
        heatLevel = input.heat();
        coolLevel = input.coolant();
        collecting = input.collect();

        double dt = StepClock.DT;
        temperature = Credit.clamp01(temperature + params.tempResponse() * (DistillationParams.HEAT_TARGETS[heatLevel] - temperature) * dt);
        coolant = Credit.clamp01(coolant + params.coolResponse() * (DistillationParams.COOL_TARGETS[coolLevel] - coolant) * dt);

        double vapour = Math.max(0, temperature - 0.35) * 1.4;
        double condensed = vapour * efficiency() * params.rate() * dt;
        condensed = Math.min(condensed, 1.0 - fraction);
        if (collecting && condensed > 0) {
            double before = fraction;
            double after = fraction + condensed;
            heads += overlap(before, after, 0, params.headsEnd());
            heart += overlap(before, after, params.headsEnd(), params.tailsStart());
            tails += overlap(before, after, params.tailsStart(), 1.0);
        }
        fraction += condensed;
        lastCondensed = condensed;

        if (step >= params.warmupSteps()) {
            thermalSum += Credit.band(temperature, params.heatLow(), params.heatHigh(), 0.15);
            coolingSum += Credit.band(coolant, params.coolLow(), params.coolHigh(), 0.20);
            scoredSteps++;
        }
        step++;
        if (fraction >= 1.0 - 1e-9) {
            finished = true;
            completed = true;
        } else if (step >= params.durationSteps()) {
            finished = true;
        }
    }

    /** Condensing efficiency of the coil: too little coolant lets vapour escape back; far too much chokes it. */
    public double efficiency() {
        double e = Math.min(1.0, coolant / 0.45);
        if (coolant > 0.85) e *= Math.max(0.3, 1.0 - (coolant - 0.85) * 2.5);
        return e;
    }

    private static double overlap(double a, double b, double lo, double hi) {
        return Math.max(0, Math.min(b, hi) - Math.max(a, lo));
    }

    public Condensate condensate() {
        double d = fraction;
        if (d < params.headsEnd() - 0.04) return Condensate.CLOUDY;
        if (d < params.headsEnd()) return Condensate.CLEARING;
        if (d < params.tailsStart() - 0.05) return Condensate.CLEAR;
        if (d < params.tailsStart()) return Condensate.HAZE_RISING;
        return Condensate.HAZY;
    }

    public boolean finished() {
        return finished;
    }

    public int step() {
        return step;
    }

    public int remainingSteps() {
        return Math.max(0, params.durationSteps() - step);
    }

    public double temperature() {
        return temperature;
    }

    public double coolant() {
        return coolant;
    }

    public double fraction() {
        return fraction;
    }

    public boolean collecting() {
        return collecting;
    }

    public int heatLevel() {
        return heatLevel;
    }

    public int coolLevel() {
        return coolLevel;
    }

    /** Condensate produced in the last step, for drip rate. */
    public double lastCondensed() {
        return lastCondensed;
    }

    public DistillationScore score() {
        double heartTotal = params.tailsStart() - params.headsEnd();
        double collection = Credit.clamp01((heart - 1.5 * (heads + tails)) / heartTotal);
        double thermal = scoredSteps == 0 ? 0 : thermalSum / scoredSteps;
        double cooling = scoredSteps == 0 ? 0 : coolingSum / scoredSteps;
        int d = inputs == 0 ? 0 : QualityMath.distillation(collection, thermal, cooling);
        return new DistillationScore(d, collection, thermal, cooling, heart / heartTotal, heads, tails, completed, inputs > 0);
    }

    public long[] saveState() {
        return new SimData.Writer().putInt(STATE_VERSION).putInt(step).putInt(heatLevel).putInt(coolLevel).putDouble(temperature)
                .putDouble(coolant).putDouble(fraction).putBool(collecting).putDouble(heart).putDouble(heads).putDouble(tails)
                .putDouble(thermalSum).putDouble(coolingSum).putInt(scoredSteps).putInt(inputs).putBool(finished)
                .putBool(completed).toArray();
    }

    public void loadState(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, STATE_VERSION);
        if (version != STATE_VERSION) throw new IllegalArgumentException("distillation state version " + version);
        step = r.getInt(0, params.durationSteps());
        heatLevel = r.getInt(0, DistillationInput.LEVELS - 1);
        coolLevel = r.getInt(0, DistillationInput.LEVELS - 1);
        temperature = r.getDouble(0, 1);
        coolant = r.getDouble(0, 1);
        fraction = r.getDouble(0, 1);
        collecting = r.getBool();
        heart = r.getDouble(0, 1);
        heads = r.getDouble(0, 1);
        tails = r.getDouble(0, 1);
        thermalSum = r.getDouble(0, 10000);
        coolingSum = r.getDouble(0, 10000);
        scoredSteps = r.getInt(0, 10000);
        inputs = r.getInt(0, Integer.MAX_VALUE);
        finished = r.getBool();
        completed = r.getBool();
    }
}
