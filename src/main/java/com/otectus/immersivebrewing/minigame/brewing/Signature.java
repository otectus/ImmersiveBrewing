package com.otectus.immersivebrewing.minigame.brewing;

import java.util.Locale;

/**
 * Reaction signatures (spec 7): how a phase's visible "trouble" S (foam, sediment, broken shimmer, streaks, trapped
 * bubbles, whorls, scattered glints, floating motes) grows and clears with circulation. S is driven by circulation and
 * additions only, never by heat, so a heat mistake is never penalised twice (spec 8.1): heat is scored by the thermal
 * term alone.
 *
 * <p>Per second: {@code dS = g0 + gHigh x max(0, |V| - high) + gLow x max(0, low - |V|) - decay x S} (decay only while
 * |V| is inside the band, or {@code strongDecay} while stirring vigorously), plus {@code kick} on each addition.
 */
public enum Signature {
    /** No signature: circulation is scored directly against the band. */
    NONE(0, 0, 0, 0, 0, 0, Channel.CLARITY),
    /** Bloom: small even bubbles under gentle circulation; a still or thrashed surface bubbles unevenly. */
    BLOOM_SIMMER(0.03, 0.6, 0.4, 0.35, 0, 0.15, Channel.BUBBLES),
    /** Settle: the brew clears when left calm. */
    SETTLE_CLEAR(0.0, 1.2, 0.0, 0.40, 0, 0.20, Channel.CLARITY),
    /** Swiftness: light foam expands under excessive agitation; steady moderate circulation disperses it. */
    LIGHT_FOAM(0.04, 2.0, 0.5, 0.45, 0, 0.35, Channel.FOAM),
    /** Strength: dense sediment settles; brief stronger stirring lifts it, then gentle circulation. */
    DENSE_SEDIMENT(0.02, 0.25, 1.1, 0.30, 1.8, 0.45, Channel.SEDIMENT),
    /** Healing / Regeneration: a delicate shimmer breaks under agitation; patient folding preserves it. */
    DELICATE_SHIMMER(0.0, 1.6, 0.3, 0.40, 0, 0.25, Channel.CLARITY),
    /** Fire Resistance: thick streaks need sustained circulation. */
    MAGMA_STREAKS(0.05, 0.4, 1.3, 0.38, 0.6, 0.35, Channel.SEDIMENT),
    /** Water Breathing: suspended bubbles gather; one announced reversal of direction releases them. */
    SUSPENDED_BUBBLES(0.07, 0.5, 0.3, 0.0, 0, 0.20, Channel.BUBBLES),
    /** Poison / Weakness / Harming: dark whorls drift from each addition; a brief quiet rest clarifies them. */
    DARK_WHORLS(0.0, 1.0, 0.0, 0.45, 0, 0.50, Channel.CLARITY),
    /** Night Vision / Invisibility: glints align as the brew settles; vigorous late stirring scatters them. */
    ALIGNING_GLINTS(0.0, 1.8, 0.0, 0.42, 0, 0.30, Channel.CLARITY),
    /** Slow Falling / Leaping: light motes float; gentle circulation draws them under. */
    FLOATING_MOTES(0.04, 1.0, 0.8, 0.42, 0, 0.30, Channel.FOAM);

    /** Which visible quantity S drives in the renderer. */
    public enum Channel { FOAM, SEDIMENT, CLARITY, BUBBLES }

    /** |V| at or above which a signature's {@link #strongDecay} applies (a deliberately vigorous stir). */
    public static final double STRONG_STIR = 0.75;

    public final double g0;
    public final double gHigh;
    public final double gLow;
    public final double decay;
    public final double strongDecay;
    public final double kick;
    public final Channel channel;

    Signature(double g0, double gHigh, double gLow, double decay, double strongDecay, double kick, Channel channel) {
        this.g0 = g0;
        this.gHigh = gHigh;
        this.gLow = gLow;
        this.decay = decay;
        this.strongDecay = strongDecay;
        this.kick = kick;
        this.channel = channel;
    }

    public String serialized() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Signature parse(String value) {
        for (Signature s : values()) if (s.serialized().equals(value)) return s;
        return null;
    }
}
