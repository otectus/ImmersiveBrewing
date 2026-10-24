package com.otectus.immersivebrewing.minigame.distillation;

/** The resolved distillation {@code D} and its three terms, with the routing detail the result screen explains. */
public record DistillationScore(int distillation, double collection, double thermal, double cooling, double heart,
                                double heads, double tails, boolean completed, boolean participated) {

    public String tipKey() {
        if (!participated) return "tip.immersive_brewing.distill.idle";
        if (!completed && heart < 0.5) return "tip.immersive_brewing.distill.too_slow";
        if (heads > 0.03) return "tip.immersive_brewing.distill.heads";
        if (tails > 0.03) return "tip.immersive_brewing.distill.tails";
        if (collection < 0.8) return "tip.immersive_brewing.distill.missed_heart";
        if (thermal < cooling && thermal < 0.85) return "tip.immersive_brewing.distill.heat";
        if (cooling < 0.85) return "tip.immersive_brewing.distill.cooling";
        return "tip.immersive_brewing.distill.excellent";
    }
}
