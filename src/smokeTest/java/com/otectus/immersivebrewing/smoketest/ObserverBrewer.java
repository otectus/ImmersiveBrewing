package com.otectus.immersivebrewing.smoketest;

import com.mojang.authlib.GameProfile;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopIntents;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.testing.KettleTender;
import com.otectus.immersivebrewing.testing.WorkshopHand;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/**
 * A second brewer for the observer view (handoff 22.7): a server-side player at a kettle the client only watches from
 * the world. It acts through the same intent envelopes, presence, reach and lease checks a client's packets do, and
 * carries like a player: each bottle and dose cup is lifted, moved over the mouth for a moment (the carried pose is what
 * observers are sent) and tipped. Nothing about it is drawn as a body (it is not a listed player), so the observer sees
 * only what the apparatus shows: the carried objects, the paddle, the liquid and the filled bottles. Never shipped.
 */
final class ObserverBrewer {
    private static final int CARRY_TICKS = 16;

    private final BrewersKettleBlockEntity kettle;
    private final FakePlayer player;
    private final WorkshopHand hand;
    private int carrying = -1;
    private int carryTick;
    private double fromU, fromV, toU, toV;
    private WorkshopAction commit;
    private int argument;
    private boolean setup;
    private long revision;
    private boolean started, pourRequested, fillRequested, holding;
    private int cooldown;
    private double angle;
    private int carries;
    private int refused;

