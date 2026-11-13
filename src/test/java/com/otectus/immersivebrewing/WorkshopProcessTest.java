package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic domain tests for the hands-on workshop (handoff 21): bounded work from tool contact, no farming from
 * stationary, repeated, teleporting or bursty input, packet-rate independence, reachable completion for every reaction
 * family, and save/load preserving every cumulative value.
 */
class WorkshopProcessTest {
    private static KettleProcess.Setup setup(ReactionFamily family, int steps) {
        double[] cooling = new double[steps];
        double[] kick = new double[steps];
        for (int i = 0; i < steps; i++) {
            cooling[i] = 0.06;
            kick[i] = family.kick;
        }
        return new KettleProcess.Setup(family, family.idealLow, family.idealHigh, Math.max(0.2, family.idealLow - 0.12),
                Math.min(0.95, family.idealHigh + 0.14), 1.0, 9, family == ReactionFamily.DENSE_SEDIMENT ? 4 : 3, cooling, kick, "test", 1);
    }

    /** A tender that heats to the centre of the band, doses when allowed, stirs at the family's comfortable speed and rests. */
    private static final class Tender {
        final KettleProcess p;
        final ToolTrack track = new ToolTrack(WorkshopParts.KETTLE_HX, WorkshopParts.KETTLE_HZ);
        final double speedPx;
        final boolean stirs;
        final int samplesEvery;
        long tick;
        double angle;

        Tender(KettleProcess p, double speedPx, boolean stirs, int samplesEvery) {
            this.p = p;
            this.speedPx = speedPx;
            this.stirs = stirs;
            this.samplesEvery = samplesEvery;
        }

        /** Runs until the batch is ready to commit or {@code maxSeconds} pass; returns the simulated seconds. */
        double run(double maxSeconds) {
            for (int i = 0; i < p.portions(); i++) p.pour(i);
            int target = (int) Math.round((p.setup().idealLow() + p.setup().idealHigh()) / 2 * KettleProcess.BURNER_STEPS);
            while (tick < maxSeconds * 20) {
                tick++;
                KettleProcess.Stage stage = p.stage();
                if (stage == KettleProcess.Stage.RESTING) p.setBurner(0);
                else p.setBurner(target);
                if ((stage == KettleProcess.Stage.HEATING || stage == KettleProcess.Stage.WAITING_DOSE)
                        && p.temperature() >= p.setup().workLow()) p.dose(p.doses());
                boolean stir = stirs && stage == KettleProcess.Stage.REACTING && (p.undissolved() > 0.02 || p.mixTrouble() > 0.15);
                if (stir && tick % samplesEvery == 0) {
                    // Circular motion at the requested speed around the vessel centre; one sample per samplesEvery ticks.
                    double radiusPx = 3.0;
                    angle += speedPx * 0.05 * samplesEvery / radiusPx;
                    track.sample(Math.cos(angle) * radiusPx / WorkshopParts.KETTLE_HX, Math.sin(angle) * radiusPx / WorkshopParts.KETTLE_HZ, true, tick);
                }
                double px = stir ? track.take(tick) : 0;
                p.tick(px, track.broadness());
                if (p.readyToFinalize()) return tick / 20.0;
            }
            return -1;
        }
    }

    private static double comfortableSpeed(ReactionFamily f) {
        return switch (f) {
            case LIGHT_FOAM, DELICATE_SHIMMER, SUSPENDED_BUBBLES, FLOATING_MOTES -> 10;
            case MAGMA_STREAKS -> 14;
            default -> 16;
        };
    }

    @Test
    void everyFamilyCompletesWithCompetentHandling() {
        for (ReactionFamily f : ReactionFamily.values()) {
            KettleProcess p = KettleProcess.begin(setup(f, 2), new int[] {0, 1, 2}, UUID.randomUUID());
            double seconds = new Tender(p, comfortableSpeed(f), true, 1).run(120);
            assertTrue(seconds > 0, f + " never became ready");
            assertTrue(seconds < 75, f + " took " + seconds + " s");
            int c = p.computeControl();
            assertTrue(c >= 80, f + " competent control was only " + c);
        }
    }

    @Test
    void everyFamilyCompletesWithoutAnyStirring() {
        // Stirring is never mandatory: dissolution also proceeds by convection, and every family settles at rest.
        for (ReactionFamily f : ReactionFamily.values()) {
            KettleProcess p = KettleProcess.begin(setup(f, 2), new int[] {0}, UUID.randomUUID());
            double seconds = new Tender(p, 0, false, 1).run(300);
            assertTrue(seconds > 0, f + " deadlocked without stirring");
        }
    }

