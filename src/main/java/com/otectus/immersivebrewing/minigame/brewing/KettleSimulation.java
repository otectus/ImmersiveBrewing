package com.otectus.immersivebrewing.minigame.brewing;

import com.otectus.immersivebrewing.minigame.Credit;
import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.minigame.StepClock;
import com.otectus.immersivebrewing.potion.QualityMath;

import java.util.Arrays;

/**
 * "Tend the Living Brew" (spec 7, 8) as one deterministic step simulation through Bloom, Infuse and Settle.
 *
 * <p>Heat has two-stage inertia: the burner B follows the draft target and the liquid H follows the burner,
 * {@code H_next = clamp(H + heat_response x (B - H) x dt - cooling, 0, 1)}, so a high draft held too long overshoots
 * (spec 8.1 asks for inertia that "can overshoot"; the single-stage form it gives as an initial model can not, see
 * docs/ARCHITECTURE.md). Circulation follows the stir command, {@code V_next = clamp(V + stir_response x (cmd - V) x dt)},
 * and coasts back toward rest when released. Each phase's {@link Signature} turns circulation into visible trouble.
 *
 * <p>Score: {@code C = round(100 x (0.40 thermal + 0.35 circulation + 0.25 dosing))}. Thermal and circulation are
 * continuous per-step credits normalised by the scored steps; a phase in which the player gave no input (nor in the
 * three seconds before it) earns nothing, and a session with no input at all resolves with C = 0.
 */
public final class KettleSimulation {
    public static final int STATE_VERSION = 1;
    /** Input within this many steps before a phase starts still counts as tending that phase. */
    public static final int PARTICIPATION_LEAD = StepClock.seconds(3);

    public enum Outcome {
        PENDING, EARLY, ON_TIME, LATE, AUTOMATIC;

