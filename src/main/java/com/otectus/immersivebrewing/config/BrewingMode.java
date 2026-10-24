package com.otectus.immersivebrewing.config;

/** Relationship with the ordinary brewing stand (spec 14). */
public enum BrewingMode {
    /** Default: stands and their recipes keep working; the Kettle offers the hands-on, graded route. */
    COEXIST,
    /** Covered effect-creation transformations are routed through the Kettle; everything else stays available. */
    REPLACE_COVERED,
    /** New productive jobs and stand interception stop; existing jobs may still resume or recover. */
    DISABLED
}
