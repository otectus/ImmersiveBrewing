package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.minigame.preparation.PreparationInput;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationScore;
import com.otectus.immersivebrewing.minigame.preparation.PreparationSimulation;
import com.otectus.immersivebrewing.testing.MortarBot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 6, 26.1: the Mortar simulation is deterministic, rewards judgement, and can not be sped up by input spam. */
class PreparationSimulationTest {
    static PreparationParams params(PreparationMode mode, int kits) {
        return switch (mode) {
            case CRYSTALLINE -> PreparationParams.of(mode, 240, 0.60, 0.78, 0.70, 0.86, kits, 1.0);
            case FIBROUS -> PreparationParams.of(mode, 260, 0.55, 0.75, 0.60, 0.85, kits, 1.0);
            case SOFT -> PreparationParams.of(mode, 240, 0.55, 0.75, 0.50, 0.85, kits, 1.0);
            case BRITTLE -> PreparationParams.of(mode, 220, 0.58, 0.76, 0.65, 0.86, kits, 1.0);
            case LIQUID -> PreparationParams.of(mode, 260, 0.60, 0.80, 0.80, 0.90, kits, 1.0);
        };
    }

    static PreparationScore play(PreparationParams params, MortarBot bot) {
        PreparationSimulation sim = new PreparationSimulation(params);
        while (!sim.finished()) sim.step(bot.decide(sim));
        return sim.score();
    }

    @Test
    void competentPreparationExtractsWell() {
        for (PreparationMode mode : PreparationMode.values()) {
            for (int kits = 1; kits <= 4; kits++) {
                PreparationScore score = play(params(mode, kits), MortarBot.competent());
                assertTrue(score.extraction() >= 85, mode + " x" + kits + ": E=" + score.extraction() + " acc=" + score.accuracy() + " res=" + score.restraint());
            }
        }
    }

    @Test
    void idleScoresZeroAndPoorScoresLower() {
        for (PreparationMode mode : PreparationMode.values()) {
            assertEquals(0, play(params(mode, 1), MortarBot.idle()).extraction(), mode + " idle");
            int competent = play(params(mode, 1), MortarBot.competent()).extraction();
            int poor = play(params(mode, 1), MortarBot.poor(3)).extraction();
            assertTrue(poor < competent - 15, mode + ": poor " + poor + " vs competent " + competent);
        }
    }

    @Test
    void determinismAndSealResume() {
        PreparationParams p = params(PreparationMode.FIBROUS, 2);
        PreparationSimulation a = new PreparationSimulation(p);
        MortarBot bot = MortarBot.competent();
        while (!a.finished()) a.step(bot.decide(a));
        PreparationSimulation b = new PreparationSimulation(PreparationParams.decode(p.encode()));
        MortarBot bot2 = MortarBot.competent();
        while (b.step() < 60 && !b.finished()) b.step(bot2.decide(b));
        PreparationSimulation c = new PreparationSimulation(p);
        c.loadState(b.saveState());
        while (!c.finished()) c.step(bot2.decide(c));
        assertEquals(a.score().extraction(), c.score().extraction());
    }

    /** Spec 6: high DPI, event spam and very fast circles must not produce more extraction. */
    @Test
    void motionIsCappedPerStep() {
        PreparationParams p = params(PreparationMode.CRYSTALLINE, 1);
        PreparationSimulation capped = new PreparationSimulation(p);
        PreparationSimulation spam = new PreparationSimulation(p);
        double angle = 0;
        for (int i = 0; i < 80; i++) {
            angle += 0.16;
            capped.step(PreparationInput.of(Math.cos(angle) * 0.6, Math.sin(angle) * 0.6, true, false, false));
            // Teleport to the opposite side every step: as far as the target can be.
            spam.step(PreparationInput.of(i % 2 == 0 ? 1 : -1, i % 2 == 0 ? 1 : -1, true, false, false));
        }
        assertTrue(spam.processed() <= capped.processed() * 1.05 + 1e-9, "spam " + spam.processed() + " vs capped " + capped.processed());
    }

    @Test
    void encodedParamsRoundTrip() {
        PreparationParams p = params(PreparationMode.LIQUID, 3);
        assertEquals(p, PreparationParams.decode(p.encode()));
        PreparationSimulation sim = new PreparationSimulation(p);
        for (int i = 0; i < 40; i++) sim.step(PreparationInput.of(0.5, 0.2, i % 3 == 0, i == 20, false));
        PreparationSimulation copy = new PreparationSimulation(p);
        copy.loadState(sim.saveState());
        assertArrayEquals(sim.saveState(), copy.saveState());
    }
}
