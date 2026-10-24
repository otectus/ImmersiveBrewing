package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.minigame.SimRandom;
import com.otectus.immersivebrewing.minigame.preparation.PreparationInput;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationSimulation;

/**
 * Reference Mortar players. {@link #competent} circles the pestle at full allowed speed, presses firmly while the batch
 * is coarse and lightly near the endpoint (short presses for brittle material, a held light touch for soft), folds when
 * it clumps and finishes inside the endpoint window. {@link #poor} grinds hard and too long; {@link #idle} does nothing.
 */
public final class MortarBot {
    public enum Style { COMPETENT, POOR, IDLE }

    private final Style style;
    private final SimRandom random;
    private double angle;
    private int pressTimer;

    public MortarBot(Style style, long seed) {
        this.style = style;
        this.random = new SimRandom(seed);
    }

    public static MortarBot competent() {
        return new MortarBot(Style.COMPETENT, 1);
    }

    public static MortarBot poor(long seed) {
        return new MortarBot(Style.POOR, seed);
    }

    public static MortarBot idle() {
        return new MortarBot(Style.IDLE, 0);
    }

    public PreparationInput decide(PreparationSimulation sim) {
        if (style == Style.IDLE) return PreparationInput.NEUTRAL;
        PreparationParams p = sim.params();
        angle += 0.16;
        double x = Math.cos(angle) * 0.6;
        double y = Math.sin(angle) * 0.6;
        if (style == Style.POOR) {
            boolean fold = random.nextInt(200) == 0;
            return PreparationInput.of(x, y, true, fold, sim.step() > p.durationSteps() - 5);
        }
        double processed = sim.processed();
        double lo = p.endpointLow();
        double hi = p.endpointHigh();
        double stopAt = lo + (hi - lo) * 0.45;
        if (processed >= stopAt) return PreparationInput.of(x, y, false, false, true);
        boolean fold = sim.clump() > 0.45 || p.mode() == PreparationMode.LIQUID && sim.clump() > 0.25;
        boolean press;
        double pressure = sim.pressure();
        double limit = p.pressureLimit() * 0.9;
        switch (p.mode()) {
            case SOFT -> press = pressure < 0.33;
            case BRITTLE -> {
                pressTimer = (pressTimer + 1) % 24;
                press = pressTimer < 14 && pressure < limit;
            }
            case LIQUID -> press = false;
            default -> press = processed < lo * 0.8 ? pressure < limit : pressure < Math.min(limit, 0.45);
        }
        return PreparationInput.of(x, y, press, fold, false);
    }
}
