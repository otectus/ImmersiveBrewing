package com.otectus.immersivebrewing.minigame.brewing;

/**
 * One reserved addition (spec 7): the item is shown in the tray from the start of its phase, its cue appears at
 * {@code cueStep}, and the scoring window is {@code [openStep, closeStep)} (at least 700 ms wide). {@code item} is the
 * registry id, used only for display and tips.
 */
public record AdditionSpec(int index, KettlePhase phase, int cueStep, int openStep, int closeStep, double cooling, double kick, String item) {
    /** Default scoring window lower bound: 14 steps = 700 ms (spec 7). */
    public static final int MIN_WINDOW = 14;

    public AdditionSpec {
        if (closeStep - openStep < MIN_WINDOW) throw new IllegalArgumentException("addition window shorter than 700 ms");
        if (cueStep > openStep) throw new IllegalArgumentException("cue after window");
        if (!(cooling >= 0 && cooling <= 0.5) || !(kick >= 0 && kick <= 1)) throw new IllegalArgumentException("addition effect");
    }
}
