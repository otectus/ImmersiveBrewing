package com.otectus.immersivebrewing.minigame.preparation;

import java.util.Locale;

/**
 * Material behaviour in the Mortar (spec 6). The selector that picks a mode is datapack data; the mode itself only
 * changes how pressure, motion and folds turn into processing.
 */
public enum PreparationMode {
    /** Crack coarse pieces with firm pressure, then stop before excessive fines. */
    CRYSTALLINE,
    /** Gentle circular pressure; folds expose unworked fibres. */
    FIBROUS,
    /** Low pressure; uniform mashing matters more than speed. */
    SOFT,
    /** Short controlled presses; sustained force creates fines. */
    BRITTLE,
    /** Fold and emulsify; pressure does little. */
    LIQUID;

    public String serialized() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PreparationMode parse(String value) {
        for (PreparationMode mode : values()) if (mode.serialized().equals(value)) return mode;
        return null;
    }
}
