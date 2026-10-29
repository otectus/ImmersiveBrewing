package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.client.tutorial.GuidedPractice;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IB-110-02: each replayable practice stage isolates one concept and recovery begins with a recoverable mistake. */
class GuidedPracticeTest {
    @Test
    void stagesAreShortAndSingleConcept() {
        for (GuidedPractice.Stage stage : GuidedPractice.Stage.values()) {
            KettleParams params = GuidedPractice.params(stage);
            assertTrue(params.totalSteps() <= 12 * 20, stage + " remains a short lesson");
            assertTrue(params.disturbances().isEmpty(), stage + " has no unrelated random disturbance");
            assertEquals(stage == GuidedPractice.Stage.ADDITION ? 1 : 0, params.additions().size(), stage + " addition count");
        }
    }

    @Test
    void lessonFilterNeutralisesOnlyUnrelatedControls() {
        KettleInput all = new KettleInput(2, KettleInput.VIGOROUS, true);
        assertEquals(new KettleInput(2, 0, false), GuidedPractice.filter(GuidedPractice.Stage.HEAT, all, 1));
        assertEquals(new KettleInput(1, KettleInput.VIGOROUS, false), GuidedPractice.filter(GuidedPractice.Stage.CIRCULATION, all, 1));
        assertEquals(new KettleInput(1, 0, true), GuidedPractice.filter(GuidedPractice.Stage.ADDITION, all, 1));
    }

    @Test
    void recoveryStartsHotButInsidePhysicalBounds() {
        KettleParams params = GuidedPractice.params(GuidedPractice.Stage.RECOVERY);
        assertTrue(params.initialHeat() > params.phases().get(0).heatHigh(), "lesson deliberately starts above the target");
        assertTrue(params.initialHeat() <= 1 && params.initialBurner() <= 1, "mistake remains physically valid and recoverable");
    }
}
