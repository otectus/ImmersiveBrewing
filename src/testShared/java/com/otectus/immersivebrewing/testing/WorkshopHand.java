package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopIntents;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A test player's hand at a station: every action goes through the same envelope, sequence, budget, presence, reach,
 * line-of-sight and station checks a real client's packets do ({@link WorkshopIntents#receive}). Never shipped.
 */
public final class WorkshopHand {
    private static final Map<UUID, Integer> SEQUENCES = new HashMap<>();

    private final ServerPlayer player;
    private final BlockPos pos;

    public WorkshopHand(ServerPlayer player, BlockPos pos) {
        this.player = player;
        this.pos = pos.immutable();
    }

    public BlockPos pos() {
        return pos;
    }

    public ServerPlayer player() {
        return player;
    }

    public WorkshopResult act(int part, WorkshopAction action, int argument) {
        return send(part, action, argument, 0, 0, false, Effort.NORMAL, false);
    }

    public WorkshopResult batch(int part, WorkshopAction action, int argument) {
        return send(part, action, argument, 0, 0, false, Effort.NORMAL, true);
    }

    public WorkshopResult sample(int part, double x, double z, boolean contact, Effort effort) {
        return send(part, WorkshopAction.SAMPLE, 0, x, z, contact, effort, false);
    }

    /** A tool sample with its posture: {@code deep} works it along the bottom of the material. */
    public WorkshopResult sample(int part, double x, double z, boolean contact, Effort effort, boolean deep) {
        int sequence = SEQUENCES.merge(player.getUUID(), 1, Integer::sum);
        WorkshopIntentC2S plain = envelope(sequence, part, WorkshopAction.SAMPLE, 0, x, z, contact, effort, false);
        return WorkshopIntents.receive(player, withFlags(plain, plain.flags() | (deep ? WorkshopIntentC2S.DEEP : 0), WorkshopIntent.NO_REVISION));
    }

    /** A commit that asks for an acknowledgement (and, with {@code setup}, accepts the recipe page at {@code revision}). */
    public WorkshopResult commit(int part, WorkshopAction action, int argument, boolean batch, boolean setup, long revision) {
        int sequence = SEQUENCES.merge(player.getUUID(), 1, Integer::sum);
        WorkshopIntentC2S plain = envelope(sequence, part, action, argument, 0, 0, false, Effort.NORMAL, batch);
        int flags = plain.flags() | WorkshopIntentC2S.ACK | (setup ? WorkshopIntentC2S.SETUP : 0);
        return WorkshopIntents.receive(player, withFlags(plain, flags, revision));
    }

    /** The same envelope with other flags and expected revision (and the hand's current view epoch). */
    public WorkshopIntentC2S withFlags(WorkshopIntentC2S e, int flags, long revision) {
        return new WorkshopIntentC2S(e.dimension(), e.pos(), e.job(), e.epoch(), e.sequence(), e.part(), e.action(), e.argument(), e.x(), e.z(), flags,
                revision);
    }

    /** An envelope stamped with an explicit view epoch (stale-view tests). */
    public WorkshopIntentC2S withEpoch(WorkshopIntentC2S e, int epoch) {
        return new WorkshopIntentC2S(e.dimension(), e.pos(), e.job(), epoch, e.sequence(), e.part(), e.action(), e.argument(), e.x(), e.z(), e.flags(),
                e.expected());
    }

    public WorkshopResult press(double x, double z, Effort effort) {
        return send(0, WorkshopAction.PRESS, 0, x, z, true, effort, false);
    }

    public WorkshopResult send(int part, WorkshopAction action, int argument, double x, double z, boolean contact, Effort effort, boolean batch) {
        int sequence = SEQUENCES.merge(player.getUUID(), 1, Integer::sum);
        return WorkshopIntents.receive(player, envelope(sequence, part, action, argument, x, z, contact, effort, batch));
    }

    /** A raw envelope (protocol tests forge and replay these). */
    public WorkshopIntentC2S envelope(int sequence, int part, WorkshopAction action, int argument, double x, double z, boolean contact,
                                      Effort effort, boolean batch) {
        // Stamped with the player's current view epoch, as a real client does.
        return new WorkshopIntentC2S(player.level().dimension().location(), pos, WorkshopIntent.ANY_JOB,
                com.otectus.immersivebrewing.interaction.WorkshopPresence.epoch(player.getUUID()), sequence, part, action.ordinal(), argument,
                (float) x, (float) z, WorkshopIntentC2S.flags(contact, false, batch, effort.ordinal()), WorkshopIntent.NO_REVISION);
    }

    /** The next sequence number this hand would use (for replay tests). */
    public int nextSequence() {
        return SEQUENCES.getOrDefault(player.getUUID(), 0) + 1;
    }

    public void skipTo(int sequence) {
        SEQUENCES.put(player.getUUID(), sequence);
    }
}
