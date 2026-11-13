package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.client.presentation.StationVisualCache;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for sparse snapshot continuity: no world-time angle jumps or stale endless motion. */
class StationVisualCacheTest {
    private static StationVisual visual(long revision, float circulation, UUID job) {
        StationVisual base = new StationVisual(0, 0, 0, circulation, 0, 0, true);
        return base.withContext(new StationVisual.Context(new UUID(1, 2), job, revision, revision * 5, 0,
                null, StationVisual.Activity.WORKING, StationVisual.NONE, 1));
    }
    @Test void integratesAndStopsWhenSnapshotGoesStale() {
        StationVisualCache.clear(); UUID job = new UUID(3, 4);
        float start = StationVisualCache.paddleAngle("overworld", 7, 0, 0, visual(1, 1, job), 1);
        float moving = StationVisualCache.paddleAngle("overworld", 7, 5, 0, visual(2, 1, job), 1);
        float stale = StationVisualCache.paddleAngle("overworld", 7, 30, 0, visual(2, 1, job), 1);
        float later = StationVisualCache.paddleAngle("overworld", 7, 45, 0, visual(2, 1, job), 1);
        assertNotEquals(start, moving); assertEquals(stale, later, .001F);
    }
    @Test void jobReplacementStartsFreshPose() {
        StationVisualCache.clear();
        StationVisualCache.paddleAngle("overworld", 7, 0, 0, visual(1, 1, new UUID(3,4)), 1);
        assertEquals(20F, StationVisualCache.paddleAngle("overworld", 7, 6, 0, visual(2, -1, new UUID(5,6)), 1), .001F);
    }
}
