package com.otectus.immersivebrewing.smoketest;

import net.minecraftforge.fml.common.Mod;

/**
 * Test-only companion mod used by {@code runClient -PsmokeTest}. It does nothing unless the system property
 * {@code immersive_brewing.smoketest.output} names a directory for its result file. Never shipped.
 */
@Mod(SmokeTestAgent.MOD_ID)
public class SmokeTestAgent {
    public static final String MOD_ID = "immersive_brewing_smoketest";
    public static final String OUTPUT_PROPERTY = "immersive_brewing.smoketest.output";

    public SmokeTestAgent() {
    }
}
