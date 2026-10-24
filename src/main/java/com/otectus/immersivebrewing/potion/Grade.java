package com.otectus.immersivebrewing.potion;

/** The family's grade bands (spec 11.1). A grade summarises a score; it is never a multiplier of its own. */
public enum Grade {
    CRUDE(0, 34, 0x9A8F84, '○'),
    STANDARD(35, 69, 0xE0E0E0, '◑'),
    FINE(70, 89, 0x7FD4FF, '◆'),
    MASTERWORK(90, 100, 0xFFC84A, '★');

    private final int min;
    private final int max;
    private final int color;
    private final char symbol;

    Grade(int min, int max, int color, char symbol) {
        this.min = min;
        this.max = max;
        this.color = color;
        this.symbol = symbol;
    }

    public static Grade of(int score) {
        int s = QualityMath.clampScore(score);
        if (s >= MASTERWORK.min) return MASTERWORK;
        if (s >= FINE.min) return FINE;
        if (s >= STANDARD.min) return STANDARD;
        return CRUDE;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    /** Accent colour; always shown together with {@link #symbol()} and the grade name, never alone. */
    public int color() {
        return color;
    }

    public char symbol() {
        return symbol;
    }

    public String key() {
        return "grade.immersive_brewing." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
