package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.minigame.SimRandom;
import com.otectus.immersivebrewing.minigame.StepClock;
import com.otectus.immersivebrewing.minigame.brewing.AdditionSpec;
import com.otectus.immersivebrewing.minigame.brewing.Disturbance;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettleSimulation;
import com.otectus.immersivebrewing.minigame.brewing.PhaseSpec;
import com.otectus.immersivebrewing.minigame.brewing.Signature;

/**
 * Reference kettle players. {@link #competent} plays like an attentive brewer: it picks the draft whose predicted heat
 * a second and a half ahead is closest to the band's centre (allowing for a warned cold draft or flare and for the
 * cooling of a due addition), keeps circulation where the reaction signature wants it, and adds on time. {@link #poor}
 * fidgets at random and never adds; {@link #idle} gives no input. Used by the unit tests, the GameTests and the smoke
 * test; never shipped.
 */
public final class KettleBot {
    public enum Style { COMPETENT, POOR, IDLE }

    private final Style style;
    private final SimRandom random;
    private int lastDraft;

    public KettleBot(Style style, long seed) {
        this.style = style;
        this.random = new SimRandom(seed);
    }

    public static KettleBot competent() {
        return new KettleBot(Style.COMPETENT, 1);
    }

    public static KettleBot poor(long seed) {
        return new KettleBot(Style.POOR, seed);
    }

    public static KettleBot idle() {
        return new KettleBot(Style.IDLE, 0);
    }

    /** The frame for the simulation's next step, decided from its current state. */
    public KettleInput decide(KettleSimulation sim) {
        return switch (style) {
            case IDLE -> KettleInput.neutral(sim.draft());
            case POOR -> poor(sim);
            case COMPETENT -> competent(sim);
        };
    }

    private KettleInput poor(KettleSimulation sim) {
        if (random.nextInt(25) == 0) lastDraft = random.nextInt(3);
        int stir = random.nextInt(4) == 0 ? random.range(-8, 8) : 0;
        return new KettleInput(lastDraft, stir, false);
    }

    private KettleInput competent(KettleSimulation sim) {
        KettleParams params = sim.params();
        int step = sim.step();
        PhaseSpec phase = params.phaseAt(Math.min(step, params.totalSteps() - 1));
        int horizon = StepClock.seconds(1.5);
        // The band of whatever phase the brew will be in at the end of the horizon.
        PhaseSpec ahead = params.phaseAt(Math.min(step + horizon, params.totalSteps() - 1));
        double target = (ahead.heatLow() + ahead.heatHigh()) / 2;
        double extraCooling = 0;
        for (AdditionSpec a : params.additions()) {
            if (a.index() >= sim.nextAddition() && a.openStep() >= step && a.openStep() - step <= horizon) extraCooling += a.cooling();
        }
        double flare = 0;
        for (Disturbance d : params.disturbances()) {
            if (d.step() >= step && d.step() - step <= horizon) {
                if (d.kind() == Disturbance.Kind.COLD_DRAFT) extraCooling += d.magnitude();
                if (d.kind() == Disturbance.Kind.BURNER_FLARE) flare += d.magnitude();
            }
        }
        int bestDraft = sim.draft();
        double bestError = Double.MAX_VALUE;
        for (int d = 0; d <= 2; d++) {
            double b = Math.min(1, sim.burner() + (step + horizon > 0 ? flare : 0));
            double h = sim.heat();
            double t = KettleParams.DRAFT_TARGETS[d];
            for (int i = 0; i < horizon; i++) {
                b += params.burnerResponse() * (t - b) * StepClock.DT;
                h += params.heatResponse() * (b - h) * StepClock.DT;
            }
            h -= extraCooling;
            double error = Math.abs(h - target) + (d == sim.draft() ? 0 : 0.004);
            if (error < bestError) {
                bestError = error;
                bestDraft = d;
            }
        }

        int stir = stirFor(sim, phase);
        boolean add = false;
        int next = sim.nextAddition();
        if (next < params.additions().size()) {
            AdditionSpec a = params.additions().get(next);
            if (a.phase() == phase.phase() && step >= a.openStep() + 2 && step < a.closeStep()) add = true;
        }
        return new KettleInput(bestDraft, stir, add);
    }

    private static int stirFor(KettleSimulation sim, PhaseSpec phase) {
        double v = sim.circulation();
        double absV = Math.abs(v);
        double target = (phase.circLow() + phase.circHigh()) / 2;
        int direction = 1;
        Signature sig = phase.signature();
        if (sig == Signature.DENSE_SEDIMENT && sim.trouble() > 0.3) target = 0.85;
        if (sig == Signature.SUSPENDED_BUBBLES && phase.reverseStep() >= 0 && sim.step() >= phase.reverseStep()) direction = -1;
        if (phase.circHigh() <= 0.25 && sig != Signature.NONE) target = Math.min(target, phase.circHigh() * 0.5);
        if (direction > 0 && v < -0.05 || direction < 0 && v > 0.05) return direction * KettleInput.STEADY;
        if (absV < target - 0.02) {
            int level = (int) Math.round(Math.min(1.0, target + (target - absV) * 1.5) * 8);
            return direction * Math.max(1, Math.min(8, level));
        }
        if (absV > target + 0.06) return 0;
        return direction * Math.max(1, (int) Math.round(target * 8));
    }
}
