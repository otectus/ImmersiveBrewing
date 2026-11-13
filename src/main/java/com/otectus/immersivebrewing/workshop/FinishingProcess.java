package com.otectus.immersivebrewing.workshop;

import net.minecraft.nbt.CompoundTag;

/**
 * Container-only finishing at the kettle (handoff 8): the finishing fitting is mounted over the reserved bottles, the
 * player tips each registered modifier into it in order and closes the fitting; after the settle (the server's finishing
 * time, fixed when the batch is set out) the bottles change container in place. It earns no score and never changes quality: the commit still goes through QuickFinishing's
 * container proof. Opening the fitting before the settle ends simply pauses it.
 */
public final class FinishingProcess {
    public static final int VERSION = 1;
    /** The settle of a payload saved without one. */
    public static final double SETTLE_SECONDS = 3.0;

    private final int steps;
    private final int[] slots;
    private final double settleSeconds;
    private int doses;
    private boolean closed;
    private double settle;

    private FinishingProcess(int steps, int[] slots, double settleSeconds) {
        this.steps = Math.max(1, Math.min(KettleProcess.MAX_STEPS, steps));
        this.slots = slots.clone();
        this.settleSeconds = KettleProcess.finite(settleSeconds, 1, 30);
    }

    /**
     * @param slots the rack position of each reserved bottle, in the owning ledger's input order
     * @param settleSeconds how long the closed fitting takes to change the bottles
     */
    public static FinishingProcess begin(int steps, int[] slots, double settleSeconds) {
        return new FinishingProcess(steps, slots, settleSeconds);
    }

    public double settleSeconds() {
        return settleSeconds;
    }

    public int[] slots() {
        return slots.clone();
    }

    public int steps() {
        return steps;
    }

    public int doses() {
        return doses;
    }

    public boolean closed() {
        return closed;
    }

    public String doseProblem(int step) {
        if (closed) return "workshop.immersive_brewing.finishing.open_first";
        if (step != doses) return step < doses ? "workshop.immersive_brewing.kettle.dose.added" : "workshop.immersive_brewing.kettle.dose.order";
        return null;
    }

    public boolean dose(int step) {
        if (doseProblem(step) != null) return false;
        doses++;
        return true;
    }

    /** Closes or opens the fitting; closing needs every modifier in it. */
    public boolean setClosed(boolean value) {
        if (value && doses < steps) return false;
        if (closed == value) return false;
        closed = value;
        return true;
    }

    public void tick() {
        if (closed && doses >= steps) settle = Math.min(1, settle + KettleProcess.DT / settleSeconds);
    }

    public double settle() {
        return settle;
    }

    public boolean done() {
        return settle >= 1;
    }

    public String cueKey() {
        if (doses < steps) return "cue.immersive_brewing.finishing.dose";
        if (!closed) return "cue.immersive_brewing.finishing.close";
        return done() ? "cue.immersive_brewing.finishing.done" : "cue.immersive_brewing.finishing.settling";
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "finishing");
        t.putInt("version", VERSION);
        t.putInt("steps", steps);
        t.putIntArray("slots", slots);
        t.putInt("doses", doses);
        t.putBoolean("closed", closed);
        t.putDouble("settle", settle);
        t.putDouble("settleSeconds", settleSeconds);
        return t;
    }

    public static FinishingProcess load(CompoundTag t) {
        if (!"finishing".equals(t.getString("kind"))) throw new IllegalArgumentException("not a finishing process");
        if (t.getInt("version") != VERSION) throw new IllegalArgumentException("finishing process version " + t.getInt("version"));
        int[] slots = t.getIntArray("slots");
        if (slots.length > KettleProcess.MAX_SERVINGS) throw new IllegalArgumentException("finishing slots " + slots.length);
        for (int s : slots) if (s < 0 || s >= KettleProcess.MAX_SERVINGS) throw new IllegalArgumentException("finishing slot " + s);
        FinishingProcess p = new FinishingProcess(t.getInt("steps"), slots,
                t.contains("settleSeconds", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? t.getDouble("settleSeconds") : SETTLE_SECONDS);
        p.doses = Math.max(0, Math.min(p.steps, t.getInt("doses")));
        p.closed = t.getBoolean("closed") && p.doses >= p.steps;
        p.settle = KettleProcess.finite(t.getDouble("settle"), 0, 1);
        return p;
    }

    public void writeSnapshot(CompoundTag t) {
        t.putByte("doses", (byte) doses);
        t.putByte("steps", (byte) steps);
        t.putBoolean("closed", closed);
        t.putByte("settle", KettleProcess.q(settle));
        byte[] at = new byte[slots.length];
        for (int i = 0; i < at.length; i++) at[i] = (byte) slots[i];
        t.putByteArray("slots", at);
        t.putString("cue", cueKey());
    }
}
