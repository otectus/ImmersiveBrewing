package com.otectus.immersivebrewing.client.presentation;

import com.otectus.immersivebrewing.minigame.brewing.Signature;

/** Shared semantic motifs used by both world and screen kettles; trouble is never inferred from heat. */
public final class KettleSignatureVisuals {
    public enum Motif { CLEAR, BUBBLES, HAZE, FOAM, SEDIMENT, SHIMMER, STREAKS, TRAPPED_BUBBLES, WHORLS, GLINTS, MOTES }
    public record Definition(Motif motif, int healthyColor, int troubleColor, String guidance) {}
    private static final Definition[] DEFINITIONS = {
            new Definition(Motif.CLEAR, 0xD6E8F0, 0xFFFFFF, "Follow the target bands"),
            new Definition(Motif.BUBBLES, 0xE8F4F2, 0xD7B18B, "Keep the simmer even"),
            new Definition(Motif.HAZE, 0xDCE9E0, 0xA0A89D, "Let the brew settle"),
            new Definition(Motif.FOAM, 0xF2E9D1, 0xDCC79C, "Ease the circulation"),
            new Definition(Motif.SEDIMENT, 0xA87954, 0x4D3024, "Lift sediment briefly"),
            new Definition(Motif.SHIMMER, 0xFFF2A8, 0xC6A16B, "Keep a gentle movement"),
            new Definition(Motif.STREAKS, 0xD56B35, 0x743424, "Sustain circulation"),
            new Definition(Motif.TRAPPED_BUBBLES, 0xB9E7E9, 0x7597A2, "Reverse once to release bubbles"),
            new Definition(Motif.WHORLS, 0x6D556E, 0x342739, "Let the dark whorls rest"),
            new Definition(Motif.GLINTS, 0xE5E0AD, 0x847E64, "Let the glints align"),
            new Definition(Motif.MOTES, 0xE6D8B4, 0xA29376, "Draw the motes under gently")
    };
    public static Definition of(int ordinal) { return DEFINITIONS[Math.max(0, Math.min(DEFINITIONS.length - 1, ordinal))]; }
    public static Definition of(Signature signature) { return of(signature == null ? 0 : signature.ordinal()); }
    private KettleSignatureVisuals() {}
}
