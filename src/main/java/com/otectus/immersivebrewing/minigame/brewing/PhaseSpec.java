package com.otectus.immersivebrewing.minigame.brewing;

/**
 * One phase of a kettle session: absolute step range, the heat band and |circulation| band it rewards, its reaction
 * signature and the trouble level it starts with. {@code reverseStep} (or -1) is when a reversal stage is announced.
 */
public record PhaseSpec(KettlePhase phase, int start, int length, double heatLow, double heatHigh, double circLow,
                        double circHigh, Signature signature, double initialTrouble, int reverseStep) {
    public PhaseSpec {
        if (length <= 0 || start < 0) throw new IllegalArgumentException("phase steps");
        if (!(0 <= heatLow && heatLow <= heatHigh && heatHigh <= 1)) throw new IllegalArgumentException("heat band");
        if (!(0 <= circLow && circLow <= circHigh && circHigh <= 1)) throw new IllegalArgumentException("circulation band");
        if (!(initialTrouble >= 0 && initialTrouble <= 1)) throw new IllegalArgumentException("initial trouble");
    }

    public int end() {
        return start + length;
    }

    public boolean contains(int step) {
        return step >= start && step < end();
    }
}
