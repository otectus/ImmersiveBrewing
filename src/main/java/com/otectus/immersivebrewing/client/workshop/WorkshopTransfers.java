package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.StationNotices;
import com.otectus.immersivebrewing.client.presentation.KettleSurface;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visible transfers (handoff 7.3): a small, bounded state machine per station for every bottle, dose cup, spout fill and
 * placement that moves through the scene: lift, travel, align, tip, flow, return, settle. It is presentation only. The
 * resource stays owned at its source or by the batch until the server accepts the commit; the stream flows only after
 * that, and the source drains and the destination fills around the accepted boundary. A refusal, a timeout, a lost
 * view or a reconnect returns the object to where the server says it is; a duplicate or late acknowledgement for a
 * settled transfer changes nothing. Observers build the same motion from replicated carry poses, effect events and the
 * batch queue's timing, never from a replay of earlier pours.
 */
public final class WorkshopTransfers {
    public enum Kind { POUR, DOSE, FILL, PLACE }

    public enum Phase { LIFT, TRAVEL, TIP, FLOW, RETURN, SETTLE }

    public static final long LIFT_MS = 140, TRAVEL_MS = 240, TIP_MS = 200, POUR_FLOW_MS = 520, DOSE_FLOW_MS = 420, FILL_FLOW_MS = 520,
            RETURN_MS = 260, SETTLE_MS = 120, PLACE_MS = 220, ACK_TIMEOUT_MS = 2500, EFFECT_WAIT_MS = 1500;
    public static final int MAX_PER_STATION = 8;
    /** Carrying height (model pixels): a bottle's base clears the near rim, the spout pipe and the other bottles. */
    public static final float CARRY_Y = 11.4F;

    /** One moving object. Positions are model pixels of the north-authored station; tilt is degrees. */
    public static final class Transfer {
        public final BlockPos pos;
        public final WorkshopKind station;
        public Kind kind;
        /** The part it belongs to (a rail position, a dose cup, a tray position, the fuel hatch). */
        public int part;
        public final boolean local;
        @Nullable
        public UUID holder;
        public Phase phase;
        long phaseStart;
        /** The acknowledged operation (local), or -1. */
        int sequence = -1;
        /** The server committed it (acknowledged, or its effect arrived). */
        public boolean committed;
        boolean refused;
        /** Observers and batch queues: waiting for the effect that commits this transfer. */
        boolean awaitingEffect;
        long expectedAt;
        /** Whether the latest snapshot already counts the transfer's change (for the liquid level). */
        boolean counted;
        public float x, y, z, tilt;
        float fromX, fromY, fromZ;
        /** Where it tips (over the vessel) or where the carriage outlet sits. */
        float tipX, tipY, tipZ;
        /** A dose's representatives were added to the liquid (once, when its flow began). */
        public boolean spawned;

        Transfer(BlockPos pos, WorkshopKind station, Kind kind, int part, boolean local) {
            this.pos = pos.immutable();
            this.station = station;
            this.kind = kind;
            this.part = part;
            this.local = local;
            this.phase = Phase.LIFT;
            this.phaseStart = Util.getMillis();
            double[] home = home(station, part);
            this.x = this.fromX = (float) home[0];
            this.y = this.fromY = (float) home[1];
            this.z = this.fromZ = (float) home[2];
        }

        void enter(Phase next) {
            phase = next;
            phaseStart = Util.getMillis();
            fromX = x;
            fromY = y;
            fromZ = z;
        }

        long age() {
            return Util.getMillis() - phaseStart;
        }

        /** 0..1 through the current phase. */
        public float progress() {
            long d = duration();
            return d <= 0 ? 1 : Mth.clamp((float) age() / d, 0, 1);
        }

