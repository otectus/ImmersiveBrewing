package com.otectus.immersivebrewing.minigame.brewing;

import com.otectus.immersivebrewing.minigame.SimRandom;
import com.otectus.immersivebrewing.minigame.StepClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the parameters of one kettle session from the selected plan (spec 8.3). The complexity tier comes from the
 * chosen transformation path, serving count and reaction profiles; the seed only places disturbances inside validated
 * bounds and never changes costs, effects or formulas.
 *
 * <table>
 * <tr><th>Tier</th><th>Plan</th><th>Bloom / Infuse / Settle</th><th>Interventions</th></tr>
 * <tr><td>Gentle</td><td>base brews, one-effect potions</td><td>9 / 13 / 8 s (about 30 s)</td><td>2-3</td></tr>
 * <tr><td>Practiced</td><td>extended, strong or splash variants</td><td>9 / 14 / 12 s (about 35 s)</td><td>3-4</td></tr>
 * <tr><td>Intricate</td><td>corruptions, lingering, four or more steps</td><td>9 / 17 / 16 s (about 42 s)</td><td>4-5</td></tr>
 * </table>
 * Four to six servings add up to 4 s and some inertia; modifier-only sessions skip to Settle and last 10-16 s.
 */
public final class KettleSetup {
    public static final int GENTLE = 0;
    public static final int PRACTICED = 1;
    public static final int INTRICATE = 2;

    public static final double BURNER_RESPONSE = 1.6;
    public static final double HEAT_RESPONSE = 0.9;
    public static final double STIR_RESPONSE = 2.6;
    public static final double COAST_RESPONSE = 0.7;
    public static final double HEAT_FALLOFF = 0.12;
    public static final double CIRC_FALLOFF = 0.22;

    public static final Reaction DEFAULT_BLOOM = new Reaction(Signature.BLOOM_SIMMER, 0.48, 0.62, 0.08, 0.40, 0.25);
    public static final Reaction DEFAULT_SETTLE = new Reaction(Signature.SETTLE_CLEAR, 0.36, 0.48, 0.0, 0.22, 0.30);
    public static final Reaction DEFAULT_INFUSE = new Reaction(Signature.NONE, 0.46, 0.60, 0.25, 0.55, 0.0);

    /** What a step does to the path, which drives the tier. */
    public enum Role { BASE, EFFECT, CORRUPTION, MODIFIER, CONTAINER }

    /** One addition of the plan, already reserved: its phase, role, display id and how it disturbs the brew. */
    public record Step(KettlePhase phase, Role role, String item, double cooling, double kick, boolean lingering) {}

    /** A phase's reaction: signature, heat band, |circulation| band and starting trouble. */
    public record Reaction(Signature signature, double heatLow, double heatHigh, double circLow, double circHigh, double initialTrouble) {}

    /** Everything about the plan the session needs. */
    public record Plan(List<Step> steps, Reaction bloom, Reaction infuse, Reaction settle, List<Disturbance.Kind> disturbances,
                       double inertia, int servings) {}

    /** Server-approved assistance (spec 23.1): slower motion and cue timing, wider bands. 1.0 means none. */
    public record Assist(double time, double band) {
        public static final Assist NONE = new Assist(1.0, 1.0);

        public Assist {
            time = Math.max(1.0, Math.min(1.5, time));
            band = Math.max(1.0, Math.min(1.25, band));
        }
    }

    public static int tier(Plan plan) {
        boolean intricate = plan.steps().size() >= 4;
        boolean practiced = false;
        for (Step s : plan.steps()) {
            if (s.role() == Role.CORRUPTION || s.lingering()) intricate = true;
            if (s.role() == Role.MODIFIER || s.role() == Role.CONTAINER) practiced = true;
        }
        if (intricate) return INTRICATE;
        return practiced ? PRACTICED : GENTLE;
    }

