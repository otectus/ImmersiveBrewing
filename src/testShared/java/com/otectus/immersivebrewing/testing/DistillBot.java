package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.minigame.distillation.DistillationInput;
import com.otectus.immersivebrewing.minigame.distillation.DistillationSimulation;

/**
 * Reference Alembic players. {@link #competent} sets both valves to their middle and switches the receiver on the
 * condensate's own cue (clear in, haze out); {@link #sloppy} collects everything; {@link #idle} does nothing.
 */
public final class DistillBot {
    public enum Style { COMPETENT, SLOPPY, IDLE }

    private final Style style;

    public DistillBot(Style style) {
        this.style = style;
    }

    public static DistillBot competent() {
        return new DistillBot(Style.COMPETENT);
    }

    public static DistillBot sloppy() {
        return new DistillBot(Style.SLOPPY);
    }

    public static DistillBot idle() {
        return new DistillBot(Style.IDLE);
    }

    public DistillationInput decide(DistillationSimulation sim) {
        return switch (style) {
            case IDLE -> new DistillationInput(sim.heatLevel(), sim.coolLevel(), sim.collecting());
            case SLOPPY -> new DistillationInput(3, 1, true);
            case COMPETENT -> {
                DistillationSimulation.Condensate c = sim.condensate();
                boolean collect = c == DistillationSimulation.Condensate.CLEAR
                        || c == DistillationSimulation.Condensate.HAZE_RISING && sim.fraction() < sim.params().tailsStart() - 0.01;
                yield new DistillationInput(2, 2, collect);
            }
        };
    }
}
