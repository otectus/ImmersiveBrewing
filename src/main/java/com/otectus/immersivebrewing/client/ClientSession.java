package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.network.packet.LeaveSessionC2S;
import com.otectus.immersivebrewing.network.packet.PauseSessionC2S;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * The client half of a session (ADR-2): plays the count-in, runs the same pure simulation one step per client tick with
 * the player's input, records and sends every frame in batches of at most four, and rewinds to each server snapshot,
 * re-applying its own recorded frames. It never runs more than {@link #MAX_LEAD} steps ahead of the last snapshot, so a
 * slow server slows the game instead of letting the client race ahead. The displayed score is provisional until the
 * server's result arrives.
 *
 * <p>While the help overlay is open the session stands still: no count-in, no steps, no frames, and the server's clock
 * stops too. Closing it mid-run counts in again for {@link #RESUME_TICKS} before play continues; the server hears of
 * the resume only when that count-in ends, so its clock stays stopped until then.
 *
 * <p>Practice sessions use the same class with no network at all.
 */
public final class ClientSession {
    private static final int MAX_LEAD = 30;
    private static final int SEND_EVERY = 2;
    /** The count-in after the help closes mid-run, so the player has their hands back on the keys first. */
    public static final int RESUME_TICKS = 40;

    @Nullable
    private static ClientSession active;
    /**
     * Test hook for the client smoke-test agent: when set, it supplies every frame from the predicted simulation instead
     * of the screen's keys and mouse, so the reference bots play through the real screens and packets. Null in play.
     */
    @Nullable
    public static volatile java.util.function.ToIntFunction<SimHandle> scriptedInput;

    public final UUID id;
    public final SessionKind kind;
    public final BlockPos pos;
    public final boolean practice;
    public final boolean resumed;
    public final CompoundTag display;
    private final long[] params;
    private SimHandle sim;
    private int countIn;
    private final int countInTotal;
    private int ackStep;
    private long[] ackState;
    private final List<Integer> recorded = new ArrayList<>();
    private int recordedFrom;
    private final List<Integer> pending = new ArrayList<>();
    private int pendingFrom = -1;
    private int sequence;
    private int ticks;
    private boolean ended;
    /** The help overlay is open. */
    private boolean paused;
    /** What the server was last told; it stays paused through the count-in after the help closes. */
    private boolean serverPaused;
    /** Ticks left of the count-in after the help closed mid-run. */
    private int resumeIn;
    @Nullable
    private IntSupplier input;
    @Nullable
    private CompoundTag result;
    @Nullable
    private String endReason;
    private boolean endSealed;

    private ClientSession(UUID id, SessionKind kind, BlockPos pos, long[] params, long[] state, int countIn, boolean practice, boolean resumed,
                          CompoundTag display) {
        this.id = id;
        this.kind = kind;
        this.pos = pos;
        this.params = params;
        this.sim = SimHandles.create(kind, params, state);
        if (resumed) this.sim.neutraliseHeld();
        this.countIn = countIn;
        this.countInTotal = Math.max(1, countIn);
        this.practice = practice;
        this.resumed = resumed;
        this.display = display;
        this.ackStep = sim.step();
        this.ackState = sim.saveState();
        this.recordedFrom = sim.step();
    }

    public static ClientSession start(UUID id, SessionKind kind, BlockPos pos, long[] params, long[] state, int countIn, boolean resumed, CompoundTag display) {
        active = new ClientSession(id, kind, pos, params, state, countIn, false, resumed, display);
        return active;
    }

    public static ClientSession practice(SessionKind kind, long[] params, CompoundTag display) {
        active = new ClientSession(UUID.randomUUID(), kind, BlockPos.ZERO, params, new long[0], 40, true, false, display);
        return active;
    }

    @Nullable
    public static ClientSession active() {
        return active;
    }

    public static void clear() {
        active = null;
    }

    /** The screen supplies the current packed input; null pauses input (neutral frames). */
    public void bindInput(@Nullable IntSupplier supplier) {
        this.input = supplier;
    }

    public SimHandle sim() {
        return sim;
    }

    /** Counting in: before the run starts or resumes, or before play continues after the help closed. */
    public boolean countingIn() {
        return countIn > 0 || resumeIn > 0;
    }

    /** Whether the count-in on screen continues interrupted work (a resumed batch, or play after the help). */
    public boolean resuming() {
        return resumed || resumeIn > 0;
    }

    /** 0..1 progress through the count-in, 1 when running. */
    public float countInProgress() {
        return countIn <= 0 ? 1F : 1F - (float) countIn / countInTotal;
    }

    public int countInSeconds() {
        return ((countIn > 0 ? countIn : resumeIn) + 19) / 20;
    }

    /** Stood still for the help: the overlay is open, or it just closed and play has not continued yet. */
    public boolean paused() {
        return paused || resumeIn > 0;
    }

    /**
     * The help overlay opened or closed. Opening it stops the session where it is, count-in included; closing it
     * continues the count-in, or mid-run counts in again first. A finished game has nothing left to stop.
     */
    public void setPaused(boolean paused) {
        if (ended || paused == this.paused) return;
        this.paused = paused;
        if (paused) {
            resumeIn = 0;
            if (!sim.finished()) tellServer(true);
        } else if (countIn > 0 || sim.finished()) {
            tellServer(false);
        } else {
            resumeIn = RESUME_TICKS;
        }
    }

    /**
     * Tells the server, after every frame recorded so far. A resume carries this client's timing: the next step it
     * simulates comes after the count-in still to run (none mid-run), which is what the server restarts its clock from.
     */
    private void tellServer(boolean paused) {
        if (practice || paused == serverPaused) return;
        flush();
        serverPaused = paused;
        ModNetwork.sendToServer(new PauseSessionC2S(id, paused, sim.step(), countIn));
    }

    public boolean ended() {
        return ended;
    }

    @Nullable
    public CompoundTag result() {
        return result;
    }

    @Nullable
    public String endReason() {
        return endReason;
    }

    public boolean endSealed() {
        return endSealed;
    }

    /** Client tick: count in, then advance one predicted step with the screen's input. Nothing moves while paused. */
    public void tick() {
        if (ended || paused) return;
        if (resumeIn > 0) {
            if (--resumeIn == 0) tellServer(false);
            return;
        }
        ticks++;
        if (countIn > 0) {
            countIn--;
            return;
        }
        if (sim.finished()) {
            if (practice) finishPractice();
            flush();
            return;
        }
        if (!practice && sim.step() - ackStep > MAX_LEAD) {
            flush();
            return;
        }
        java.util.function.ToIntFunction<SimHandle> script = scriptedInput;
        int frame = script != null ? script.applyAsInt(sim)
                : input != null ? input.getAsInt() : sim.neutral(recorded.isEmpty() ? 0 : recorded.get(recorded.size() - 1));
        if (!sim.valid(frame)) frame = sim.neutral(0);
        int step = sim.step();
        sim.step(frame);
        recorded.add(frame);
        if (!practice) {
            if (pendingFrom < 0) pendingFrom = step;
            pending.add(frame);
            if (pending.size() >= InputFramesC2S.MAX_FRAMES || ticks % SEND_EVERY == 0) flush();
        }
    }

    private void flush() {
        if (practice || pending.isEmpty()) return;
        int[] frames = new int[pending.size()];
        for (int i = 0; i < frames.length; i++) frames[i] = pending.get(i);
        ModNetwork.sendToServer(new InputFramesC2S(id, sequence++, pendingFrom, frames));
        pending.clear();
        pendingFrom = -1;
    }

    /** Server snapshot: rewind to it and replay this client's own frames after it. */
    public void reconcile(int step, long[] state) {
        if (practice || ended || step < ackStep) return;
        ackStep = step;
        ackState = state;
        SimHandle fresh;
        try {
            fresh = SimHandles.create(kind, params, state);
        } catch (IllegalArgumentException e) {
            return;
        }
        int local = sim.step();
        for (int s = step; s < local; s++) {
            int index = s - recordedFrom;
            if (index < 0 || index >= recorded.size() || fresh.finished()) break;
            fresh.step(recorded.get(index));
        }
        sim = fresh;
        // Frames before the acknowledged step are no longer needed.
        int drop = Math.min(recorded.size(), Math.max(0, step - recordedFrom));
        if (drop > 0) {
            recorded.subList(0, drop).clear();
            recordedFrom += drop;
        }
    }

    public void finish(CompoundTag result) {
        flush();
        this.result = result;
        this.ended = true;
    }

    public void end(String reason, boolean sealed) {
        this.endReason = reason;
        this.endSealed = sealed;
        this.ended = true;
    }

    /** The player left the screen: tell the server (it seals, or releases during the count-in). */
    public void leave() {
        if (ended) return;
        ended = true;
        endReason = "session.immersive_brewing.end.closed";
        if (!practice) ModNetwork.sendToServer(new LeaveSessionC2S(id));
    }

    private void finishPractice() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("practice", true);
        this.result = PracticeResults.describe(sim, tag);
        this.ended = true;
    }
}