        long duration() {
            return switch (phase) {
                case LIFT -> kind == Kind.PLACE ? PLACE_MS : LIFT_MS;
                case TRAVEL -> local ? Long.MAX_VALUE : kind == Kind.FILL ? 220 : TRAVEL_MS;
                case TIP -> kind == Kind.FILL ? 100 : TIP_MS;
                case FLOW -> switch (kind) {
                    case DOSE -> DOSE_FLOW_MS;
                    case FILL -> FILL_FLOW_MS;
                    default -> POUR_FLOW_MS;
                };
                case RETURN -> kind == Kind.FILL ? 120 : RETURN_MS;
                case SETTLE -> SETTLE_MS;
            };
        }

        /** How far the stream has run (0 before the flow, 1 after it). */
        public float flow() {
            return switch (phase) {
                case LIFT, TRAVEL, TIP -> 0;
                case FLOW -> progress();
                default -> committed ? 1 : 0;
            };
        }
    }

    private static final Map<BlockPos, List<Transfer>> BY_STATION = new HashMap<>();
    @Nullable
    private static Transfer local;
    /** The batch queue each station announced: {kind (1 pour, 2 fill), queued, game time of the next commit}. */
    private static final Map<BlockPos, long[]> QUEUES = new HashMap<>();

    // ---- where things live ----

    /** A carriable or loadable part's resting position, in model pixels {x, y, z} (the base centre of what stands there). */
    public static double[] home(WorkshopKind kind, int part) {
        if (kind == WorkshopKind.KETTLE) {
            if (part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + 6) {
                return new double[] {WorkshopParts.railX(part - WorkshopParts.K_RACK), WorkshopParts.RAIL_TOP, WorkshopParts.RAIL_Z};
            }
        }
        WorkshopPart p = WorkshopParts.get(kind, part);
        if (p == null) return new double[] {8, 8, 8};
        return new double[] {p.cx(), p.y0(), p.cz()};
    }

    /** Where a bottle or cup is tipped over the vessel mouth (the auto path of a batch or another player's tip). */
    private static float[] mouth(int part) {
        if (part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS) {
            return new float[] {WorkshopParts.KETTLE_CX + 2.2F, CARRY_Y, WorkshopParts.KETTLE_CZ - 1.5F};
        }
        int slot = part - WorkshopParts.K_RACK;
        float x = Mth.clamp(WorkshopParts.railX(slot), WorkshopParts.KETTLE_CX - 2.5F, WorkshopParts.KETTLE_CX + 2.5F);
        return new float[] {x, CARRY_Y, WorkshopParts.KETTLE_CZ - 3.2F};
    }

    // ---- the local hand ----

    /** The local player lifted a part (the GRAB's acknowledgement may still bring it back). */
    static void lift(BlockPos pos, WorkshopKind station, int part, int op) {
        abandonLocal();
        Transfer t = new Transfer(pos, station, part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS ? Kind.DOSE : Kind.POUR,
                part, true);
        t.sequence = op;
        t.holder = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        add(t);
        local = t;
    }

    /** The carried part goes back: refused, cancelled, or the view ended. */
    static void putBack(BlockPos pos, int part, @Nullable Component reason) {
        Transfer t = local;
        if (t == null || !t.pos.equals(pos) || t.part != part) return;
        local = null;
        if (t.phase == Phase.RETURN || t.phase == Phase.SETTLE) return;
        t.enter(Phase.RETURN);
        if (reason != null) StationNotices.show(reason, true);
    }

    /** The local player tipped what they carry: the tip shows now, the flow waits for the server. */
    static void tip(BlockPos pos, int part, int op) {
        Transfer t = local;
        if (t == null || !t.pos.equals(pos) || t.part != part) return;
        local = null;
        t.sequence = op;
        t.tipX = t.x;
        t.tipY = t.y;
        t.tipZ = t.z;
        t.enter(Phase.TIP);
    }

    /** The answer to an acknowledged commit (from the server, or from the practice bench at once). */
    static void acknowledged(BlockPos pos, int op, boolean accepted, @Nullable Component reason) {
        for (Transfer t : list(pos)) {
            if (t.sequence != op) continue;
            if (t.committed || t.refused) return; // a duplicate: the transfer already settled
            if (accepted) {
                // A GRAB's acceptance only confirms the carry; a tip's acceptance commits the transfer.
                if (t.phase == Phase.TIP || t.phase == Phase.FLOW) t.committed = true;
            } else {
                t.refused = true;
                if (t == local) local = null;
                ClientWorkshop.resetCarryIf(t.part);
                t.enter(Phase.RETURN);
                if (reason != null) StationNotices.show(reason, true);
            }
            return;
        }
    }

