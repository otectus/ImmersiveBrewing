package com.otectus.immersivebrewing.advancement;

import java.util.Locale;

/** Committed outcomes that advancements listen for (spec 25.2). Each fires once, from the server commit. */
public enum Milestone {
    PREPARE_KIT, FIRST_BREW, FINE_BATCH, MASTERWORK_BATCH, RESUME, PURIFY, SIX_SERVINGS, RECLAIM;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