    ObserverBrewer(ServerLevel level, BlockPos pos) {
        kettle = (BrewersKettleBlockEntity) level.getBlockEntity(pos);
        player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("immersive_brewing.smoketest.observer".getBytes()), "Brewer"));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
        kettle.openWorkingView(player);
        hand = new WorkshopHand(player, pos);
    }

    int carries() {
        return carries;
    }

    int refused() {
        return refused;
    }

    boolean done() {
        return started && kettle.ledger() == null && kettle.lastResult() != null;
    }

    void tick() {
        if (carrying >= 0) {
            carry();
            return;
        }
        if (!started) {
            kettle.setup().refresh();
            var page = kettle.setup().preview();
            if (page.acceptable()) {
                started = true;
                lift(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, true, page.revision());
            }
            return;
        }
        JobLedger ledger = kettle.ledger();
        if (ledger == null || ledger.state() != JobState.RUNNING) return;
        KettleProcess p = kettle.liveProcess();
        if (p == null) return;
        if (cooldown > 0) cooldown--;
        KettleProcess.Stage stage = p.stage();
        if (stage == KettleProcess.Stage.CHARGING) {
            if (!pourRequested) {
                // The second bottle is carried by hand; the batch action pours the third at the visible pace.
                int second = p.slotOf(1);
                pourRequested = true;
                lift(WorkshopParts.K_RACK + second, WorkshopAction.POUR, 0, false, WorkshopIntent.NO_REVISION);
            } else if (p.count(KettleProcess.Portion.STAGED) > 0 && cooldown == 0) {
                hand.batch(WorkshopParts.K_RACK + p.slotOf(2), WorkshopAction.POUR, -1);
                cooldown = 40;
            }
            return;
        }
        if (stage == KettleProcess.Stage.READY) {
            if (holding) release();
            if (!fillRequested) {
                hand.batch(WorkshopParts.K_SPOUT, WorkshopAction.FILL, -1);
                fillRequested = true;
            }
            return;
        }
        int target = stage == KettleProcess.Stage.RESTING ? 0
                : (int) Math.round((p.setup().idealLow() + p.setup().idealHigh()) / 2 * KettleProcess.BURNER_STEPS);
        if (p.burner() != target && cooldown == 0) {
            hand.act(WorkshopParts.K_BURNER, WorkshopAction.SET_CONTROL, target);
            cooldown = 2;
            return;
        }
        if ((stage == KettleProcess.Stage.HEATING || stage == KettleProcess.Stage.WAITING_DOSE) && p.temperature() >= p.setup().workLow()
                && cooldown == 0) {
            if (holding) {
                release();
                return;
            }
            lift(WorkshopParts.K_CUP + p.doses(), WorkshopAction.DOSE, p.doses(), false, WorkshopIntent.NO_REVISION);
            cooldown = 10;
            return;
        }
        boolean stir = stage == KettleProcess.Stage.REACTING && (p.undissolved() > 0.02 || p.mixTrouble() > 0.15);
        if (stir) {
            if (!holding) {
                holding = hand.act(WorkshopParts.K_PADDLE, WorkshopAction.GRAB, 0).accepted();
                return;
            }
            double speed = KettleTender.comfortable(p.setup().family());
            double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
            angle += speed / radiusPx / 20.0;
            WorkshopResult r = hand.sample(WorkshopParts.K_PADDLE, Math.cos(angle) * radius, Math.sin(angle) * radius, true, Effort.NORMAL,
                    p.setup().family().posture() > 0);
            if (r.kind() == WorkshopResult.Kind.IGNORED) holding = false;
        } else if (holding && stage == KettleProcess.Stage.RESTING) {
            release();
        }
    }

    private void release() {
        hand.act(WorkshopParts.K_PADDLE, WorkshopAction.RELEASE, 0);
        holding = false;
    }

    /** Lifts a bottle or cup (the carry lease, posed where it stands) and starts moving it toward the mouth. */
    private void lift(int part, WorkshopAction then, int arg, boolean accept, long rev) {
        double[] home = home(part);
        fromU = home[0];
        fromV = home[1];
        boolean cup = part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS;
        toU = (WorkshopParts.KETTLE_CX + (cup ? 2.2 : 0)) / 8.0 - 1;
        toV = (WorkshopParts.KETTLE_CZ - (cup ? 1.5 : 3.2)) / 8.0 - 1;
        WorkshopResult r = send(part, WorkshopAction.GRAB, 0, fromU, fromV, WorkshopIntentC2S.ACK, WorkshopIntent.NO_REVISION);
        if (!r.accepted()) {
            refused++;
            if (accept) started = false;
            return;
        }
        carrying = part;
        carryTick = 0;
        commit = then;
        argument = arg;
        setup = accept;
        revision = rev;
    }

    /** One tick of a carry: the pose moves (and is sent) toward the mouth; at the end it is tipped. */
    private void carry() {
        carryTick++;
        double f = Math.min(1, carryTick / (double) CARRY_TICKS);
        f = f * f * (3 - 2 * f);
        double u = fromU + (toU - fromU) * f, v = fromV + (toV - fromV) * f;
        if (carryTick < CARRY_TICKS + 6) {
            hand.send(carrying, WorkshopAction.SAMPLE, 0, u, v, false, Effort.NORMAL, false);
            return;
        }
        int flags = WorkshopIntentC2S.ACK | (setup ? WorkshopIntentC2S.SETUP : 0);
        WorkshopResult r = send(carrying, commit, argument, u, v, flags, revision);
        if (r.accepted()) carries++;
        else {
            refused++;
            if (setup) started = false;
        }
        carrying = -1;
    }

    private WorkshopResult send(int part, WorkshopAction action, int arg, double u, double v, int extraFlags, long rev) {
        int sequence = hand.nextSequence();
        hand.skipTo(sequence);
        WorkshopIntentC2S plain = hand.envelope(sequence, part, action, arg, u, v, false, Effort.NORMAL, false);
        return WorkshopIntents.receive(player, hand.withFlags(plain, plain.flags() | extraFlags, rev));
    }

    /** Where a rack bottle or dose cup stands, as a carry pose (model pixels / 8 - 1). */
    private static double[] home(int part) {
        if (part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + BrewersKettleBlockEntity.RACK_SIZE) {
            return new double[] {WorkshopParts.railX(part - WorkshopParts.K_RACK) / 8.0 - 1, WorkshopParts.RAIL_Z / 8.0 - 1};
        }
        var p = WorkshopParts.get(com.otectus.immersivebrewing.workshop.WorkshopKind.KETTLE, part);
        return p == null ? new double[] {0, 0} : new double[] {p.cx() / 8.0 - 1, p.cz() / 8.0 - 1};
    }
}
