package com.otectus.immersivebrewing.client.presentation;

import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;

/** Deterministic, renderer-neutral material frames.  State selects a frame; time never fabricates progress. */
public final class PreparationMaterialVisuals {
    public record Fragment(float x, float z, float scale, float rotation) {}
    private static final Fragment[][][] FRAMES = new Fragment[PreparationMode.values().length][5][];
    static {
        for (PreparationMode mode : PreparationMode.values()) for (int stage = 0; stage < 5; stage++) {
            int count = switch (mode) { case CRYSTALLINE -> 8 - stage; case FIBROUS -> 6; case SOFT -> 4; case BRITTLE -> 7 - stage; case LIQUID -> 3; };
            FRAMES[mode.ordinal()][stage] = new Fragment[count];
            for (int i = 0; i < count; i++) {
                float a = (float) (i * 2.399963229728653 + mode.ordinal() * .71);
                float radius = (stage == 2 ? .15F : .25F) + (i % 2) * .035F;
                FRAMES[mode.ordinal()][stage][i] = new Fragment(.5F + (float)Math.cos(a) * radius, .5F + (float)Math.sin(a) * radius,
                        Math.max(.055F, .13F - stage * .016F + (i % 3) * .012F), (float)Math.toDegrees(a));
            }
        }
    }
    /** texture is the server simulation's Texture ordinal; presentation never estimates endpoint thresholds. */
    public static Fragment[] fragments(int mode, int texture) {
        return FRAMES[Math.max(0, Math.min(FRAMES.length - 1, mode))][Math.max(0, Math.min(4, texture))];
    }
    private PreparationMaterialVisuals() {}
}
