package com.otectus.immersivebrewing.minigame.brewing;

import java.util.Locale;

/** A telegraphed disturbance (spec 7): warned at {@code warnStep}, takes effect at {@code step}. */
public record Disturbance(Kind kind, int warnStep, int step, double magnitude) {
    /** Warning lead, 2.5 s. */
    public static final int LEAD = 50;
    /** A cold draft spreads its cooling over one second. */
    public static final int COLD_SPREAD = 20;

    public enum Kind {
        /** The liquid cools: raise the heat ahead of it. */
        COLD_DRAFT,
        /** The burner flares: ease the draft before the heat overshoots. */
        BURNER_FLARE,
        /** The signature rises (foam surge, sediment pocket, gathering bubbles): circulate as the profile needs. */
        SURGE;

        public String serialized() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String value) {
            for (Kind k : values()) if (k.serialized().equals(value)) return k;
            return null;
        }
    }
}
