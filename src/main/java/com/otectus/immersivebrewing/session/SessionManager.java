package com.otectus.immersivebrewing.session;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.network.packet.SessionEndS2C;
import com.otectus.immersivebrewing.network.packet.SessionResultS2C;
import com.otectus.immersivebrewing.network.packet.SessionStartS2C;
import com.otectus.immersivebrewing.network.packet.StateSnapshotS2C;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side owner of every productive session (spec 20, 21). Server thread only.
 *
 * <p>Clock (ADR-2): one logical step per server tick. Step {@code k} is simulated {@code bufferTicks} after it is due, with
 * the controller's frame for {@code k} if it arrived and a neutral frame otherwise; one-shot actions from a frame that
 * arrived late are carried into the next step. A lagging server therefore slows the brew rather than skipping it.
 * While the controller has the help overlay open the clock stands still, count-in included; the resume restarts it
 * from the client's own timing (see {@link #pause}), so the playout buffer is whole again however late either message
 * was handled.
 *
 * <p>Invariants: a station has at most one controller and a player controls at most one session; a session ends by
 * completing (the station commits once), by sealing (state stored in the ledger) or, during the count-in of a fresh
 * job, by releasing the reservation.
 */
public final class SessionManager {
    private static final Map<UUID, ActiveSession> BY_PLAYER = new HashMap<>();
    private static final Map<GlobalPos, ActiveSession> BY_STATION = new HashMap<>();
    private static final int SNAPSHOT_EVERY = 10;
    private static final int MAX_PACKETS_PER_SECOND = 25;
    private static final int MAX_FRAMES_PER_SECOND = 32;
    /** Help toggles per second a reader could plausibly make; more still apply, but count as violations. */
    private static final int MAX_PAUSES_PER_SECOND = 4;
    /**
     * Ticks a resume adds before the playout buffer: the client simulates its next step on the tick after its count-in
     * ends, and one more tick of margin for a frame handled a tick late.
     */
    public static final int RESUME_SLACK = 2;
    private static final int MAX_VIOLATIONS = 40;
    private static final int MAX_CATCH_UP = 4;
    private static volatile long lastTickNanos;

    public static Optional<ActiveSession> byPlayer(UUID player) {
        return Optional.ofNullable(BY_PLAYER.get(player));
    }

    public static Optional<ActiveSession> byStation(GlobalPos station) {
        return Optional.ofNullable(BY_STATION.get(station));
    }

    public static boolean playerBusy(ServerPlayer player) {
        return BY_PLAYER.containsKey(player.getUUID());
    }

    public static boolean stationBusy(StationBlockEntity station) {
        return station.getLevel() != null && BY_STATION.containsKey(GlobalPos.of(station.getLevel().dimension(), station.getBlockPos()));
    }

    public static List<ActiveSession> all() {
        return List.copyOf(BY_PLAYER.values());
    }

    /** Input playout buffer for this player: the configured minimum, grown with measured latency up to the cap. */
    public static int bufferFor(ServerPlayer player) {
        int min = ServerConfig.get(ServerConfig.INPUT_BUFFER_TICKS);
        int max = Math.max(min, ServerConfig.get(ServerConfig.MAX_INPUT_BUFFER_TICKS));
        int fromLatency = (int) Math.ceil(Math.max(0, player.latency) / 50.0) + 2;
        return Math.max(min, Math.min(max, fromLatency));
    }

