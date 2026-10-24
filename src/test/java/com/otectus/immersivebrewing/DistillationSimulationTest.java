package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.minigame.StepClock;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.distillation.DistillationScore;
import com.otectus.immersivebrewing.minigame.distillation.DistillationSimulation;
import com.otectus.immersivebrewing.testing.DistillBot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 9.1, 26.1: routing and control decide D; a sloppy run collects contamination; a run takes 14-22 s. */
class DistillationSimulationTest {
    static DistillationScore play(DistillationParams params, DistillBot bot, int[] steps) {
        DistillationSimulation sim = new DistillationSimulation(params);
        while (!sim.finished()) sim.step(bot.decide(sim));
        steps[0] = sim.step();
        return sim.score();
    }

    @Test
    void competentRoutingScoresHighAndFinishesInTime() {
        for (long seed = 0; seed < 50; seed++) {
            int[] steps = new int[1];
            DistillationScore score = play(DistillationParams.create(seed, 1, 1), DistillBot.competent(), steps);
            assertTrue(score.distillation() >= 85, "seed " + seed + ": D=" + score.distillation() + " coll=" + score.collection()
                    + " t=" + score.thermal() + " c=" + score.cooling());
            double seconds = steps[0] / (double) StepClock.STEPS_PER_SECOND;
            assertTrue(seconds >= 14 && seconds <= 22, "seed " + seed + ": " + seconds + " s");
            assertTrue(score.completed(), "the run completes before the deadline");
        }
    }

    @Test
    void sloppyAndIdleScoreLower() {
        DistillationParams p = DistillationParams.create(3, 1, 1);
        int[] steps = new int[1];
        int competent = play(p, DistillBot.competent(), steps).distillation();
        int sloppy = play(p, DistillBot.sloppy(), steps).distillation();
        assertTrue(sloppy < 35, "collecting everything contaminates the heart: " + sloppy);
        assertTrue(sloppy < competent);
        assertEquals(0, play(p, DistillBot.idle(), steps).distillation());
    }
}
