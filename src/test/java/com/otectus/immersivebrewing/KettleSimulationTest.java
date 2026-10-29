package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.minigame.StepClock;
import com.otectus.immersivebrewing.minigame.brewing.AdditionSpec;
import com.otectus.immersivebrewing.minigame.brewing.BrewScore;
import com.otectus.immersivebrewing.minigame.brewing.Disturbance;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.minigame.brewing.KettleSimulation;
import com.otectus.immersivebrewing.minigame.brewing.PhaseSpec;
import com.otectus.immersivebrewing.minigame.brewing.Signature;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.testing.KettleBot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 8, 26.1: determinism, fairness, bounds and timing of the kettle simulation across every reaction signature. */
class KettleSimulationTest {
    static final KettleSetup.Reaction[] INFUSE = {
            new KettleSetup.Reaction(Signature.NONE, 0.46, 0.60, 0.25, 0.55, 0),
            new KettleSetup.Reaction(Signature.LIGHT_FOAM, 0.46, 0.60, 0.30, 0.58, 0),
            new KettleSetup.Reaction(Signature.DENSE_SEDIMENT, 0.50, 0.64, 0.25, 0.52, 0),
            new KettleSetup.Reaction(Signature.DELICATE_SHIMMER, 0.38, 0.52, 0.10, 0.36, 0),
            new KettleSetup.Reaction(Signature.MAGMA_STREAKS, 0.50, 0.66, 0.40, 0.70, 0),
            new KettleSetup.Reaction(Signature.SUSPENDED_BUBBLES, 0.44, 0.58, 0.25, 0.55, 0),
            new KettleSetup.Reaction(Signature.DARK_WHORLS, 0.42, 0.56, 0.05, 0.30, 0),
            new KettleSetup.Reaction(Signature.ALIGNING_GLINTS, 0.44, 0.58, 0.20, 0.45, 0),
            new KettleSetup.Reaction(Signature.FLOATING_MOTES, 0.44, 0.58, 0.15, 0.40, 0),
    };

    static KettleSetup.Step step(KettlePhase phase, KettleSetup.Role role, String item, boolean lingering) {
        return new KettleSetup.Step(phase, role, item, 0.07, 0.3, lingering);
    }

    /** water -> awkward -> effect (gentle) */
    static KettleSetup.Plan gentle(KettleSetup.Reaction infuse, int servings) {
        return new KettleSetup.Plan(List.of(step(KettlePhase.BLOOM, KettleSetup.Role.BASE, "minecraft:nether_wart", false),
                step(KettlePhase.INFUSE, KettleSetup.Role.EFFECT, "minecraft:sugar", false)),
                KettleSetup.DEFAULT_BLOOM, infuse, KettleSetup.DEFAULT_SETTLE, List.of(Disturbance.Kind.values()), 1.0, servings);
    }

    /** water -> awkward -> effect -> long (practiced) */
    static KettleSetup.Plan practiced(KettleSetup.Reaction infuse) {
        return new KettleSetup.Plan(List.of(step(KettlePhase.BLOOM, KettleSetup.Role.BASE, "minecraft:nether_wart", false),
                step(KettlePhase.INFUSE, KettleSetup.Role.EFFECT, "minecraft:sugar", false),
                step(KettlePhase.SETTLE, KettleSetup.Role.MODIFIER, "minecraft:redstone", false)),
                KettleSetup.DEFAULT_BLOOM, infuse, KettleSetup.DEFAULT_SETTLE, List.of(Disturbance.Kind.values()), 1.0, 3);
    }