    /** Drops the local carry without a return animation (the view moved or closed). */
    static void abandonLocal() {
        Transfer t = local;
        local = null;
        if (t != null && t.phase != Phase.RETURN && t.phase != Phase.SETTLE) t.enter(Phase.RETURN);
    }

    // ---- what others (and batch queues) do ----

    /** A snapshot arrived: other players' carries and the batch queue's timing. */
    static void observe(StationBlockEntity station) {
        BlockPos pos = station.getBlockPos();
        CompoundTag snap = station.workshopView();
        var mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        WorkshopKind kind = station.workshopKind();
        ListTag held = snap.getList("held", Tag.TAG_COMPOUND);
        List<Transfer> list = list(pos);
        for (int i = 0; i < held.size(); i++) {
            CompoundTag h = held.getCompound(i);
            if (!h.getBoolean("carry")) continue;
            UUID holder = h.hasUUID("id") ? h.getUUID("id") : null;
            if (holder != null && holder.equals(self)) continue;
            int part = h.getByte("part");
            Transfer t = find(list, part, false);
            if (t == null) {
                t = new Transfer(pos, kind, part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS ? Kind.DOSE : Kind.POUR,
                        part, false);
                t.holder = holder;
                add(t);
            }
            if (t.phase == Phase.LIFT || t.phase == Phase.TRAVEL) {
                t.tipX = (h.getFloat("x") + 1) * 8;
                t.tipZ = (h.getFloat("z") + 1) * 8;
                t.tipY = CARRY_Y;
                t.awaitingEffect = true;
                t.expectedAt = Util.getMillis() + 60_000;
            }
        }
        // Carries that vanished without a commit go home.
        for (Transfer t : list) {
            if (t.local || t.committed || !t.awaitingEffect || t.holder == null) continue;
            if (t.phase != Phase.LIFT && t.phase != Phase.TRAVEL) continue;
            boolean still = false;
            for (int i = 0; i < held.size(); i++) if (held.getCompound(i).getByte("part") == t.part && held.getCompound(i).getBoolean("carry")) still = true;
            if (!still) {
                t.expectedAt = Util.getMillis() + 400; // its effect, if it was tipped, is on its way
            }
        }
        if (snap.contains("queue")) QUEUES.put(pos.immutable(), new long[] {snap.getByte("queue"), snap.getByte("queued"), snap.getLong("queueAt")});
        else QUEUES.remove(pos);
        // The snapshot now counts committed changes (the liquid level stops compensating for them).
        CompoundTag k = snap.getCompound("kettle");
        byte[] slots = k.getByteArray("slots");
        byte[] portions = k.getByteArray("portions");
        for (Transfer t : list) {
            if (!t.committed || t.counted) continue;
            int slot = t.part - WorkshopParts.K_RACK;
            int at = -1;
            for (int i = 0; i < slots.length; i++) if (slots[i] == slot) at = i;
            if (t.kind == Kind.POUR) t.counted = at >= 0 && at < portions.length && portions[at] != KettleProcess.Portion.STAGED.ordinal();
            else if (t.kind == Kind.FILL) t.counted = at < 0;
            else t.counted = true;
        }
    }

