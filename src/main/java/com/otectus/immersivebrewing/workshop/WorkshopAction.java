package com.otectus.immersivebrewing.workshop;

/**
 * What a client asks a station part to do (handoff 15: intent, never workmanship or results). The ordinal travels in
 * {@code WorkshopIntentC2S}; append new actions only. Every action is validated against the station's current state on
 * the server, and a stale or repeated one fails harmlessly.
 */
public enum WorkshopAction {
    /** Take a tool (paddle, pestle, scraper) into the hand: a short server lease. */
    GRAB,
    /** Put the held tool back on its rest. */
    RELEASE,
    /** A held tool's position on its work plane: normalised x/z, contact and effort. Bounded and rate-limited. */
    SAMPLE,
    /** A pestle press at the selected effort. */
    PRESS,
    /** A discrete control setting: burner, coolant valve, diverter. */
    SET_CONTROL,
    /** Tip a staged serving into the vessel (argument: portion, or -1 for the whole batch in sequence). */
    POUR,
    /** Tip the next reserved dose (argument: the recipe step). */
    DOSE,
    /** Open the spout over a reserved bottle (argument: portion, or -1 for the remaining bottles in sequence). */
    FILL,
    /** Stop a running pour or fill sequence. */
    STOP,
    /** Close the lid / clamp: seal the batch now. */
    SEAL,
    /** Open the lid / clamp of a sealed batch and carry on. */
    UNSEAL,
    /** Release a batch before any irreversible processing (exact staged resources return). */
    CANCEL,
    /** Mortar: scrape the prepared material into the dose cup (commits the kits once). Alembic: charge the still. */
    FINISH,
    /** Put the stack in the acting hand into the part's slot. */
    INSERT,
    /** Take the part's stack into the player's inventory. */
    TAKE,
    /** Rack: take every stack matching the targeted one. */
    TAKE_GROUP,
    /** Rack: pack the held carrier from the rack. */
    PACK,
    /** Rack: unpack the held carrier's selected stack into the rack. */
    UNPACK,
    /** Open the station's inventory drawer (its maintenance menu). */
    DRAWER,
    /** Open the route book for this station. */
    BOOK,
    /** Leave the working view. */
    LEAVE,
    /** Finish an unfinished batch from an earlier version through its original interface (migration only). */
    LEGACY_RESUME,
    /** Begin preparing the reagents in the bowl (the first contact also begins it). */
    BEGIN,
    /** Return to a working view that is still open on the server (after the drawer or the book closed). */
    FOCUS,
    /** Kettle: turn the recipe page on the book stand to another valid route (argument: the page). */
    SETUP_PAGE,
    /**
     * Move the working view to a neighbouring station (argument: the packed offset, see {@code StationOffsets}). The
     * server checks reach, protection and the ordinary block-use hooks at the new station, and releases this one first.
     */
    SWITCH_STATION
}
