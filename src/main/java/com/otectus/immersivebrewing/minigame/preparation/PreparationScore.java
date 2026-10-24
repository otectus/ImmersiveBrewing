package com.otectus.immersivebrewing.minigame.preparation;

/**
 * The resolved preparation: extraction {@code E} and the two terms it came from, plus what the result screen needs
 * to explain it (spec 11.4: explain the actual contributors).
 */
public record PreparationScore(int extraction, double accuracy, double restraint, PreparationSimulation.Texture texture,
                               int folds, int overpressureSteps, int wastedSteps, boolean finishedByPlayer, boolean productive) {

    /** Translation key of the single most useful improvement tip. */
    public String tipKey() {
        if (!productive) return "tip.immersive_brewing.prep.no_work";
        if (texture == PreparationSimulation.Texture.COARSE || texture == PreparationSimulation.Texture.BREAKING) {
            return finishedByPlayer ? "tip.immersive_brewing.prep.stopped_early" : "tip.immersive_brewing.prep.ran_out";
        }
        if (texture == PreparationSimulation.Texture.OVERWORKED || texture == PreparationSimulation.Texture.FINE) {
            return "tip.immersive_brewing.prep.overworked";
        }
        if (restraint < 0.75) {
            return overpressureSteps >= wastedSteps ? "tip.immersive_brewing.prep.ease_pressure" : "tip.immersive_brewing.prep.stop_sooner";
        }
        return "tip.immersive_brewing.prep.well_judged";
    }
}
