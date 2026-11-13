package com.otectus.immersivebrewing.workshop;

import java.util.Locale;

/**
 * The pressure a player chooses for a tool (handoff 6, 7): ordinary mice sense no pressure, so effort is a selected or
 * hold-derived level. The same three levels exist for mouse, keyboard and toggle input, so none has a lower ceiling.
 */
public enum Effort {
    GENTLE(0.30), NORMAL(0.55), FIRM(0.85);

    public final double value;

    Effort(double value) {
        this.value = value;
    }

    public static Effort byId(int id) {
        Effort[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, id))];
    }

    public Effort next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return "effort.immersive_brewing." + name().toLowerCase(Locale.ROOT);
    }
}