    /**
     * Starts (or resumes) a session after the station validated and reserved everything. Sends the parameters, seed-derived
     * schedule and state to the client, which plays the count-in.
     */
    public static ActiveSession begin(ServerPlayer player, StationBlockEntity station, SimHandle sim, boolean resumed, CompoundTag display) {
        ServerLevel level = (ServerLevel) station.getLevel();
        int buffer = bufferFor(player);
        int countIn = ServerConfig.get(ServerConfig.COUNT_IN_TICKS);
        long start = level.getServer().getTickCount() + countIn;
        int initial = sim.neutral(initialFrame(sim));
        ActiveSession session = new ActiveSession(player, player.getGameProfile().getName(), level.dimension(),
                station.getBlockPos(), sim, resumed, buffer, start, initial);
        session.windowStartTick = level.getServer().getTickCount();
        BY_PLAYER.put(session.playerId, session);
        BY_STATION.put(session.station(), session);
        station.setController(session.playerName);
        ModNetwork.sendTo(player, new SessionStartS2C(session.id, sim.kind(), station.getBlockPos(), sim.params(), sim.saveState(),
                countIn, buffer, resumed, display));
        debug("begin {} {} at {} for {} (buffer {}, resumed {})", sim.kind(), session.id, station.getBlockPos(), session.playerName, buffer, resumed);
        return session;
    }

    private static int initialFrame(SimHandle sim) {
        if (sim instanceof SimHandles.Brewing b) return com.otectus.immersivebrewing.minigame.brewing.KettleInput.neutral(b.sim().draft()).pack();
        if (sim instanceof SimHandles.Distillation d) {
            return new com.otectus.immersivebrewing.minigame.distillation.DistillationInput(d.sim().heatLevel(), d.sim().coolLevel(), d.sim().collecting()).pack();
        }
        if (sim instanceof SimHandles.Preparation p) {
            return com.otectus.immersivebrewing.minigame.preparation.PreparationInput.of(p.sim().pestleX(), p.sim().pestleY(), false, false, false).pack();
        }
        return 0;
    }

    // ---- ticking ----

    public static void tick(MinecraftServer server) {
        if (BY_PLAYER.isEmpty()) {
            lastTickNanos = 0;
            return;
        }
        long started = System.nanoTime();
        try {
            tickSessions(server);
        } finally {
            lastTickNanos = System.nanoTime() - started;
        }
    }

    /** Wall time the last server tick spent on sessions, for the performance test and the operator report. */
    public static long lastTickNanos() {
        return lastTickNanos;
    }

    private static void tickSessions(MinecraftServer server) {
        long now = server.getTickCount();
        for (ActiveSession s : new ArrayList<>(BY_PLAYER.values())) {
            ServerPlayer player = playerOf(server, s);
            ServerLevel level = server.getLevel(s.dimension);
            StationBlockEntity station = level != null && level.isLoaded(s.pos) && level.getBlockEntity(s.pos) instanceof StationBlockEntity be ? be : null;
            if (station == null || station.ledger() == null) {
                drop(s, player, "session.immersive_brewing.end.station_gone");
                continue;
            }
            String problem = problem(s, player, station);
            if (problem != null) {
                seal(s, station, player, problem);
                continue;
            }
            // The controller is reading the help: nothing is due until the resume restarts the clock (see pause).
            if (s.paused()) continue;
            if (!s.running) {
                if (now < s.runStartTick) continue;
                s.running = true;
                if (s.resumed) s.sim.neutraliseHeld();
                station.onRunStarted(player, s.resumed);
            }
            long due = s.initialStep + now - s.runStartTick - s.bufferTicks;
            int advanced = 0;
            while (!s.sim.finished() && s.sim.step() <= due && advanced < MAX_CATCH_UP) {
                int k = s.sim.step();
                Integer frame = s.frames.remove(k);
                int packed = frame != null ? frame : s.sim.neutral(s.lastPacked);
                packed |= s.pendingEdges & s.sim.edgeMask();
                s.pendingEdges = 0;
                s.sim.step(packed);
                s.lastPacked = packed & ~s.sim.edgeMask();
                advanced++;
            }
            if (advanced > 0) {
                s.frames.keySet().removeIf(step -> step < s.sim.step());
                if (s.sim.step() % SNAPSHOT_EVERY < advanced || s.sim.finished()) {
                    ModNetwork.sendTo(player, new StateSnapshotS2C(s.id, s.sim.step(), s.sim.saveState()));
                    station.setVisual(s.sim.visual());
                }
            }
            if (s.sim.finished()) complete(s, station, player);
        }
    }

