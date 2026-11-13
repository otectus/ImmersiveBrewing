package com.otectus.immersivebrewing.workshop;

/**
 * Server-side reading of one held tool's samples (handoff 6, 7, 15). Samples are normalised work-plane coordinates
 * ({@code -1..1} across the vessel or bowl) with a contact flag; the tracker turns them into bounded displacement in
 * model pixels that the owning process consumes once per server step.
 *
 * <p>Rules that keep work honest:
 * <ul>
 * <li>Displacement accrues only between two consecutive samples that are both in contact: lifting the tool, moving
 * through the air, or the first touch only sets a new starting point.</li>
 * <li>A jump longer than {@link #MAX_JUMP_PX} (opposite edges, a forged teleport) re-anchors without credit.</li>
 * <li>At most {@link #MAX_STEP_PX} is credited per server step, and no more than {@link #MAX_BANK_PX} may wait, so packet
 * bursts, repeated packets and higher sample rates can not buy more work than steady motion at the cap.</li>
 * <li>After {@link #GAP_TICKS} without a sample the motion stops and anything banked is dropped; the next sample
 * starts afresh instead of joining a distant old point.</li>
 * </ul>
 * Identical repeated samples have zero displacement, so a stationary tool earns nothing.
 */
public final class ToolTrack {
    /** Longest believable move between two samples, in pixels (about 140 px/s at 20 samples per second). */
    public static final double MAX_JUMP_PX = 7.0;
    /** Most displacement credited in one 50 ms server step, in pixels (60 px/s, far above a vigorous stir). */
    public static final double MAX_STEP_PX = 3.0;
    /** Most displacement that may wait for later steps (two steps' worth: smooths ordinary network jitter only). */
    public static final double MAX_BANK_PX = 6.0;
    public static final int GAP_TICKS = 10;

    private final double halfX;
    private final double halfZ;
    private double u;
    private double v;
    private boolean anchored;
    private boolean contact;
    private long lastSample = Long.MIN_VALUE / 2;
    private double bank;
    /** Displacement per server step implied by the latest sample (its move spread over the steps it covered). */
    private double rate;
    private double broadness;
    private int teleports;

    /** @param halfX half the work plane's extent across x, in pixels; @param halfZ the same across z */
    public ToolTrack(double halfX, double halfZ) {
        this.halfX = halfX;
        this.halfZ = halfZ;
    }

    /** Forgets the pose: a new holder, a seal or a resume starts from a neutral, unanchored tool. */
    public void reset() {
        anchored = false;
        contact = false;
        bank = 0;
        rate = 0;
        broadness = 0;
        lastSample = Long.MIN_VALUE / 2;
    }

    /**
     * Records one sample. Returns false when the sample was refused as a teleport (no credit; the caller may count it as
     * suspicious input). Coordinates must already be finite; they are clamped to the plane.
     */
    public boolean sample(double nu, double nv, boolean inContact, long tick) {
        nu = Math.max(-1, Math.min(1, nu));
        nv = Math.max(-1, Math.min(1, nv));
        boolean gap = tick - lastSample > GAP_TICKS;
        long covered = Math.max(1, tick - lastSample);
        lastSample = tick;
        if (gap) bank = 0;
        if (!anchored || gap || !inContact || !contact) {
            u = nu;
            v = nv;
            anchored = true;
            contact = inContact;
            return true;
        }
        double dx = (nu - u) * halfX, dz = (nv - v) * halfZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        u = nu;
        v = nv;
        if (d > MAX_JUMP_PX) {
            teleports++;
            return false;
        }
        bank = Math.min(MAX_BANK_PX, bank + d);
        // A sample sent every k steps describes k steps of motion: credit it evenly, so 10 and 20 samples a second
        // produce the same circulation instead of a pulse and a pause.
        rate = d / covered;
        double r = Math.min(1, Math.sqrt(nu * nu + nv * nv));
        broadness += (r - broadness) * 0.15;
        return true;
    }

    /** The displacement credited this server step, in pixels; zero while lifted or after a gap. */
    public double take(long tick) {
        if (tick - lastSample > GAP_TICKS) {
            bank = 0;
            contact = false;
            return 0;
        }
        double d = Math.min(bank, Math.min(MAX_STEP_PX, Math.max(rate, 0.05)));
        bank -= d;
        return d;
    }

    public double u() {
        return u;
    }

    public double v() {
        return v;
    }

    public boolean contact() {
        return contact && anchored;
    }

    /** Recent average distance from the centre while working, 0..1: broad sweeps versus a stir in one spot. */
    public double broadness() {
        return broadness;
    }

    public int teleports() {
        return teleports;
    }

    /** Whether samples are still arriving (the holder is actively working). */
    public boolean live(long tick) {
        return tick - lastSample <= GAP_TICKS;
    }
}