        public String key() {
            return "outcome.immersive_brewing." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final KettleParams params;
    private int step;
    private int draft;
    private double burner;
    private double heat;
    private double circulation;
    private double trouble;
    private double dominant;
    private int coldSteps;
    private double coldPerStep;
    private int nextAddition;
    private final Outcome[] outcomes;
    private final double[] credits;
    private final int[] madeAt;
    private final boolean[] participation;
    private final double[] thermalSum;
    private final double[] circulationSum;
    private final double[] heatErrorSum;
    private final int[] scoredSteps;
    private int inputs;
    private boolean finished;
    /** Transient: the last addition press was refused because the next item belongs to a later phase. */
    private boolean refusedAddition;

    public KettleSimulation(KettleParams params) {
        this.params = params;
        int n = params.additions().size();
        int p = params.phases().size();
        this.outcomes = new Outcome[n];
        Arrays.fill(outcomes, Outcome.PENDING);
        this.credits = new double[n];
        this.madeAt = new int[n];
        Arrays.fill(madeAt, -1);
        this.participation = new boolean[p];
        this.thermalSum = new double[p];
        this.circulationSum = new double[p];
        this.heatErrorSum = new double[p];
        this.scoredSteps = new int[p];
        this.draft = params.initialDraft();
        this.burner = params.initialBurner();
        this.heat = params.initialHeat();
        this.trouble = params.phases().get(0).initialTrouble();
    }

    public KettleParams params() {
        return params;
    }

    /** Advances one 50 ms step. */
    public void step(KettleInput input) {
        if (finished) return;
        refusedAddition = false;
        int phaseIndex = params.phaseIndex(step);
        PhaseSpec phase = params.phases().get(phaseIndex);

        boolean acted = false;
        if (input.draft() != draft) {
            draft = input.draft();
            acted = true;
        }
        if (input.stir() != 0) acted = true;
        if (input.add()) {
            acted = true;
            tryAddition(phase);
        }
        if (acted) {
            inputs++;
            markParticipation(step);
        }

        for (Disturbance d : params.disturbances()) {
            if (d.step() != step) continue;
            switch (d.kind()) {
                case COLD_DRAFT -> {
                    coldSteps = Disturbance.COLD_SPREAD;
                    coldPerStep = d.magnitude() / Disturbance.COLD_SPREAD;
                }
                case BURNER_FLARE -> burner = Credit.clamp01(burner + d.magnitude());
                case SURGE -> trouble = Credit.clamp01(trouble + d.magnitude());
            }
        }

        double dt = StepClock.DT;
        double target = KettleParams.DRAFT_TARGETS[draft];
        burner = Credit.clamp01(burner + params.burnerResponse() * (target - burner) * dt);
        double cooling = 0;
        if (coldSteps > 0) {
            cooling = coldPerStep;
            coldSteps--;
        }
        heat = Credit.clamp01(heat + params.heatResponse() * (burner - heat) * dt - cooling);

        double command = input.stirCommand();
        double response = command != 0 ? params.stirResponse() : params.coastResponse();
        circulation = Credit.clamp(circulation + response * (command - circulation) * dt, -1, 1);

        updateTrouble(phase, dt);

        if (step >= params.warmupSteps()) {
            double absV = Math.abs(circulation);
            thermalSum[phaseIndex] += Credit.band(heat, phase.heatLow(), phase.heatHigh(), params.heatFalloff());
            circulationSum[phaseIndex] += phase.signature() == Signature.NONE
                    ? Credit.band(absV, phase.circLow(), phase.circHigh(), params.circFalloff())
                    : Credit.below(trouble, 0.25, 0.75);
            if (heat > phase.heatHigh()) heatErrorSum[phaseIndex] += heat - phase.heatHigh();
            else if (heat < phase.heatLow()) heatErrorSum[phaseIndex] -= phase.heatLow() - heat;
            scoredSteps[phaseIndex]++;
        }

        step++;
        if (step == phase.end()) {
            autoAdd(phase.phase());
            if (phaseIndex + 1 < params.phases().size()) trouble = params.phases().get(phaseIndex + 1).initialTrouble();
        }
        if (step >= params.totalSteps()) finished = true;
    }

    private void markParticipation(int at) {
        for (int i = 0; i < participation.length; i++) {
            PhaseSpec p = params.phases().get(i);
            if (at >= p.start() - PARTICIPATION_LEAD && at < p.end()) participation[i] = true;
        }
    }

    private void tryAddition(PhaseSpec phase) {
        if (nextAddition >= params.additions().size()) return;
        AdditionSpec next = params.additions().get(nextAddition);
        if (next.phase().ordinal() > phase.phase().ordinal()) {
            refusedAddition = true;
            return;
        }
        Outcome outcome;
        double credit;
        if (step < next.openStep()) {
            outcome = Outcome.EARLY;
            credit = step < next.cueStep() ? 0.25 : 0.4;
        } else if (step < next.closeStep()) {
            outcome = Outcome.ON_TIME;
            credit = 1.0;
        } else {
            outcome = Outcome.LATE;
            int span = Math.max(1, phase.end() - next.closeStep());
            credit = Math.max(0.25, 0.7 - 0.45 * (step - next.closeStep()) / (double) span);
        }
        apply(next, outcome, credit);
    }

    private void autoAdd(KettlePhase ending) {
        while (nextAddition < params.additions().size() && params.additions().get(nextAddition).phase().ordinal() <= ending.ordinal()) {
            apply(params.additions().get(nextAddition), Outcome.AUTOMATIC, 0);
        }
    }

    private void apply(AdditionSpec addition, Outcome outcome, double credit) {
        outcomes[addition.index()] = outcome;
        credits[addition.index()] = credit;
        madeAt[addition.index()] = step;
        heat = Credit.clamp01(heat - addition.cooling());
        trouble = Credit.clamp01(trouble + addition.kick());
        nextAddition++;
    }

    private void updateTrouble(PhaseSpec phase, double dt) {
        Signature sig = phase.signature();
        if (sig == Signature.NONE) {
            trouble = Math.max(0, trouble - 0.5 * trouble * dt);
            return;
        }
        double absV = Math.abs(circulation);
        double growth = sig.g0 + sig.gHigh * Math.max(0, absV - phase.circHigh()) + sig.gLow * Math.max(0, phase.circLow() - absV);
        if (sig == Signature.ALIGNING_GLINTS && step >= phase.start() + phase.length() / 2) {
            growth += sig.gHigh * Math.max(0, absV - phase.circHigh()); // vigorous late stirring scatters twice as fast
        }
        double decay = 0;
        if (sig == Signature.SUSPENDED_BUBBLES && phase.reverseStep() >= 0) {
            if (step < phase.reverseStep()) {
                dominant += circulation;
            } else if (dominant != 0 && Math.signum(circulation) == -Math.signum(dominant) && absV >= phase.circLow()) {
                decay = 0.9;
                growth = 0;
            }
        } else if (sig.strongDecay > 0 && absV >= Signature.STRONG_STIR) {
            decay = sig.strongDecay;
        } else if (absV >= phase.circLow() && absV <= phase.circHigh()) {
            decay = sig.decay;
        }
        trouble = Credit.clamp01(trouble + (growth - decay * trouble) * dt);
    }

    // ---- read-only views for the screen, renderer and tests ----

    public boolean finished() {
        return finished;
    }

    public int step() {
        return step;
    }

    public int remainingSteps() {
        return Math.max(0, params.totalSteps() - step);
    }

    public int draft() {
        return draft;
    }

    public double burner() {
        return burner;
    }

    public double heat() {
        return heat;
    }

    public double circulation() {
        return circulation;
    }

    /** The current phase's trouble level S. */
    public double trouble() {
        return trouble;
    }

    public PhaseSpec phase() {
        return params.phaseAt(Math.min(step, params.totalSteps() - 1));
    }

    public int nextAddition() {
        return nextAddition;
    }

    public Outcome outcome(int addition) {
        return outcomes[addition];
    }

    public int madeAt(int addition) {
        return madeAt[addition];
    }

    public boolean refusedAddition() {
        return refusedAddition;
    }

    public int inputs() {
        return inputs;
    }

    /** A disturbance currently warned and not yet past, or null. */
    public Disturbance warning() {
        for (Disturbance d : params.disturbances()) if (step >= d.warnStep() && step < d.step() + Disturbance.COLD_SPREAD) return d;
        return null;
    }

    public double foam() {
        Signature s = phase().signature();
        return s.channel == Signature.Channel.FOAM ? trouble : s.channel == Signature.Channel.BUBBLES ? trouble * 0.4 : 0;
    }

    public double sediment() {
        return phase().signature().channel == Signature.Channel.SEDIMENT ? trouble : 0;
    }

    public double clarity() {
        return 1.0 - 0.6 * trouble;
    }

    public BrewScore score() {
        int n = params.phases().size();
        double thermal = 0;
        double circ = 0;
        int scored = 0;
        double[] phaseThermal = new double[n];
        double[] phaseCirc = new double[n];
        double[] phaseHeatBias = new double[n];
        boolean[] counted = new boolean[n];
        for (int i = 0; i < n; i++) {
            scored += scoredSteps[i];
            counted[i] = participation[i];
            if (scoredSteps[i] > 0) {
                phaseThermal[i] = participation[i] ? thermalSum[i] / scoredSteps[i] : 0;
                phaseCirc[i] = participation[i] ? circulationSum[i] / scoredSteps[i] : 0;
                phaseHeatBias[i] = heatErrorSum[i] / scoredSteps[i];
            }
            if (participation[i]) {
                thermal += thermalSum[i];
                circ += circulationSum[i];
            }
        }
        thermal = scored == 0 ? 0 : thermal / scored;
        circ = scored == 0 ? 0 : circ / scored;
        double dosing = 0;
        for (double c : credits) dosing += c;
        dosing = credits.length == 0 ? 1.0 : dosing / credits.length;
        int control = inputs == 0 ? 0 : QualityMath.control(thermal, circ, dosing);
        KettlePhase[] phases = new KettlePhase[n];
        Signature[] signatures = new Signature[n];
        for (int i = 0; i < n; i++) {
            phases[i] = params.phases().get(i).phase();
            signatures[i] = params.phases().get(i).signature();
        }
        String[] items = new String[credits.length];
        for (int i = 0; i < items.length; i++) items[i] = params.additions().get(i).item();
        return new BrewScore(control, thermal, circ, dosing, inputs > 0, phases, signatures, phaseThermal, phaseCirc, phaseHeatBias,
                counted, outcomes.clone(), credits.clone(), items);
    }

    // ---- persistence (sealing) ----

    public long[] saveState() {
        SimData.Writer w = new SimData.Writer().putInt(STATE_VERSION).putInt(step).putInt(draft).putDouble(burner).putDouble(heat)
                .putDouble(circulation).putDouble(trouble).putDouble(dominant).putInt(coldSteps).putDouble(coldPerStep)
                .putInt(nextAddition).putInt(inputs).putBool(finished);
        w.putInt(outcomes.length);
        for (int i = 0; i < outcomes.length; i++) w.putInt(outcomes[i].ordinal()).putDouble(credits[i]).putInt(madeAt[i]);
        w.putInt(participation.length);
        for (int i = 0; i < participation.length; i++) {
            w.putBool(participation[i]).putDouble(thermalSum[i]).putDouble(circulationSum[i]).putDouble(heatErrorSum[i]).putInt(scoredSteps[i]);
        }
        return w.toArray();
    }

    public void loadState(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, STATE_VERSION);
        if (version != STATE_VERSION) throw new IllegalArgumentException("kettle state version " + version);
        step = r.getInt(0, params.totalSteps());
        draft = r.getInt(0, 2);
        burner = r.getDouble(0, 1);
        heat = r.getDouble(0, 1);
        circulation = r.getDouble(-1, 1);
        trouble = r.getDouble(0, 1);
        dominant = r.getDouble();
        coldSteps = r.getInt(0, Disturbance.COLD_SPREAD);
        coldPerStep = r.getDouble(0, 1);
        nextAddition = r.getInt(0, params.additions().size());
        inputs = r.getInt(0, Integer.MAX_VALUE);
        finished = r.getBool();
        if (r.getInt() != outcomes.length) throw new IllegalArgumentException("kettle state additions mismatch");
        for (int i = 0; i < outcomes.length; i++) {
            outcomes[i] = Outcome.values()[r.getInt(0, Outcome.values().length - 1)];
            credits[i] = r.getDouble(0, 1);
            madeAt[i] = r.getInt(-1, params.totalSteps());
        }
        if (r.getInt() != participation.length) throw new IllegalArgumentException("kettle state phases mismatch");
        for (int i = 0; i < participation.length; i++) {
            participation[i] = r.getBool();
            thermalSum[i] = r.getDouble(0, KettleParams.SAFETY_LIMIT_STEPS);
            circulationSum[i] = r.getDouble(0, KettleParams.SAFETY_LIMIT_STEPS);
            heatErrorSum[i] = r.getDouble(-KettleParams.SAFETY_LIMIT_STEPS, KettleParams.SAFETY_LIMIT_STEPS);
            scoredSteps[i] = r.getInt(0, KettleParams.SAFETY_LIMIT_STEPS);
        }
    }

    /** Resume after a seal: held stirring stops (the liquid keeps coasting from its stored state); draft stays. */
    public void neutraliseHeldInput() {
        refusedAddition = false;
    }
}