    /** water -> awkward -> effect -> corrupted -> splash -> lingering (intricate) */
    static KettleSetup.Plan intricate(KettleSetup.Reaction infuse, int servings) {
        return new KettleSetup.Plan(List.of(step(KettlePhase.BLOOM, KettleSetup.Role.BASE, "minecraft:nether_wart", false),
                step(KettlePhase.INFUSE, KettleSetup.Role.EFFECT, "minecraft:spider_eye", false),
                step(KettlePhase.INFUSE, KettleSetup.Role.CORRUPTION, "minecraft:fermented_spider_eye", false),
                step(KettlePhase.SETTLE, KettleSetup.Role.CONTAINER, "minecraft:gunpowder", false),
                step(KettlePhase.SETTLE, KettleSetup.Role.CONTAINER, "minecraft:dragon_breath", true)),
                KettleSetup.DEFAULT_BLOOM, infuse, KettleSetup.DEFAULT_SETTLE, List.of(Disturbance.Kind.values()), 1.0, servings);
    }

    static KettleSetup.Plan modifierOnly() {
        return new KettleSetup.Plan(List.of(step(KettlePhase.SETTLE, KettleSetup.Role.MODIFIER, "minecraft:redstone", false)),
                KettleSetup.DEFAULT_BLOOM, INFUSE[1], KettleSetup.DEFAULT_SETTLE, List.of(Disturbance.Kind.values()), 1.0, 3);
    }

    static BrewScore play(KettleParams params, KettleBot bot) {
        KettleSimulation sim = new KettleSimulation(params);
        while (!sim.finished()) sim.step(bot.decide(sim));
        return sim.score();
    }

    static KettleParams build(KettleSetup.Plan plan, long seed) {
        return KettleSetup.build(plan, seed, 50, KettleSetup.Assist.NONE);
    }

    @Test
    void sameSeedAndInputsGiveTheSameResult() {
        KettleParams params = build(gentle(INFUSE[1], 3), 42);
        KettleSimulation a = new KettleSimulation(params);
        KettleSimulation b = new KettleSimulation(KettleParams.decode(params.encode()));
        KettleBot botA = KettleBot.competent();
        KettleBot botB = KettleBot.competent();
        while (!a.finished()) {
            a.step(botA.decide(a));
            b.step(botB.decide(b));
        }
        assertArrayEquals(a.saveState(), b.saveState());
        assertEquals(a.score().control(), b.score().control());
    }