    /** The session's player while it is still the live one for that UUID (fake players are never in the player list). */
    @Nullable
    private static ServerPlayer playerOf(MinecraftServer server, ActiveSession s) {
        ServerPlayer listed = server.getPlayerList().getPlayer(s.playerId);
        if (listed != null && listed != s.player) return null;
        return s.player;
    }

    @Nullable
    private static String problem(ActiveSession s, @Nullable ServerPlayer player, StationBlockEntity station) {
        if (player == null || player.hasDisconnected()) return "session.immersive_brewing.end.disconnected";
        if (player.isDeadOrDying() || player.isRemoved()) return "session.immersive_brewing.end.died";
        if (player.level().dimension() != s.dimension) return "session.immersive_brewing.end.dimension";
        double max = ServerConfig.get(ServerConfig.MAX_STATION_DISTANCE);
        if (player.distanceToSqr(s.pos.getX() + 0.5, s.pos.getY() + 0.5, s.pos.getZ() + 0.5) > max * max) {
            return "session.immersive_brewing.end.range";
        }
        if (s.violations > MAX_VIOLATIONS) return "session.immersive_brewing.end.invalid_input";
        if (s.running && s.sim.step() > com.otectus.immersivebrewing.minigame.brewing.KettleParams.SAFETY_LIMIT_STEPS + 200) {
            return "session.immersive_brewing.end.too_long";
        }
        return null;
    }

    private static void complete(ActiveSession s, StationBlockEntity station, @Nullable ServerPlayer player) {
        remove(s, station);
        CompoundTag result = station.completeJob(player, s.sim);
        if (player != null) {
            if (result != null) ModNetwork.sendTo(player, new SessionResultS2C(s.id, s.sim.kind(), result));
            else ModNetwork.sendTo(player, new SessionEndS2C(s.id, "session.immersive_brewing.end.held", true));
        }
        station.setVisual(StationVisual.IDLE);
        debug("complete {} {}", s.sim.kind(), s.id);
    }

    /** Seals a running session (or, during the count-in of a fresh job, releases the reservation). */
    public static void seal(ActiveSession s, StationBlockEntity station, @Nullable ServerPlayer player, String reason) {
        remove(s, station);
        boolean sealed;
        if (s.running) {
            station.sealJob(s.sim.saveState());
            sealed = true;
        } else {
            station.cancelCountIn(s.resumed);
            sealed = s.resumed;
        }
        station.setVisual(StationVisual.IDLE);
        if (player != null) {
            ModNetwork.sendTo(player, new SessionEndS2C(s.id, reason, sealed));
            ModNetwork.notice(player, Component.translatable(sealed ? "notice.immersive_brewing.sealed" : "notice.immersive_brewing.released",
                    Component.translatable(reason)), false);
        }
        debug("seal {} {}: {} (sealed {})", s.sim.kind(), s.id, reason, sealed);
    }

    /** A session whose station disappeared: nothing to seal into (the station packaged its ledger itself). */
    private static void drop(ActiveSession s, @Nullable ServerPlayer player, String reason) {
        BY_PLAYER.remove(s.playerId, s);
        BY_STATION.remove(s.station(), s);
        if (player != null) ModNetwork.sendTo(player, new SessionEndS2C(s.id, reason, false));
    }

    private static void remove(ActiveSession s, StationBlockEntity station) {
        BY_PLAYER.remove(s.playerId, s);
        BY_STATION.remove(s.station(), s);
        station.setController(null);
    }

    // ---- input ----

