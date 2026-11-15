package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.minigame.brewing.Signature;

import java.util.Locale;

/**
 * The physical personality of a kettle reaction (handoff 8). Each family turns the liquid's real state into visible
 * trouble through two separate quantities, so one mistake costs one penalty:
 * <ul>
 * <li><b>agitation trouble</b> comes from how the paddle is used (and from each dose); it is what the incorporation
 * score samples;</li>
 * <li><b>heat trouble</b> comes from boiling; it is only shown (foam, broken shimmer, trapped gas), because the heat
 * itself is already scored by the thermal term.</li>
 * </ul>
 * Every family has a decay path in the calm, resting regime the kettle needs for bottling, so a resting brew always
 * becomes ready: no family can deadlock a batch.
 *
 * <p>Rates are per second. {@code v} is circulation 0..1, {@code t} the liquid temperature 0..1, {@code u} the
 * undissolved part of the latest dose 0..1, {@code broad} how broad the recent strokes were 0..1.
 */
public enum ReactionFamily {
    GENERIC(0.46, 0.62, 0.20),
    LIGHT_FOAM(0.46, 0.60, 0.35),
    DENSE_SEDIMENT(0.50, 0.66, 0.45),
    DELICATE_SHIMMER(0.38, 0.54, 0.25),
    MAGMA_STREAKS(0.52, 0.68, 0.40),
    SUSPENDED_BUBBLES(0.42, 0.58, 0.25),
    DARK_WHORLS(0.42, 0.58, 0.45),
    ALIGNING_GLINTS(0.44, 0.58, 0.30),
    FLOATING_MOTES(0.44, 0.58, 0.30);

    /** Default ideal temperature band when no profile names one. */
    public final double idealLow;
    public final double idealHigh;
    /** Agitation trouble a dose adds before it is incorporated (scaled by the reagent's sensitivity). */
    public final double kick;

    ReactionFamily(double idealLow, double idealHigh, double kick) {
        this.idealLow = idealLow;
        this.idealHigh = idealHigh;
        this.kick = kick;
    }

    /** The family a legacy reaction signature translates to (handoff 13: deterministic fallback). */
    public static ReactionFamily of(Signature signature) {
        if (signature == null) return GENERIC;
        return switch (signature) {
            case LIGHT_FOAM -> LIGHT_FOAM;
            case DENSE_SEDIMENT -> DENSE_SEDIMENT;
            case DELICATE_SHIMMER -> DELICATE_SHIMMER;
            case MAGMA_STREAKS -> MAGMA_STREAKS;
            case SUSPENDED_BUBBLES -> SUSPENDED_BUBBLES;
            case DARK_WHORLS -> DARK_WHORLS;
            case ALIGNING_GLINTS -> ALIGNING_GLINTS;
            case FLOATING_MOTES -> FLOATING_MOTES;
            default -> GENERIC;
        };
    }

    public static ReactionFamily parse(String name) {
        for (ReactionFamily f : values()) if (f.serialized().equals(name)) return f;
        return null;
    }

    public String serialized() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Change per second of the agitation trouble {@code mix}. */
    public double mixRate(double v, double t, double u, double broad, double mix, boolean reacting) {
        return switch (this) {
            // Fine bubbles gather into froth above a gentle fold; slowing down lets the head settle.
            case LIGHT_FOAM -> 1.2 * Math.max(0, v - 0.45) - (v <= 0.45 ? 0.45 : 0.08) * mix;
            // Heavy material settles while the liquid is still; broad sweeps lift it. At rest it settles out cleanly.
            case DENSE_SEDIMENT -> reacting
                    ? 0.10 * Math.max(0, 0.25 - v) / 0.25 - 0.9 * v * (0.5 + 0.5 * broad) * mix
                    : -0.35 * mix;
            // Shimmer breaks under hard stirring; patient folding keeps it.
            case DELICATE_SHIMMER -> 1.0 * Math.max(0, v - 0.5) - (v < 0.4 ? 0.30 : 0.05) * mix;
            // Thick ribbons only soften once warm and folded; still and warm they slowly give way.
            case MAGMA_STREAKS -> {
                boolean warm = t > 0.52;
                if (warm && v > 0.22 && v < 0.78) yield -0.6 * mix;
                if (warm && v <= 0.22) yield -0.08 * mix;
                yield reacting ? 0.02 : -0.03 * mix;
            }
            // Gas is trapped by thrashing; calm movement releases it, rest releases it slowly.
            case SUSPENDED_BUBBLES -> 0.5 * Math.max(0, v - 0.6) - (v > 0.05 && v < 0.45 ? 0.45 : v <= 0.05 ? 0.15 : 0.0) * mix;
            // Dark ribbons from each dose disperse with mixing; a quiet rest clarifies them slowly.
            case DARK_WHORLS -> v > 0.25 ? -0.7 * v * mix : -0.12 * mix;
            // Grains need mixing to dissolve; once dissolved, agitation scatters the glints again.
            case ALIGNING_GLINTS -> u > 0.15 ? -(0.6 * v + 0.05) * mix : 0.8 * Math.max(0, v - 0.55) - (v <= 0.55 ? 0.4 : 0.0) * mix;
            // Motes stay on top: gentle broad strokes draw them under, turbulence throws them out, rest sinks them slowly.
            case FLOATING_MOTES -> v > 0.65 ? 0.7 * (v - 0.65)
                    : v > 0.15 ? -0.7 * (0.5 + broad) * mix : -0.10 * mix;
            case GENERIC -> 0.6 * Math.max(0, v - 0.7) - (v < 0.7 ? 0.35 : 0.0) * mix;
        };
    }

