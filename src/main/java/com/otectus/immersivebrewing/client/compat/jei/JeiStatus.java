package com.otectus.immersivebrewing.client.compat.jei;

import java.util.Map;

/**
 * What JEI accepted from this mod, recorded when its runtime starts (no JEI types here, so anything may read it without
 * JEI present): recipe counts per category path. Read by the client smoke test and by nothing in play.
 */
public final class JeiStatus {
    private static volatile Map<String, Long> counts = Map.of();

    public static Map<String, Long> counts() {
        return counts;
    }

    static void record(Map<String, Long> value) {
        counts = Map.copyOf(value);
    }

    private JeiStatus() {}
}