    public static void receive(ServerPlayer player, InputFramesC2S msg) {
        ActiveSession s = BY_PLAYER.get(player.getUUID());
        if (s == null || !s.id.equals(msg.sessionId())) return; // stale or forged session id: ignored, no debit
        if (msg.malformed() || msg.frames().length > InputFramesC2S.MAX_FRAMES) {
            s.violations += 4;
            return;
        }
        window(s, player.server.getTickCount());
        s.packetsInWindow++;
        s.framesInWindow += msg.frames().length;
        if (s.packetsInWindow > MAX_PACKETS_PER_SECOND || s.framesInWindow > MAX_FRAMES_PER_SECOND) {
            s.violations++;
            return;
        }
        if (msg.sequence() <= s.lastSequence) {
            s.violations++;
            return; // duplicate or reordered packet
        }
        s.lastSequence = msg.sequence();
        int current = s.sim.step();
        for (int i = 0; i < msg.frames().length; i++) {
            int step = msg.firstStep() + i;
            int packed = msg.frames()[i];
            if (!s.sim.valid(packed)) {
                s.violations++;
                continue;
            }
            if (step <= s.lastFrameStep) continue; // already have it
            // Range checks come before the step is recorded as received: a refused frame must not hide later good ones.
            if (step > current + s.bufferTicks + ActiveSession.MAX_FUTURE) {
                s.violations++;
                continue; // unreasonably far ahead of the server clock
            }
            if (step >= s.sim.totalSteps()) continue;
            s.lastFrameStep = step;
            if (step < current) {
                if (step >= current - ActiveSession.MAX_LATE) s.pendingEdges |= packed & s.sim.edgeMask();
                continue;
            }
            s.frames.put(step, packed);
        }
    }

    /** Starts a new one-second rate window when the current one is over. */
    private static void window(ActiveSession s, long now) {
        if (now - s.windowStartTick < 20) return;
        s.windowStartTick = now;
        s.packetsInWindow = 0;
        s.framesInWindow = 0;
        s.pausesInWindow = 0;
    }

    /**
     * The controller opened ({@code paused}) or closed the help overlay. The clock stops when the pause arrives. The
     * resume restarts it from the client's own timing, never from when either message was handled (a server busy as the
     * pause came in would otherwise start the run early and see every frame late): the client simulates {@code step}
     * after {@code countIn} more ticks, so that step falls due {@link #RESUME_SLACK} ticks after that and is simulated a
     * playout buffer later, exactly like the first step after a count-in. Frames already buffered keep their steps.
     *
     * <p>A repeated toggle changes nothing and is a violation; an impossible step or count-in is clamped and is one too.
     * Toggles faster than {@link #MAX_PAUSES_PER_SECOND} still apply (refusing one would leave client and server
     * disagreeing about the clock) but each counts as a violation, so a flood seals.
     */
    public static void pause(ServerPlayer player, UUID sessionId, boolean paused, int step, int countIn) {
        ActiveSession s = BY_PLAYER.get(player.getUUID());
        if (s == null || !s.id.equals(sessionId)) return; // stale or forged session id: ignored, no debit
        if (paused == s.paused()) {
            s.violations++;
            return;
        }
        long now = player.server.getTickCount();
        window(s, now);
        if (++s.pausesInWindow > MAX_PAUSES_PER_SECOND) s.violations++;
        if (paused) {
            s.pausedSince = now;
            debug("pause {} {} at step {}", s.sim.kind(), s.id, s.sim.step());
            return;
        }
        int current = s.sim.step();
        int next = Math.max(current - ActiveSession.MAX_LATE, Math.min(current + s.bufferTicks + ActiveSession.MAX_FUTURE, step));
        int wait = Math.max(0, Math.min(ServerConfig.get(ServerConfig.COUNT_IN_TICKS), countIn));
        if (next != step || wait != countIn) s.violations++;
        s.runStartTick = now + wait + RESUME_SLACK - (next - s.initialStep);
        s.pausedSince = -1;
        debug("resume {} {} at step {}: client step {} after {} ticks", s.sim.kind(), s.id, current, next, wait);
    }

