package com.otectus.immersivebrewing.minigame.preparation;

/**
 * One step of Mortar input: where the player is steering the pestle (bowl coordinates, quantised to -127..127 per
 * axis), whether pressure is held, and the two edge actions (lift and fold, finish). Keyboard and mouse both produce
 * exactly this, so neither can reach a score the other can not.
 */
public record PreparationInput(byte x, byte y, boolean press, boolean fold, boolean finish) {
    public static final PreparationInput NEUTRAL = new PreparationInput((byte) 0, (byte) 0, false, false, false);

    public static PreparationInput of(double x, double y, boolean press, boolean fold, boolean finish) {
        return new PreparationInput(quantise(x), quantise(y), press, fold, finish);
    }

    public double targetX() {
        return x / 127.0;
    }

    public double targetY() {
        return y / 127.0;
    }

    public int pack() {
        return (x & 0xFF) | (y & 0xFF) << 8 | (press ? 1 << 16 : 0) | (fold ? 1 << 17 : 0) | (finish ? 1 << 18 : 0);
    }

    public static PreparationInput unpack(int packed) {
        return new PreparationInput((byte) (packed & 0xFF), (byte) (packed >> 8 & 0xFF), (packed & 1 << 16) != 0,
                (packed & 1 << 17) != 0, (packed & 1 << 18) != 0);
    }

    /** Input with the edge actions removed: what a held control looks like on the step after the edge. */
    public PreparationInput held() {
        return new PreparationInput(x, y, press, false, false);
    }

    private static byte quantise(double v) {
        double c = Math.max(-1.0, Math.min(1.0, v));
        return (byte) Math.round(c * 127.0);
    }
}
