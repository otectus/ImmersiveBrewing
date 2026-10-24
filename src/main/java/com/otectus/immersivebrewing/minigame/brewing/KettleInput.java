package com.otectus.immersivebrewing.minigame.brewing;

/**
 * One step of kettle input: the selected draft (0 low, 1 medium, 2 high), the stir command in eighths (-8..8; negative
 * is counter-clockwise; the keyboard sends +-5 for a steady stir and +-8 for a vigorous one, mouse circles anything
 * between, all capped the same), and the addition edge.
 */
public record KettleInput(int draft, int stir, boolean add) {
    public static final int STEADY = 5;
    public static final int VIGOROUS = 8;

    public KettleInput {
        draft = Math.max(0, Math.min(2, draft));
        stir = Math.max(-8, Math.min(8, stir));
    }

    /** No stirring and no addition, keeping the draft where it was (draft is a setting, not a held key). */
    public static KettleInput neutral(int draft) {
        return new KettleInput(draft, 0, false);
    }

    public double stirCommand() {
        return stir / 8.0;
    }

    public int pack() {
        return (draft & 0x3) | ((stir + 8) & 0x1F) << 2 | (add ? 1 << 7 : 0);
    }

    public static KettleInput unpack(int packed) {
        return new KettleInput(packed & 0x3, ((packed >> 2) & 0x1F) - 8, (packed & 1 << 7) != 0);
    }

    public KettleInput held() {
        return new KettleInput(draft, stir, false);
    }
}
