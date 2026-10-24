package com.otectus.immersivebrewing.batch;

import java.util.Locale;

/**
 * Lifecycle of a station job (spec 20.2). {@code READY} is a station without a ledger; {@code RESOLVED} is the moment of
 * commit, after which outputs are ordinary inventory and the station is ready again.
 */
public enum JobState {
    RESERVED, RUNNING, SEALED, RECOVERY;

    public String key() {
        return "state.immersive_brewing." + name().toLowerCase(Locale.ROOT);
    }
}
