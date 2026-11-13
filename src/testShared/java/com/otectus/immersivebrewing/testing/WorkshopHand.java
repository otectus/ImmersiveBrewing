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
        return new WorkshopIntentC2S(player.level().dimension().location(), pos, WorkshopIntent.ANY_JOB, sequence, part, action.ordinal(), argument,
                (float) x, (float) z, WorkshopIntentC2S.flags(contact, false, batch, effort.ordinal()));
    }

    /** The next sequence number this hand would use (for replay tests). */
    public int nextSequence() {
        return SEQUENCES.getOrDefault(player.getUUID(), 0) + 1;
    }

    public void skipTo(int sequence) {
        SEQUENCES.put(player.getUUID(), sequence);
    }
}