    /**
     * Kettle process version 2 (handoff 8.3): the paddle's posture matters where a family's cue names it. {@code depth}
     * is the recent share of strokes swept along the bottom (0 surface folding .. 1 bottom sweeps). Heavy sediment lifts
     * only when the bottom is swept; floating motes are drawn under by strokes at the surface; light froth settles under
     * slow deep folds; thick ribbons fold in best from below. Every other family ignores the posture, so its rate equals
     * version 1's.
     */
    public double mixRate(double v, double t, double u, double broad, double mix, boolean reacting, double depth) {
        double d = Math.max(0, Math.min(1, depth));
        return switch (this) {
            case LIGHT_FOAM -> 1.2 * Math.max(0, v - 0.45) * (1 - 0.35 * d) - (v <= 0.45 ? 0.45 : 0.08 + 0.12 * d) * mix;
            case DENSE_SEDIMENT -> reacting
                    ? 0.10 * Math.max(0, 0.25 - v) / 0.25 - 0.9 * v * (0.20 + 0.30 * broad + 0.75 * d) * mix
                    : -0.35 * mix;
            case MAGMA_STREAKS -> {
                boolean warm = t > 0.52;
                if (warm && v > 0.22 && v < 0.78) yield -0.6 * (0.75 + 0.45 * d) * mix;
                if (warm && v <= 0.22) yield -0.08 * mix;
                yield reacting ? 0.02 : -0.03 * mix;
            }
            case FLOATING_MOTES -> v > 0.65 ? 0.7 * (v - 0.65)
                    : v > 0.15 ? -0.7 * (0.5 + broad) * (1.1 - 0.6 * d) * mix : -0.10 * mix;
            default -> mixRate(v, t, u, broad, mix, reacting);
        };
    }

    /** Which posture the family's cue asks for: 1 sweep the bottom, -1 keep to the surface, 0 either. */
    public int posture() {
        return switch (this) {
            case DENSE_SEDIMENT, MAGMA_STREAKS, LIGHT_FOAM -> 1;
            case FLOATING_MOTES -> -1;
            default -> 0;
        };
    }

    /** Change per second of the heat trouble {@code heat} (shown, never scored twice). */
    public double heatRate(double t, double heat) {
        double onset = switch (this) {
            case LIGHT_FOAM -> 0.68;
            case DELICATE_SHIMMER -> 0.62;
            case SUSPENDED_BUBBLES -> 0.62;
            case MAGMA_STREAKS -> 0.88;
            case DENSE_SEDIMENT -> 0.85;
            default -> 0.80;
        };
        double grow = switch (this) {
            case LIGHT_FOAM -> 0.9;
            case DELICATE_SHIMMER -> 1.2;
            case SUSPENDED_BUBBLES -> 0.6;
            default -> 0.5;
        };
        return t > onset ? grow * (t - onset) : -0.35 * heat;
    }

    /** The translation key of the visible trouble this family shows, for cues and text equivalents. */
    public String troubleKey() {
        return "cue.immersive_brewing.trouble." + serialized();
    }
}
