package com.otectus.immersivebrewing.client.presentation;

import com.otectus.immersivebrewing.config.ClientConfig;

/**
 * One client-side interpretation of presentation preferences.  This deliberately
 * exposes no gameplay data: callers use it only to decide how state is drawn.
 * In particular, intensity zero is zero even when reduced motion is selected.
 */
public final class PresentationPolicy {
    public record Snapshot(double animationIntensity, double particleDensity, boolean reducedMotion,
                           boolean largeMarkers, ClientConfig.Palette palette, boolean writtenCues,
                           boolean numericDisplay) {
        /** Motion permitted for non-essential decorative movement. */
        public double decorativeMotion() {
            if (animationIntensity <= 0.0) return 0.0;
            // Reduced motion keeps direct gauges/tool poses but never permits an
            // autonomous orbit, swirl, bob, shake, or repeating flash.
            return reducedMotion ? 0.0 : animationIntensity;
        }

        /** Necessary tool/state poses may update immediately even at zero intensity. */
        public boolean animate() {
            return decorativeMotion() > 0.0;
        }

        public boolean particles() {
            return particleDensity > 0.0;
        }

        public int markerThickness() {
            return largeMarkers ? 3 : 1;
        }
    }

    public static Snapshot current() {
        return new Snapshot(
                clamp(ClientConfig.get(ClientConfig.ANIMATION_INTENSITY)),
                clamp(ClientConfig.get(ClientConfig.PARTICLE_DENSITY)),
                ClientConfig.get(ClientConfig.REDUCED_MOTION),
                ClientConfig.get(ClientConfig.LARGE_MARKERS),
                ClientConfig.get(ClientConfig.PALETTE),
                ClientConfig.get(ClientConfig.WRITTEN_CUES),
                ClientConfig.get(ClientConfig.NUMERIC_DISPLAY));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private PresentationPolicy() {}
}