    /** An accepted server action: the motion it implies, unless this client already shows it. */
    static void effect(StationEffectEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(event.pos()) instanceof StationBlockEntity station)) return;
        BlockPos pos = event.pos();
        WorkshopKind kind = station.workshopKind();
        List<Transfer> list = list(pos);
        switch (event.kind()) {
            case POUR, ADDITION -> {
                int part = event.kind() == StationEffectEvent.Kind.POUR ? WorkshopParts.K_RACK + event.index() : WorkshopParts.K_CUP + event.index();
                Transfer t = find(list, part, true);
                if (t != null && t.local) {
                    t.committed = true; // ours, possibly before its acknowledgement
                } else if (t != null && !t.committed) {
                    t.committed = true;
                    t.awaitingEffect = false;
                    if (t.phase == Phase.LIFT || t.phase == Phase.TRAVEL) {
                        t.tipX = t.x;
                        t.tipY = t.y;
                        t.tipZ = t.z;
                        t.enter(Phase.TIP);
                    }
                } else if (t == null) {
                    // Nobody showed it being carried here (a batch, the keyboard path, a late observer): the compressed path.
                    Transfer auto = new Transfer(pos, kind, event.kind() == StationEffectEvent.Kind.POUR ? Kind.POUR : Kind.DOSE, part, false);
                    auto.committed = true;
                    float[] m = mouth(part);
                    auto.tipX = m[0];
                    auto.tipY = m[1];
                    auto.tipZ = m[2];
                    add(auto);
                }
                if (kind == WorkshopKind.KETTLE) KettleSurface.splash(pos, event.kind() == StationEffectEvent.Kind.POUR ? 1.0 : 0.7);
            }
            case FILL -> {
                int part = WorkshopParts.K_RACK + event.index();
                Transfer t = null;
                for (Transfer c : list) if (c.kind == Kind.FILL && !c.committed && (c.part == part || c.awaitingEffect)) t = c;
                if (t == null) {
                    t = new Transfer(pos, kind, Kind.FILL, part, false);
                    t.enter(Phase.TRAVEL);
                    add(t);
                }
                t.part = part;
                t.committed = true;
                t.awaitingEffect = false;
            }
            case LOAD -> {
                if (event.index() <= 0 && event.outcome() == 0) return; // an older station's plain load notice
                int part = event.index();
                if (kind == WorkshopKind.KETTLE && (part == WorkshopParts.K_VESSEL)) return;
                Transfer t = new Transfer(pos, kind, Kind.PLACE, part, false);
                t.committed = true;
                t.counted = true;
                t.y = t.fromY = t.fromY + 3.5F;
                add(t);
            }
            default -> { }
        }
    }

    // ---- per tick ----

    static void tick() {
        long now = Util.getMillis();
        var mc = Minecraft.getInstance();
        long game = mc.level == null ? 0 : mc.level.getGameTime();
        // Batch queues: lift the next bottle (or move the carriage) so it tips as the server commits it.
        for (Iterator<Map.Entry<BlockPos, long[]>> it = QUEUES.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            long[] q = e.getValue();
            if (mc.level == null || !(mc.level.getBlockEntity(e.getKey()) instanceof BrewersKettleBlockEntity kettle)) {
                it.remove();
                continue;
            }
            long lead = q[0] == 1 ? 12 : 6;
            if (q[2] <= 0 || q[2] - game > lead) continue;
            List<Transfer> list = list(e.getKey());
            boolean pending = false;
            for (Transfer t : list) if (t.awaitingEffect && !t.committed && t.holder == null) pending = true;
            if (pending) continue;
            int part = nextQueued(kettle, q[0] == 1);
            if (part < 0) continue;
            Transfer t = new Transfer(e.getKey(), WorkshopKind.KETTLE, q[0] == 1 ? Kind.POUR : Kind.FILL, part, false);
            t.awaitingEffect = true;
            t.expectedAt = now + (q[2] - game) * 50 + EFFECT_WAIT_MS;
            if (q[0] == 1) {
                float[] m = mouth(part);
                t.tipX = m[0];
                t.tipY = m[1];
                t.tipZ = m[2];
            } else {
                t.enter(Phase.TRAVEL);
            }
            add(t);
            q[2] = 0; // one pre-lift per announced step
        }
        for (Iterator<Map.Entry<BlockPos, List<Transfer>>> it = BY_STATION.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            List<Transfer> list = e.getValue();
            list.removeIf(t -> advance(t, now));
            if (list.isEmpty()) it.remove();
        }
    }

    /** Advances one transfer's phases; true when it is finished. */
    private static boolean advance(Transfer t, long now) {
        // An unanswered commit returns to the source (the server's state decides what happened).
        if (t.local && t.phase == Phase.TIP && !t.committed && t.age() > ACK_TIMEOUT_MS) {
            t.refused = true;
            t.enter(Phase.RETURN);
        }
        if (!t.local && t.awaitingEffect && !t.committed && now > t.expectedAt) {
            t.awaitingEffect = false;
            if (t.phase != Phase.RETURN && t.phase != Phase.SETTLE) t.enter(Phase.RETURN);
        }
        boolean motion = PresentationPolicy.current().animate();
        if (t.phase == Phase.TRAVEL && t.local) return false;
        if (t.progress() < 1 && motion) return false;
        switch (t.phase) {
            case LIFT -> {
                if (t.kind == Kind.PLACE) return true;
                if (t.local || !t.awaitingEffect || t.committed) t.enter(t.local ? Phase.TRAVEL : Phase.TRAVEL);
                else t.enter(Phase.TRAVEL);
            }
            case TRAVEL -> {
                if (t.kind == Kind.FILL) {
                    if (t.committed) t.enter(Phase.TIP);
                } else if (t.committed || !t.awaitingEffect) {
                    t.tipX = t.x;
                    t.tipY = t.y;
                    t.tipZ = t.z;
                    t.enter(Phase.TIP);
                }
            }
            case TIP -> {
                if (t.committed) t.enter(Phase.FLOW);
                else if (!t.local && !t.awaitingEffect) t.enter(Phase.RETURN);
            }
            case FLOW -> t.enter(t.kind == Kind.FILL ? Phase.SETTLE : Phase.RETURN);
            case RETURN -> t.enter(Phase.SETTLE);
            case SETTLE -> {
                return true;
            }
        }
        return false;
    }

    /** The rail position the next queued pour or fill will use (the server's own rule: in portion order, unheld). */
    private static int nextQueued(BrewersKettleBlockEntity kettle, boolean pour) {
        CompoundTag snap = kettle.workshopView();
        CompoundTag k = snap.getCompound("kettle");
        byte[] slots = k.getByteArray("slots");
        byte[] portions = k.getByteArray("portions");
        int want = pour ? KettleProcess.Portion.STAGED.ordinal() : KettleProcess.Portion.READY.ordinal();
        WorkshopTargets.View view = new WorkshopTargets.View(kettle, WorkshopKind.KETTLE, snap);
        for (int i = 0; i < Math.min(slots.length, portions.length); i++) {
            if (portions[i] != want) continue;
            int part = WorkshopParts.K_RACK + slots[i];
            if (view.holderId(part) != null) continue;
            if (find(list(kettle.getBlockPos()), part, true) != null) continue;
            if (!pour && !kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + slots[i]).isEmpty()) continue;
            return part;
        }
        return -1;
    }

    // ---- what the renderers read ----

    /** The transfers to draw at a station (a copy is not made: render thread only). */
    public static List<Transfer> at(BlockPos pos) {
        List<Transfer> list = BY_STATION.get(pos);
        return list == null ? List.of() : list;
    }

    /** One presentation owner per main hand, even while an outgoing cosmetic transfer is still returning. */
    public static boolean showsHand(Transfer transfer, WorkshopTargets.View view) {
        if (transfer.phase == Phase.SETTLE || transfer.holder == null) return false;
        UUID holder=transfer.holder;
        var mc=Minecraft.getInstance();
        boolean self=mc.player!=null&&holder.equals(mc.player.getUUID());
        if (self && transfer.pos.equals(ClientWorkshop.focus()) && ClientWorkshop.tool() >= 0) return false;
        ListTag held=view.snapshot().getList("held",Tag.TAG_COMPOUND);
        for(int i=0;i<held.size();i++) {
            CompoundTag h=held.getCompound(i);
            if(!h.getBoolean("carry")&&h.hasUUID("id")&&holder.equals(h.getUUID("id"))) return false;
        }
        if(self&&local!=null&&local!=transfer) return false;
        List<Transfer> transfers=at(transfer.pos);
        for(int i=transfers.size()-1;i>=0;i--) {
            Transfer candidate=transfers.get(i);
            if(candidate.phase!=Phase.SETTLE&&holder.equals(candidate.holder)) return candidate==transfer;
        }
        return false;
    }

    /** Whether a part's resting object is away (lifted, carried, tipping, returning) and must not also be drawn in place. */
    public static boolean away(BlockPos pos, int part) {
        for (Transfer t : at(pos)) if (t.part == part && t.kind != Kind.FILL) return true;
        return false;
    }

    /** A bottle being filled at the spout: its fill so far (0..1), or -1 when it is not being filled. */
    public static float filling(BlockPos pos, int part) {
        for (Transfer t : at(pos)) if (t.kind == Kind.FILL && t.part == part) return t.committed ? t.flow() : 0;
        return -1;
    }

    /** The spout carriage's x (model pixels): over the bottle being filled, else where it last was. */
    public static float carriageX(BlockPos pos) {
        Float last = CARRIAGE.get(pos);
        float rest = last == null ? WorkshopParts.KETTLE_CX : last;
        for (Transfer t : at(pos)) {
            if (t.kind != Kind.FILL) continue;
            float target = WorkshopParts.railX(t.part - WorkshopParts.K_RACK);
            float x = t.phase == Phase.TRAVEL ? Mth.lerp(ease(t.progress()), rest, target) : target;
            if (t.phase != Phase.TRAVEL) CARRIAGE.put(pos.immutable(), target);
            return x;
        }
        return rest;
    }

    private static final Map<BlockPos, Float> CARRIAGE = new HashMap<>();

    /**
     * The liquid the kettle shows, in servings: the snapshot's count, held back for pours whose stream has not finished
     * and kept up for fills that are still running, so the level moves with the stream rather than ahead of it.
     */
    public static double servings(BlockPos pos, int authoritative) {
        double shown = authoritative;
        for (Transfer t : at(pos)) {
            if (!t.committed) continue;
            float f = t.flow();
            if (t.kind == Kind.POUR) shown += t.counted ? -(1 - f) : f;
            else if (t.kind == Kind.FILL) shown += t.counted ? (1 - f) : -f;
        }
        return Math.max(0, shown);
    }

    /** The displayed liquid height (model pixels) of a kettle, for the paddle plane and the renderers. */
    public static double liquidHeight(BlockEntity be) {
        if (!(be instanceof StationBlockEntity station)) return WorkshopParts.KETTLE_LIQUID_MAX - 1;
        CompoundTag k = WorkshopTargets.view(be, WorkshopKind.KETTLE).kettle();
        int charged = 0;
        for (byte b : k.getByteArray("portions")) if (b != KettleProcess.Portion.STAGED.ordinal()) charged++;
        return WorkshopParts.kettleLevel(servings(station.getBlockPos(), charged));
    }

    /** Where the local carry is shown (smoothed toward the cursor), updated by the renderer each frame. */
    public static void followLocal(Transfer t, double tx, double tz, float partial) {
        float k = PresentationPolicy.current().animate() ? 0.35F : 1F;
        float lift = t.phase == Phase.LIFT ? ease(t.progress()) : 1;
        float targetY = Mth.lerp(lift, (float) home(t.station, t.part)[1], CARRY_Y);
        t.x += (float) (tx - t.x) * k;
        t.z += (float) (tz - t.z) * k;
        t.y = targetY;
    }

    /** The pose of a transfer this frame (model pixels and tilt), written into the transfer. */
    public static void pose(Transfer t) {
        double[] home = home(t.station, t.part);
        float p = ease(t.progress());
        boolean pour = t.kind == Kind.POUR || t.kind == Kind.DOSE;
        switch (t.phase) {
            case LIFT -> {
                if (t.kind == Kind.PLACE) {
                    t.x = (float) home[0];
                    t.z = (float) home[2];
                    t.y = Mth.lerp(bounce(t.progress()), (float) home[1] + 3.5F, (float) home[1]);
                } else if (!t.local) {
                    t.x = (float) home[0];
                    t.z = (float) home[2];
                    t.y = Mth.lerp(p, (float) home[1], CARRY_Y);
                }
                t.tilt = 0;
            }
            case TRAVEL -> {
                if (t.kind == Kind.FILL) {
                    t.x = (float) home[0];
                    t.y = (float) home[1];
                    t.z = (float) home[2];
                } else if (!t.local) {
                    t.x = Mth.lerp(p, t.fromX, t.tipX);
                    t.y = Mth.lerp(p, t.fromY, t.tipY) + (float) Math.sin(Math.PI * p) * 0.6F;
                    t.z = Mth.lerp(p, t.fromZ, t.tipZ);
                }
                t.tilt = 0;
            }
            case TIP -> {
                if (t.kind == Kind.FILL) {
                    t.y = (float) home[1] + 0.6F * p;
                    t.tilt = 0;
                } else {
                    // The hand draws back a little as it tips, keeping the neck over the mouth it was aimed at.
                    t.x = t.tipX;
                    t.y = t.tipY + 0.8F * p;
                    t.z = t.tipZ - (pour ? 3.0F : 1.2F) * p;
                    t.tilt = (pour ? 105 : 120) * p;
                }
            }
            case FLOW -> {
                if (t.kind == Kind.FILL) {
                    t.y = (float) home[1] + 0.6F;
                } else {
                    t.x = t.tipX;
                    t.y = t.tipY + 0.8F;
                    t.z = t.tipZ - (pour ? 3.0F : 1.2F);
                    t.tilt = pour ? 105 + 15 * p : 120;
                }
            }
            case RETURN -> {
                if (t.kind == Kind.FILL) {
                    t.y = (float) home[1] + 0.6F * (1 - p);
                } else {
                    t.x = Mth.lerp(p, t.fromX, (float) home[0]);
                    t.y = Mth.lerp(p, t.fromY, (float) home[1]) + (float) Math.sin(Math.PI * p) * 0.8F;
                    t.z = Mth.lerp(p, t.fromZ, (float) home[2]);
                    t.tilt = Mth.lerp(p, t.committed || t.refused ? 110 : 0, 0);
                    if (!t.committed && !t.refused) t.tilt = 0;
                }
            }
            case SETTLE -> {
                t.x = (float) home[0];
                t.y = (float) home[1] + (float) Math.sin(Math.PI * t.progress()) * 0.15F;
                t.z = (float) home[2];
                t.tilt = 0;
            }
        }
    }

    public static float ease(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float bounce(float t) {
        t = Mth.clamp(t, 0, 1);
        return t < 0.75F ? ease(t / 0.75F) : 1 - (float) Math.sin((t - 0.75F) / 0.25F * Math.PI) * 0.08F;
    }

    // ---- bookkeeping ----

    private static List<Transfer> list(BlockPos pos) {
        return BY_STATION.computeIfAbsent(pos.immutable(), p -> new ArrayList<>());
    }

    private static void add(Transfer t) {
        List<Transfer> list = list(t.pos);
        if (list.size() >= MAX_PER_STATION) list.remove(0);
        list.add(t);
    }

    @Nullable
    private static Transfer find(List<Transfer> list, int part, boolean any) {
        for (Transfer t : list) {
            if (t.part != part) continue;
            if (t.phase == Phase.RETURN || t.phase == Phase.SETTLE) continue;
            if (any || !t.local) return t;
        }
        return null;
    }

    /** The local carry, if any (the renderer follows the cursor with it). */
    @Nullable
    public static Transfer local() {
        return local;
    }

    public static void remove(BlockPos pos) {
        BY_STATION.remove(pos);
        QUEUES.remove(pos);
        CARRIAGE.remove(pos);
        if (local != null && local.pos.equals(pos)) local = null;
    }

    public static void clear() {
        BY_STATION.clear();
        QUEUES.clear();
        CARRIAGE.clear();
        local = null;
    }

    private WorkshopTransfers() {}
}