    @Test
    void sampleRateDoesNotChangeTheOutcome() {
        for (ReactionFamily f : new ReactionFamily[] {ReactionFamily.GENERIC, ReactionFamily.DENSE_SEDIMENT, ReactionFamily.LIGHT_FOAM}) {
            KettleProcess fast = KettleProcess.begin(setup(f, 2), new int[] {0, 1}, null);
            KettleProcess slow = KettleProcess.begin(setup(f, 2), new int[] {0, 1}, null);
            double a = new Tender(fast, comfortableSpeed(f), true, 1).run(120);
            double b = new Tender(slow, comfortableSpeed(f), true, 2).run(120);
            assertTrue(a > 0 && b > 0);
            assertTrue(Math.abs(fast.computeControl() - slow.computeControl()) <= 4,
                    f + ": 20 Hz " + fast.computeControl() + " vs 10 Hz " + slow.computeControl());
            assertTrue(Math.abs(a - b) < 6, f + ": " + a + " s vs " + b + " s");
        }
    }

    @Test
    void stationaryRepeatedAndTeleportingInputEarnsNothing() {
        ToolTrack t = new ToolTrack(5, 4);
        for (int i = 0; i < 40; i++) t.sample(0.3, 0.2, true, i);
        double total = 0;
        for (int i = 0; i < 40; i++) total += t.take(i);
        assertEquals(0, total, 1e-9, "a stationary tool did work");

        ToolTrack jump = new ToolTrack(5, 4);
        jump.sample(-1, 0, true, 0);
        jump.sample(1, 0, true, 1); // opposite edges in one step: 10 px
        assertEquals(0, jump.take(1), 1e-9, "a teleport was credited");
        assertEquals(1, jump.teleports());

        ToolTrack air = new ToolTrack(5, 4);
        for (int i = 0; i < 20; i++) air.sample(Math.cos(i), Math.sin(i), false, i);
        double airWork = 0;
        for (int i = 0; i < 20; i++) airWork += air.take(i);
        assertEquals(0, airWork, 1e-9, "motion through the air did work");
    }

    @Test
    void burstsCanNotBuyMoreThanTheStepCap() {
        ToolTrack t = new ToolTrack(5, 4);
        t.sample(0, 0, true, 0);
        // Twenty packets inside one server step, each a believable move.
        double x = 0;
        for (int i = 0; i < 20; i++) {
            x = x > 0 ? -0.6 : 0.6;
            t.sample(x, 0, true, 1);
        }
        assertTrue(t.take(1) <= ToolTrack.MAX_STEP_PX + 1e-9);
        double rest = 0;
        for (int i = 2; i < 30; i++) rest += t.take(i);
        assertTrue(rest <= ToolTrack.MAX_BANK_PX + 1e-9, "banked " + rest);
        // After a long gap the next point starts afresh.
        t.sample(1, 0, true, 100);
        assertEquals(0, t.take(100), 1e-9);
    }

    @Test
    void stirringAfterTheTreatmentCanNotFarmQuality() {
        KettleProcess p = KettleProcess.begin(setup(ReactionFamily.GENERIC, 1), new int[] {0}, null);
        new Tender(p, 16, true, 1).run(120);
        int before = p.computeControl();
        ToolTrack t = new ToolTrack(5, 4);
        for (int i = 0; i < 400; i++) {
            t.sample(Math.cos(i * 0.3) * 0.6, Math.sin(i * 0.3) * 0.6, true, 10000 + i);
            p.tick(t.take(10000 + i), t.broadness());
        }
        assertEquals(before, p.computeControl(), "stirring a finished treatment changed the score");
    }

    @Test
    void savingPreservesEveryCumulativeValue() {
        KettleProcess p = KettleProcess.begin(setup(ReactionFamily.MAGMA_STREAKS, 3), new int[] {0, 2, 4}, UUID.randomUUID());
        Tender tender = new Tender(p, 14, true, 1);
        for (int i = 0; i < p.portions(); i++) p.pour(i);
        p.setBurner(5);
        for (int i = 0; i < 200; i++) {
            if (p.doseProblem(p.doses()) == null && p.temperature() > p.setup().workLow()) p.dose(p.doses());
            p.tick(i % 2 == 0 ? 0.8 : 0, 0.5);
        }
        CompoundTag saved = p.save();
        KettleProcess copy = KettleProcess.load(saved);
        assertEquals(saved, copy.save(), "a save/load round trip changed the process");
        assertEquals(p.computeControl(), copy.computeControl());
        assertEquals(p.doses(), copy.doses());
        assertTrue(tender.p == p);
    }

