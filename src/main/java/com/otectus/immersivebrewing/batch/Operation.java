package com.otectus.immersivebrewing.batch;

import java.util.Locale;

/** What a job transforms: a preparation kit, a kettle brew, an Alembic purification or reclamation. */
public enum Operation {
    PREPARE, BREW, PURIFY, RECLAIM, FINISH;

    public String key() {
        return "operation.immersive_brewing." + name().toLowerCase(Locale.ROOT);
    }

    /** Operations played as a minigame (reclamation is passive). */
    public boolean interactive() {
        return this != RECLAIM && this != FINISH;
    }
}