    @Test
    void competentReachesMasterworkOnEverySignature() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            for (long seed = 0; seed < 40; seed++) {
                BrewScore score = play(build(gentle(infuse, 3), seed), KettleBot.competent());
                int q = QualityMath.quality(100, score.control());
                assertTrue(score.control() >= 85, infuse.signature() + " seed " + seed + ": C=" + score.control() + " t=" + score.thermal()
                        + " c=" + score.circulation() + " d=" + score.dosing());
                assertTrue(q >= 90 || score.control() >= 87, infuse.signature() + " seed " + seed + ": Q=" + q);
            }
        }
    }

    @Test
    void everyGeneratedScheduleIsPlayable() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            for (long seed = 0; seed < 60; seed++) {
                for (KettleSetup.Plan plan : List.of(practiced(infuse), intricate(infuse, 6), modifierOnly())) {
                    BrewScore score = play(build(plan, seed), KettleBot.competent());
                    assertTrue(score.control() >= 80, infuse.signature() + " seed " + seed + " " + plan.steps().size() + " steps: C=" + score.control()
                            + " t=" + score.thermal() + " c=" + score.circulation() + " d=" + score.dosing());
                }
            }
        }
    }

    @Test
    void deliberateCoastingKeepsParticipationWithoutRepeatedTaps() {
        KettleParams params = build(gentle(INFUSE[0], 3), 91);
        KettleSimulation sim = new KettleSimulation(params);
        while (!sim.finished()) {
            PhaseSpec phase = params.phaseAt(sim.step());
            // One meaningful adjustment per phase, then deliberately keep the chosen burner setting.
            int draft = sim.step() == phase.start() ? (sim.draft() + 1) % 3 : sim.draft();
            sim.step(new KettleInput(draft, 0, false));
        }
        for (boolean counted : sim.score().phaseCounted()) assertTrue(counted, "coasting does not erase engagement");
        assertEquals(0, play(params, KettleBot.idle()).control(), "fully unattended play still receives no credit");
    }

    @Test
    void idleScoresZeroAndPoorScoresBelowCompetent() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            KettleParams params = build(gentle(infuse, 3), 7);
            assertEquals(0, play(params, KettleBot.idle()).control(), "idle " + infuse.signature());
            int competent = play(params, KettleBot.competent()).control();
            for (long seed = 1; seed < 6; seed++) {
                int poor = play(params, KettleBot.poor(seed)).control();
                assertTrue(poor < competent - 20, infuse.signature() + ": poor " + poor + " vs competent " + competent);
            }
        }
    }

    @Test
    void sealingAndResumingPreservesTheResult() {
        KettleParams params = build(intricate(INFUSE[5], 4), 11);
        KettleSimulation straight = new KettleSimulation(params);
        KettleBot bot = KettleBot.competent();
        while (!straight.finished()) straight.step(bot.decide(straight));

        KettleSimulation first = new KettleSimulation(params);
        KettleBot bot2 = KettleBot.competent();
        while (first.step() < params.totalSteps() / 2) first.step(bot2.decide(first));
        long[] sealed = first.saveState();
        KettleSimulation resumed = new KettleSimulation(KettleParams.decode(params.encode()));
        resumed.loadState(sealed);
        resumed.neutraliseHeldInput();
        while (!resumed.finished()) resumed.step(bot2.decide(resumed));
        assertArrayEquals(straight.saveState(), resumed.saveState());
    }

    @Test
    void stateStaysFiniteAndBoundedUnderArbitraryInput() {
        KettleParams params = build(intricate(INFUSE[2], 6), 3);
        KettleSimulation sim = new KettleSimulation(params);
        java.util.Random r = new java.util.Random(5);
        while (!sim.finished()) {
            sim.step(new KettleInput(r.nextInt(3), r.nextInt(17) - 8, r.nextInt(5) == 0));
            assertTrue(sim.heat() >= 0 && sim.heat() <= 1 && Double.isFinite(sim.heat()));
            assertTrue(sim.circulation() >= -1 && sim.circulation() <= 1);
            assertTrue(sim.trouble() >= 0 && sim.trouble() <= 1);
        }
        int c = sim.score().control();
        assertTrue(c >= 0 && c <= 100);
    }

    @Test
    void durationsFollowTheTiers() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            for (long seed = 0; seed < 5; seed++) {
                double gentle = build(gentle(infuse, 3), seed).totalSteps() / (double) StepClock.STEPS_PER_SECOND;
                double practiced = build(practiced(infuse), seed).totalSteps() / (double) StepClock.STEPS_PER_SECOND;
                double intricate = build(intricate(infuse, 3), seed).totalSteps() / (double) StepClock.STEPS_PER_SECOND;
                double large = build(intricate(infuse, 6), seed).totalSteps() / (double) StepClock.STEPS_PER_SECOND;
                double modifier = build(modifierOnly(), seed).totalSteps() / (double) StepClock.STEPS_PER_SECOND;
                assertTrue(gentle >= 28 && gentle <= 32, "gentle " + gentle);
                assertTrue(practiced >= 32 && practiced <= 40, "practiced " + practiced);
                assertTrue(intricate >= 38 && intricate <= 46, "intricate " + intricate);
                assertTrue(large <= 50 && large - intricate <= 4.01, "large " + large);
                assertTrue(modifier >= 10 && modifier <= 16, "modifier-only " + modifier);
            }
        }
    }

    @Test
    void additionWindowsAndInterventionsFollowTheContract() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            for (long seed = 0; seed < 30; seed++) {
                for (KettleSetup.Plan plan : List.of(gentle(infuse, 3), practiced(infuse), intricate(infuse, 3), intricate(infuse, 6))) {
                    KettleParams p = build(plan, seed);
                    for (AdditionSpec a : p.additions()) {
                        assertTrue(a.closeStep() - a.openStep() >= AdditionSpec.MIN_WINDOW, "window under 700 ms");
                        PhaseSpec phase = p.phaseAt(a.openStep());
                        assertEquals(a.phase(), phase.phase(), "window opens in its own phase");
                        assertTrue(a.closeStep() <= phase.end(), "window closes inside its phase");
                    }
                    int interventions = p.additions().size() + p.disturbances().size();
                    int tier = KettleSetup.tier(plan);
                    if (tier == KettleSetup.GENTLE) assertTrue(interventions >= 2 && interventions <= 3, "gentle interventions " + interventions);
                    if (tier == KettleSetup.INTRICATE) assertTrue(interventions >= 4, "intricate interventions " + interventions);
                    // At most two control concerns at once: no disturbance lands inside an addition's cue-to-close span.
                    for (Disturbance d : p.disturbances()) {
                        for (AdditionSpec a : p.additions()) {
                            assertTrue(d.step() < a.cueStep() - 10 || d.warnStep() > a.closeStep(), "disturbance overlaps an addition");
                        }
                    }
                }
            }
        }
    }

    @Test
    void sampledSchedulesNeverStackWarningsWithAdditionWindowsOrEachOther() {
        for (KettleSetup.Reaction infuse : INFUSE) {
            for (long seed = 0; seed < 400; seed++) {
                KettleParams params = build(intricate(infuse, 6), seed);
                for (Disturbance disturbance : params.disturbances()) {
                    PhaseSpec phase = params.phaseAt(disturbance.step());
                    assertTrue(disturbance.warnStep() >= phase.start() - StepClock.seconds(0.5), "warning starts too far before its phase: " + seed);
                    assertTrue(disturbance.step() < phase.end(), "disturbance lands after its phase: " + seed);
                    for (AdditionSpec addition : params.additions()) {
                        boolean separate = disturbance.step() < addition.cueStep() || disturbance.warnStep() > addition.closeStep();
                        assertTrue(separate, "warning overlaps addition cue/window: seed=" + seed + " " + disturbance + " " + addition);
                    }
                    for (Disturbance other : params.disturbances()) {
                        if (other == disturbance) continue;
                        assertTrue(Math.abs(other.step() - disturbance.step()) >= StepClock.seconds(5),
                                "contradictory disturbance cluster: seed=" + seed + " " + disturbance + " " + other);
                    }
                }
            }
        }
    }

    @Test
    void assistanceStaysWithinTheSafetyLimit() {
        KettleParams p = KettleSetup.build(intricate(INFUSE[4], 6), 9, 50, new KettleSetup.Assist(1.5, 1.25));
        assertTrue(p.totalSteps() <= KettleParams.SAFETY_LIMIT_STEPS);
        KettleParams over = KettleSetup.build(intricate(INFUSE[4], 6), 9, 50, new KettleSetup.Assist(9, 9));
        assertEquals(p.totalSteps(), over.totalSteps(), "requests above the cap are clamped");
    }

    @Test
    void missedAdditionsAreMadeAutomaticallyAndScoreNothing() {
        KettleParams params = build(gentle(INFUSE[1], 3), 2);
        KettleSimulation sim = new KettleSimulation(params);
        KettleBot bot = KettleBot.competent();
        while (!sim.finished()) {
            KettleInput in = bot.decide(sim);
            sim.step(new KettleInput(in.draft(), in.stir(), false));
        }
        BrewScore score = sim.score();
        for (KettleSimulation.Outcome o : score.outcomes()) assertEquals(KettleSimulation.Outcome.AUTOMATIC, o);
        assertEquals(0.0, score.dosing(), 1e-9);
        List<String> tipped = new ArrayList<>(List.of(score.tip().key()));
        assertTrue(tipped.get(0).contains("missed"), tipped.toString());
    }
}