    /**
     * @param maxSeconds the configured cap for unassisted sessions (default 50); assisted pacing may exceed it up to the
     *                   90 s safety limit, never mid-phase (the parameters are final before the count-in)
     */
    public static KettleParams build(Plan plan, long seed, int maxSeconds, Assist assist) {
        int tier = tier(plan);
        List<Step> bloomSteps = new ArrayList<>();
        List<Step> infuseSteps = new ArrayList<>();
        List<Step> settleSteps = new ArrayList<>();
        KettlePhase floor = KettlePhase.BLOOM;
        for (Step s : plan.steps()) {
            // Phases never go backwards: a corruption after a modifier happens in Settle.
            KettlePhase p = s.phase().ordinal() < floor.ordinal() ? floor : s.phase();
            floor = p;
            (p == KettlePhase.BLOOM ? bloomSteps : p == KettlePhase.INFUSE ? infuseSteps : settleSteps).add(s);
        }
        boolean modifierOnly = bloomSteps.isEmpty() && infuseSteps.isEmpty();
        double t = assist.time();
        int window = (int) Math.round((tier == GENTLE ? 40 : tier == PRACTICED ? 35 : 30) * t);

        int bloomLen = 0;
        int infuseLen = 0;
        int settleLen;
        if (modifierOnly) {
            settleLen = StepClock.seconds(Math.min(16.0, 10.0 + 2.0 * Math.max(0, settleSteps.size() - 1)));
        } else {
            bloomLen = StepClock.seconds(bloomSteps.isEmpty() ? 5 : 9);
            if (!infuseSteps.isEmpty()) {
                double seconds = (tier == GENTLE ? 13 : tier == PRACTICED ? 14 : 17) + 3.0 * Math.max(0, infuseSteps.size() - 1);
                infuseLen = StepClock.seconds(Math.min(22, seconds));
            }
            double settleSeconds = tier == GENTLE ? 8 : tier == PRACTICED ? 12 : 16;
            settleSeconds = Math.max(settleSeconds, 7.0 + 2.5 * settleSteps.size());
            settleLen = StepClock.seconds(Math.min(16, settleSeconds));
        }
        int large = plan.servings() >= 6 ? StepClock.seconds(4) : plan.servings() == 5 ? StepClock.seconds(3)
                : plan.servings() == 4 ? StepClock.seconds(1.5) : 0;
        if (infuseLen > 0) infuseLen += large;
        else settleLen = Math.min(StepClock.seconds(16), settleLen + large);

        bloomLen = (int) Math.round(bloomLen * t);
        infuseLen = (int) Math.round(infuseLen * t);
        settleLen = (int) Math.round(settleLen * t);

        // Cap: the default cap trims Settle then Infuse (never below what their additions need); assistance may run longer,
        // up to the safety limit.
        int cap = (int) Math.min(KettleParams.SAFETY_LIMIT_STEPS, Math.round(StepClock.seconds(maxSeconds) * t));
        int total = bloomLen + infuseLen + settleLen;
        if (total > cap) {
            int excess = total - cap;
            int settleMin = minimumLength(settleSteps.size(), window);
            int cut = Math.min(excess, Math.max(0, settleLen - settleMin));
            settleLen -= cut;
            excess -= cut;
            if (excess > 0 && infuseLen > 0) {
                int infuseMin = minimumLength(infuseSteps.size(), window);
                cut = Math.min(excess, Math.max(0, infuseLen - infuseMin));
                infuseLen -= cut;
            }
        }

        double inertia = Math.max(0.5, Math.min(2.0, plan.inertia())) * (1.0 + 0.05 * Math.max(0, plan.servings() - 3));
        List<PhaseSpec> phases = new ArrayList<>();
        List<AdditionSpec> additions = new ArrayList<>();
        int cursor = 0;
        if (bloomLen > 0) {
            phases.add(phase(KettlePhase.BLOOM, cursor, bloomLen, plan.bloom(), assist, -1));
            place(additions, bloomSteps, KettlePhase.BLOOM, cursor, bloomLen, window, 0.45);
            cursor += bloomLen;
        }
        if (infuseLen > 0) {
            int reverse = plan.infuse().signature() == Signature.SUSPENDED_BUBBLES ? cursor + (int) Math.round(infuseLen * 0.55) : -1;
            phases.add(phase(KettlePhase.INFUSE, cursor, infuseLen, plan.infuse(), assist, reverse));
            place(additions, infuseSteps, KettlePhase.INFUSE, cursor, infuseLen, window, 0.18);
            cursor += infuseLen;
        }
        phases.add(phase(KettlePhase.SETTLE, cursor, settleLen, plan.settle(), assist, -1));
        place(additions, settleSteps, KettlePhase.SETTLE, cursor, settleLen, window, modifierOnly ? 0.15 : 0.22);

        List<Disturbance> disturbances = DisturbanceSchedule.generate(new SimRandom(seed), tier, phases, additions,
                plan.disturbances(), plan.steps().size());

        double initialHeat;
        double initialBurner;
        int initialDraft;
        int warmup;
        if (modifierOnly) {
            initialHeat = 0.42;
            initialBurner = 0.55;
            initialDraft = 1;
            warmup = 0;
        } else {
            initialHeat = bloomSteps.isEmpty() ? 0.26 : 0.20;
            initialBurner = KettleParams.DRAFT_TARGETS[0];
            initialDraft = 0;
            warmup = (int) Math.round((bloomSteps.isEmpty() ? 40 : 60) * t);
        }
        return new KettleParams(phases, additions, disturbances, BURNER_RESPONSE / (inertia * t), HEAT_RESPONSE / (inertia * t),
                STIR_RESPONSE / (inertia * t), COAST_RESPONSE / inertia, HEAT_FALLOFF * assist.band(), CIRC_FALLOFF * assist.band(),
                initialHeat, initialBurner, initialDraft, warmup, tier);
    }

