package com.otectus.immersivebrewing.session;

import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettleSimulation;
import com.otectus.immersivebrewing.minigame.distillation.DistillationInput;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.distillation.DistillationSimulation;
import com.otectus.immersivebrewing.minigame.preparation.PreparationInput;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationSimulation;

/** Builds a {@link SimHandle} from encoded parameters (and optional saved state): one code path for client and server. */
public final class SimHandles {

    public static SimHandle create(SessionKind kind, long[] params, long[] state) {
        SimHandle handle = switch (kind) {
            case PREPARATION -> new Preparation(new PreparationSimulation(PreparationParams.decode(params)), params);
            case BREWING -> new Brewing(new KettleSimulation(KettleParams.decode(params)), params);
            case DISTILLATION -> new Distillation(new DistillationSimulation(DistillationParams.decode(params)), params);
        };
        if (state != null && state.length > 0) handle.loadState(state);
        return handle;
    }

    public record Preparation(PreparationSimulation sim, long[] params) implements SimHandle {
        public SessionKind kind() {
            return SessionKind.PREPARATION;
        }

        public void step(int packed) {
            sim.step(PreparationInput.unpack(packed));
        }

        public boolean finished() {
            return sim.finished();
        }

        public int step() {
            return sim.step();
        }

        public int totalSteps() {
            return sim.params().durationSteps();
        }

        public long[] saveState() {
            return sim.saveState();
        }

        public void loadState(long[] state) {
            sim.loadState(state);
        }

        public int neutral(int lastPacked) {
            PreparationInput last = PreparationInput.unpack(lastPacked);
            return new PreparationInput(last.x(), last.y(), false, false, false).pack();
        }

        public int edgeMask() {
            return 1 << 17 | 1 << 18;
        }

        public boolean valid(int packed) {
            return (packed & ~0x7FFFF) == 0;
        }

        public void neutraliseHeld() {
            sim.neutraliseHeldInput();
        }

        public StationVisual visual() {
            return new StationVisual(0, sim.texture().ordinal(), (float) sim.pressure(), (float) sim.lastWork(), (float) sim.processed(),
                    (float) sim.step() / sim.params().durationSteps(), true);
        }
    }

    public record Brewing(KettleSimulation sim, long[] params) implements SimHandle {
        public SessionKind kind() {
            return SessionKind.BREWING;
        }

        public void step(int packed) {
            sim.step(KettleInput.unpack(packed));
        }

        public boolean finished() {
            return sim.finished();
        }

        public int step() {
            return sim.step();
        }

        public int totalSteps() {
            return sim.params().totalSteps();
        }

        public long[] saveState() {
            return sim.saveState();
        }

        public void loadState(long[] state) {
            sim.loadState(state);
        }

        public int neutral(int lastPacked) {
            return KettleInput.neutral(KettleInput.unpack(lastPacked).draft()).pack();
        }

        public int edgeMask() {
            return 1 << 7;
        }

        public boolean valid(int packed) {
            if ((packed & ~0xFF) != 0) return false;
            int draft = packed & 0x3;
            int stir = ((packed >> 2) & 0x1F) - 8;
            return draft <= 2 && stir >= -8 && stir <= 8;
        }

        public void neutraliseHeld() {
            sim.neutraliseHeldInput();
        }

        public StationVisual visual() {
            return new StationVisual(sim.phase().phase().ordinal(), sim.phase().signature().ordinal(), (float) sim.heat(),
                    (float) sim.circulation(), (float) sim.trouble(), (float) sim.step() / sim.params().totalSteps(), true);
        }
    }

    public record Distillation(DistillationSimulation sim, long[] params) implements SimHandle {
        public SessionKind kind() {
            return SessionKind.DISTILLATION;
        }

        public void step(int packed) {
            sim.step(DistillationInput.unpack(packed));
        }

        public boolean finished() {
            return sim.finished();
        }

        public int step() {
            return sim.step();
        }

        public int totalSteps() {
            return sim.params().durationSteps();
        }

        public long[] saveState() {
            return sim.saveState();
        }

        public void loadState(long[] state) {
            sim.loadState(state);
        }

        public int neutral(int lastPacked) {
            return lastPacked;
        }

        public int edgeMask() {
            return 0;
        }

        public boolean valid(int packed) {
            if ((packed & ~0x7F) != 0) return false;
            return (packed & 0x7) < DistillationInput.LEVELS && (packed >> 3 & 0x7) < DistillationInput.LEVELS;
        }

        public void neutraliseHeld() {}

        public StationVisual visual() {
            return new StationVisual(0, sim.condensate().ordinal(), (float) sim.temperature(), (float) sim.coolant(), (float) sim.fraction(),
                    (float) sim.fraction(), true);
        }
    }

    private SimHandles() {}
}
