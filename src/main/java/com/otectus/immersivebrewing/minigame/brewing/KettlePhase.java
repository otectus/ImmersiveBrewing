package com.otectus.immersivebrewing.minigame.brewing;

import java.util.Locale;

/** The three phases of one evolving reaction (spec 7). */
public enum KettlePhase {
    BLOOM, INFUSE, SETTLE;

    public String serialized() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static KettlePhase parse(String value) {
        for (KettlePhase phase : values()) if (phase.serialized().equals(value)) return phase;
        return null;
    }
}