    private static int minimumLength(int additions, int window) {
        return Math.max(StepClock.seconds(6), 30 + additions * (window + 45) + 20);
    }

    private static PhaseSpec phase(KettlePhase kind, int start, int length, Reaction r, Assist assist, int reverseStep) {
        double hc = (r.heatLow() + r.heatHigh()) / 2;
        double hh = (r.heatHigh() - r.heatLow()) / 2 * assist.band();
        double cc = (r.circLow() + r.circHigh()) / 2;
        double ch = (r.circHigh() - r.circLow()) / 2 * assist.band();
        double circLow = r.circLow() <= 0 ? 0 : Math.max(0, cc - ch);
        return new PhaseSpec(kind, start, length, Math.max(0, hc - hh), Math.min(1, hc + hh), circLow, Math.min(1, cc + ch),
                r.signature(), r.initialTrouble(), reverseStep);
    }

    /**
     * Places the phase's additions: the first opens at {@code firstFraction} of the phase (Bloom waits for the simmer),
     * later ones are evenly spaced with at least the window plus two seconds between openings, and every window closes
     * at least half a second before the phase ends. The cue precedes each window by 1.25 s.
     */
    private static void place(List<AdditionSpec> out, List<Step> steps, KettlePhase phase, int start, int length, int window, double firstFraction) {
        if (steps.isEmpty()) return;
        int k = steps.size();
        int lastClose = start + length - 10;
        int firstOpen = start + Math.max(30, (int) Math.round(length * firstFraction));
        int w = window;
        int spacing = k > 1 ? Math.max(w + 40, (lastClose - w - firstOpen) / (k - 1)) : 0;
        // Squeeze if needed: shorter spacing first, then narrower windows (never below 700 ms).
        while (k > 1 && firstOpen + spacing * (k - 1) + w > lastClose && spacing > w + 20) spacing--;
        while (firstOpen + spacing * (k - 1) + w > lastClose && w > AdditionSpec.MIN_WINDOW) w--;
        while (firstOpen + spacing * (k - 1) + w > lastClose && firstOpen > start + 5) firstOpen--;
        for (int i = 0; i < k; i++) {
            Step s = steps.get(i);
            int open = firstOpen + spacing * i;
            int close = Math.min(open + w, lastClose);
            if (close - open < AdditionSpec.MIN_WINDOW) close = open + AdditionSpec.MIN_WINDOW;
            int cue = Math.max(start, open - 25);
            out.add(new AdditionSpec(out.size(), phase, cue, open, close, s.cooling(), s.kick(), s.item()));
        }
    }

    private KettleSetup() {}
}