    /** The controller closed the game screen: seal a running job, release a reservation still counting in. */
    public static void leave(ServerPlayer player, UUID sessionId, String reason) {
        ActiveSession s = BY_PLAYER.get(player.getUUID());
        if (s == null || !s.id.equals(sessionId)) return;
        StationBlockEntity station = stationOf(player.server, s);
        if (station == null) {
            drop(s, player, "session.immersive_brewing.end.station_gone");
            return;
        }
        seal(s, station, player, reason);
    }

    // ---- lifecycle hooks ----

    public static void sealPlayer(ServerPlayer player, String reason) {
        ActiveSession s = BY_PLAYER.get(player.getUUID());
        if (s == null) return;
        StationBlockEntity station = stationOf(player.server, s);
        if (station == null) drop(s, player, reason);
        else seal(s, station, player, reason);
    }

    public static void sealAll(MinecraftServer server, String reason) {
        for (ActiveSession s : new ArrayList<>(BY_PLAYER.values())) {
            ServerPlayer player = playerOf(server, s);
            StationBlockEntity station = stationOf(server, s);
            if (station == null) drop(s, player, reason);
            else seal(s, station, player, reason);
        }
    }

    public static void sealChunk(ServerLevel level, ChunkPos chunk) {
        for (ActiveSession s : new ArrayList<>(BY_PLAYER.values())) {
            if (s.dimension != level.dimension() || !new ChunkPos(s.pos).equals(chunk)) continue;
            ServerPlayer player = playerOf(level.getServer(), s);
            if (level.getBlockEntity(s.pos) instanceof StationBlockEntity station) seal(s, station, player, "session.immersive_brewing.end.unloaded");
            else drop(s, player, "session.immersive_brewing.end.unloaded");
        }
    }

    /**
     * The station is being removed: store the live state in its ledger, which the station then packages whole. Unlike
     * {@link #seal}, a fresh job still counting in is not released: its reservation (fuel charges included) stays in the
     * ledger, so the drops are the same whether the block's loot was computed before or after its removal.
     */
    public static void stationRemoved(StationBlockEntity station) {
        if (station.getLevel() == null) return;
        ActiveSession s = BY_STATION.get(GlobalPos.of(station.getLevel().dimension(), station.getBlockPos()));
        if (s == null) return;
        ServerPlayer player = station.getLevel().getServer() == null ? null : playerOf(station.getLevel().getServer(), s);
        remove(s, station);
        if (s.running) station.sealJob(s.sim.saveState());
        if (player != null) ModNetwork.sendTo(player, new SessionEndS2C(s.id, "session.immersive_brewing.end.broken", true));
        debug("station removed under {} {}", s.sim.kind(), s.id);
    }

    /** Writes a live session's state into the station's ledger (called when the station saves). */
    public static void flush(StationBlockEntity station) {
        if (station.getLevel() == null || station.getLevel().isClientSide) return;
        ActiveSession s = BY_STATION.get(GlobalPos.of(station.getLevel().dimension(), station.getBlockPos()));
        if (s != null && s.running && station.ledger() != null) station.ledger().setSimState(s.sim.saveState());
    }

    public static void clear() {
        BY_PLAYER.clear();
        BY_STATION.clear();
    }

    @Nullable
    private static StationBlockEntity stationOf(MinecraftServer server, ActiveSession s) {
        ServerLevel level = server.getLevel(s.dimension);
        if (level == null || !level.isLoaded(s.pos)) return null;
        return level.getBlockEntity(s.pos) instanceof StationBlockEntity be ? be : null;
    }

    @Nullable
    public static ActiveSession at(ServerLevel level, BlockPos pos) {
        return BY_STATION.get(GlobalPos.of(level.dimension(), pos));
    }

    private static void debug(String message, Object... args) {
        if (ServerConfig.debug()) ImmersiveBrewing.LOGGER.info("[sessions] " + message, args);
    }

    private SessionManager() {}
}
