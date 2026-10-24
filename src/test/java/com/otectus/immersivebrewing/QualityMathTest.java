package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.potion.Grade;
import com.otectus.immersivebrewing.potion.QualityMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 6, 8.2, 9.1 and 11: every documented formula at its boundary values. */
class QualityMathTest {

    @Test
    void gradeBands() {
        assertEquals(Grade.CRUDE, Grade.of(0));
        assertEquals(Grade.CRUDE, Grade.of(34));
        assertEquals(Grade.STANDARD, Grade.of(35));
        assertEquals(Grade.STANDARD, Grade.of(69));
        assertEquals(Grade.FINE, Grade.of(70));
        assertEquals(Grade.FINE, Grade.of(89));
        assertEquals(Grade.MASTERWORK, Grade.of(90));
        assertEquals(Grade.MASTERWORK, Grade.of(100));
        assertEquals(Grade.CRUDE, Grade.of(-5));
        assertEquals(Grade.MASTERWORK, Grade.of(150));
    }

    @Test
    void durationMultiplier() {
        assertEquals(0.80, QualityMath.durationMultiplier(0), 1e-12);
        assertEquals(1.00, QualityMath.durationMultiplier(50), 0.0);
        assertEquals(1.20, QualityMath.durationMultiplier(100), 1e-12);
    }

    @Test
    void scaledDurationsAreExactAtTheBaseline() {
        assertEquals(3600, QualityMath.scaleDuration(3600, 50, 0.8, 1.2));
        assertEquals(2880, QualityMath.scaleDuration(3600, 0, 0.8, 1.2));
        assertEquals(4320, QualityMath.scaleDuration(3600, 100, 0.8, 1.2));
        assertEquals(9600, QualityMath.scaleDuration(9600, 50, 0.9, 1.1));
        // Server bounds narrow the range.
        assertEquals(3960, QualityMath.scaleDuration(3600, 100, 0.8, 1.1));
        assertEquals(3240, QualityMath.scaleDuration(3600, 0, 0.9, 1.2));
        // Non-finite sentinels are untouched.
        assertEquals(-1, QualityMath.scaleDuration(-1, 100, 0.8, 1.2));
        assertEquals(0, QualityMath.scaleDuration(0, 100, 0.8, 1.2));
    }

    @Test
    void useTicks() {
        assertEquals(36, QualityMath.useTicks(0, 32));
        assertEquals(32, QualityMath.useTicks(50, 32));
        assertEquals(28, QualityMath.useTicks(100, 32));
        assertEquals(36, QualityMath.useTicks(6, 32));   // 35.52 rounds to 36
        assertEquals(35, QualityMath.useTicks(7, 32));   // 35.44 rounds to 35
        assertEquals(20, QualityMath.useTicks(50, 20));  // a different baseline shifts with it
    }

    @Test
    void qualityFormula() {
        assertEquals(83, QualityMath.quality(80, 84));   // spec 8.2 example
        assertEquals(88, QualityMath.quality(50, 100));  // raw ingredient, perfect control
        assertEquals(100, QualityMath.quality(100, 100));
        assertEquals(0, QualityMath.quality(0, 0));
        assertEquals(13, QualityMath.quality(50, 0));
    }

    @Test
    void componentScores() {
        assertEquals(100, QualityMath.extraction(1, 1));
        assertEquals(70, QualityMath.extraction(1, 0));
        assertEquals(30, QualityMath.extraction(0, 1));
        assertEquals(100, QualityMath.control(1, 1, 1));
        assertEquals(40, QualityMath.control(1, 0, 0));
        assertEquals(35, QualityMath.control(0, 1, 0));
        assertEquals(25, QualityMath.control(0, 0, 1));
        assertEquals(100, QualityMath.distillation(1, 1, 1));
        assertEquals(50, QualityMath.distillation(1, 0, 0));
        assertEquals(0, QualityMath.control(Double.NaN, -1, 0));
    }

    @Test
    void purification() {
        assertEquals(40, QualityMath.purified(40, 34, 70));   // below 35: unchanged
        assertEquals(57, QualityMath.purified(40, 35, 70));   // round(50 + 7)
        assertEquals(70, QualityMath.purified(40, 100, 70));  // capped
        assertEquals(66, QualityMath.purified(66, 50, 70));   // never lowers
        assertEquals(58, QualityMath.purified(10, 38, 70));   // round(57.6)
        assertEquals(65, QualityMath.purified(10, 100, 65));  // configured cap
    }
}