    @Test
    void mortarReachesTheEndpointAndOverworkLowersIt() {
        for (PreparationMode mode : PreparationMode.values()) {
            MortarProcess m = MortarProcess.begin(new MortarProcess.Setup(mode, 1, 0.6, 0.78, 0.86, 0.25, 0.7, 1.0, "test", 1), null);
            int seconds = workUntilReady(m, Effort.NORMAL);
            assertTrue(seconds > 0, mode + " never became ready");
            assertTrue(seconds <= 20, mode + " took " + seconds + " s for one unit");
            int best = m.evaluate().extraction();
            assertTrue(best >= 85, mode + " careful extraction only " + best);
            // Indefinite grinding past the endpoint is never rewarded.
            for (int i = 0; i < 400; i++) m.grind(2.0, Math.cos(i * 0.4) * 0.7, Math.sin(i * 0.4) * 0.7, Effort.NORMAL.value);
            assertTrue(m.evaluate().extraction() < best, mode + " overwork did not lower extraction");
        }
    }

    @Test
    void fourUnitsShareTheWorkWithoutFourRepeats() {
        MortarProcess one = MortarProcess.begin(new MortarProcess.Setup(PreparationMode.CRYSTALLINE, 1, 0.6, 0.78, 0.86, 0.25, 0.7, 1.0, "t", 1), null);
        MortarProcess four = MortarProcess.begin(new MortarProcess.Setup(PreparationMode.CRYSTALLINE, 4, 0.6, 0.78, 0.86, 0.25, 0.7, 1.0, "t", 1), null);
        int a = workUntilReady(one, Effort.NORMAL);
        int b = workUntilReady(four, Effort.NORMAL);
        assertTrue(a > 0 && b > 0);
        assertTrue(b < a * 2.2 && b > a, "one unit " + a + " s, four units " + b + " s");
    }

    @Test
    void everyEffortCanReachTheSameCeiling() {
        // Accessibility presets change how input is expressed, never the attainable result.
        int gentle = bestExtraction(Effort.GENTLE), normal = bestExtraction(Effort.NORMAL);
        assertTrue(gentle >= 85 && normal >= 85, "gentle " + gentle + ", normal " + normal);
    }

    private static int bestExtraction(Effort effort) {
        MortarProcess m = MortarProcess.begin(new MortarProcess.Setup(PreparationMode.FIBROUS, 2, 0.55, 0.75, 0.85, 0.2, 0.7, 1.0, "t", 1), null);
        return workUntilReady(m, effort) > 0 ? m.evaluate().extraction() : 0;
    }

    /** A keyboard-like routine: presses and short arcs over each zone in turn, folds when uneven, stops in the band. */
    private static int workUntilReady(MortarProcess m, Effort effort) {
        double[][] zones = {{0, 0}, {0.7, 0}, {0, 0.7}, {-0.7, 0}, {0, -0.7}};
        for (int tick = 1; tick <= 20 * 60; tick++) {
            MortarProcess.Evaluation e = m.evaluate();
            if (e.processed() >= m.setup().endpointLow() + 0.03 && e.spread() <= 0.1 && e.clump() < 0.1) return tick / 20;
            if (e.spread() > 0.12 && e.processed() > 0.2 && tick % 40 == 0) {
                m.fold(3.0);
            } else {
                int zone = (tick / 30) % zones.length;
                double[] z = zones[zone];
                if (tick % 30 < 3) m.press(z[0], z[1], effort.value, tick);
                else m.grind(1.0, z[0] + Math.cos(tick * 0.5) * 0.15, z[1] + Math.sin(tick * 0.5) * 0.15, effort.value);
            }
            m.tick();
        }
        return -1;
    }

    @Test
    void alembicRewardsRoutingTheClearHeart() {
        AlembicProcess good = AlembicProcess.begin(new AlembicProcess.Setup(1, 3, 2, 70, 50, 250), null);
        AlembicProcess careless = AlembicProcess.begin(new AlembicProcess.Setup(1, 3, 2, 70, 50, 250), null);
        for (AlembicProcess p : new AlembicProcess[] {good, careless}) {
            p.setClamped(true);
            p.setBurner(3);
            p.setValve(2);
        }
        careless.setDiverter(true);
        int ticks = 0;
        while (!good.finished() && ticks < 20 * 90) {
            ticks++;
            AlembicProcess.Condensate c = good.condensate();
            good.setDiverter(c == AlembicProcess.Condensate.CLEAR);
            good.tick();
            careless.tick();
        }
        assertTrue(good.finished(), "the run never finished");
        assertTrue(ticks / 20 <= 40, "a purification took " + ticks / 20 + " s");
        assertTrue(good.distillation() >= 80, "competent routing only " + good.distillation());
        assertTrue(careless.distillation() < good.distillation(), "collecting everything scored as well as routing");
    }

    @Test
    void anIdleStillWaitsWithoutPenalty() {
        AlembicProcess p = AlembicProcess.begin(new AlembicProcess.Setup(1, 3, 2, 70, 50, 250), null);
        for (int i = 0; i < 2000; i++) p.tick();
        assertTrue(p.untouched(), "an unclamped, unlit still made progress");
        assertFalse(p.finished());
        assertEquals(0, p.thermal(), 1e-9);
    }
}
