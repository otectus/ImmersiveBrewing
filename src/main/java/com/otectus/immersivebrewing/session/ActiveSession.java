package com.otectus.immersivebrewing.session;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One productive session on the server: who controls it, which station, its simulation, the logical clock and the
 * bounded input buffer. Transient: the station's ledger is the persistent record; this object only exists while a
 * controller is connected and in range. The clock stands still while the controller has the help overlay open.
 */
public final class ActiveSession {
    /** Frames for steps further than this beyond the server's step are refused as unreasonably future (2 s). */
    static final int MAX_FUTURE = 40;
    /** Frames this far behind are too old to carry even their one-shot actions. */
    static final int MAX_LATE = 40;

    final UUID id = UUID.randomUUID();
    /** The controlling player object; a relog or respawn makes a new object, and both of those seal first. */
    final net.minecraft.server.level.ServerPlayer player;
    final UUID playerId;
    final String playerName;
    final ResourceKey<Level> dimension;
    final BlockPos pos;
    final SimHandle sim;
    final boolean resumed;
    final int bufferTicks;
    /** Simulation step at the start of this run (nonzero when resuming). */
    final int initialStep;
    /** Server tick at which initialStep is due (after the count-in); a resume from the help sets it again. */
    long runStartTick;
    /** Server tick the controller paused at (help overlay open), or -1 while the clock runs. */
    long pausedSince = -1;
    boolean running;
    int lastPacked;
    int pendingEdges;
    int lastSequence = -1;
    int lastFrameStep = -1;
    final Map<Integer, Integer> frames = new HashMap<>();
    // rate limiting
    long windowStartTick;
    int packetsInWindow;
    int framesInWindow;
    int pausesInWindow;
    int violations;

    ActiveSession(net.minecraft.server.level.ServerPlayer player, String playerName, ResourceKey<Level> dimension, BlockPos pos, SimHandle sim,
                  boolean resumed, int bufferTicks, long runStartTick, int initialPacked) {
        this.player = player;
        this.playerId = player.getUUID();
        this.playerName = playerName;
        this.dimension = dimension;
        this.pos = pos.immutable();
        this.sim = sim;
        this.initialStep = sim.step();
        this.resumed = resumed;
        this.bufferTicks = bufferTicks;
        this.runStartTick = runStartTick;
        this.lastPacked = initialPacked;
    }

    public UUID id() {
        return id;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public GlobalPos station() {
        return GlobalPos.of(dimension, pos);
    }

    public BlockPos pos() {
        return pos;
    }

    public SimHandle sim() {
        return sim;
    }

    public boolean running() {
        return running;
    }

    /** Whether the controller has the help overlay open (the clock, count-in included, stands still). */
    public boolean paused() {
        return pausedSince >= 0;
    }

    public int bufferTicks() {
        return bufferTicks;
    }

    public long runStartTick() {
        return runStartTick;
    }

    /** Input violations counted so far (rate, sequence, validity, range); above the limit the session seals. */
    public int violations() {
        return violations;
    }

    /** Frames waiting in the playout buffer. */
    public int bufferedFrames() {
        return frames.size();
    }

    /** The frame buffered for a logical step, or null. */
    public Integer bufferedFrame(int step) {
        return frames.get(step);
    }
}
