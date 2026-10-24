package com.otectus.immersivebrewing.session;

import com.otectus.immersivebrewing.blockentity.StationVisual;

/**
 * A uniform face for the three pure simulations, so the session clock, input validation, sealing and client prediction
 * are written once. Inputs travel as packed ints ({@code PreparationInput.pack}, {@code KettleInput.pack},
 * {@code DistillationInput.pack}).
 */
public interface SimHandle {
    SessionKind kind();

    long[] params();

    void step(int packedInput);

    boolean finished();

    int step();

    int totalSteps();

    long[] saveState();

    void loadState(long[] state);

    /** The frame used when a step's input never arrived: continuous controls stop, settings stay. */
    int neutral(int lastPacked);

    /** Bits of a packed frame that are one-shot actions (carried forward if their frame arrived late). */
    int edgeMask();

    /** Whether a decoded frame is within legal ranges. */
    boolean valid(int packed);

    /** Released held controls after a seal (spec 20.4). */
    void neutraliseHeld();

    StationVisual visual();
}
