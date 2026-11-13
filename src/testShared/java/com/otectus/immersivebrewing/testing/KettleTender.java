package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;

import java.util.Random;

/**
 * Reference kettle players for the hands-on workshop, acting only through intents (never touching the process):
 * {@link #competent} pours the batch, warms to the middle of the band, doses once the liquid is warm, stirs at the
 * reaction's comfortable pace while there is something to incorporate, eases off to rest and fills every bottle.
 * {@link #rough} runs the burner hot, doses at once and thrashes the paddle. {@link #idle} never acts. Never shipped.
 */
public final class KettleTender {
    public enum Style { COMPETENT, ROUGH, IDLE }

    private final Style style;
    private final Random random;
    private double angle;
    private boolean pourRequested;
    private boolean fillRequested;
    private boolean holding;
    private int cooldown;

    public KettleTender(Style style, long seed) {
        this.style = style;
        this.random = new Random(seed);
    }

    public static KettleTender competent() {
        return new KettleTender(Style.COMPETENT, 1);
    }

    public static KettleTender rough(long seed) {
        return new KettleTender(Style.ROUGH, seed);
    }

    public static KettleTender idle() {
        return new KettleTender(Style.IDLE, 0);
    }

    public Style style() {
        return style;
    }

    /** One server tick of tending. At most two intents, inside the coordinated budget. */
    public void tick(BrewersKettleBlockEntity kettle, WorkshopHand hand) {
        if (style == Style.IDLE) return;
        JobLedger ledger = kettle.ledger();
        if (ledger == null || !ledger.physical()) return;
        // A sealed batch waits for a player's decision to reopen it; the tender only works a running one.
        if (ledger.state() != JobState.RUNNING) {
            holding = false;
            return;
        }
        if (ledger.operation() == Operation.FINISH) {
            finishing(kettle, hand);
            return;
        }
        KettleProcess p = kettle.liveProcess();
        if (p == null) return;
        if (cooldown > 0) cooldown--;
        KettleProcess.Stage stage = p.stage();
        if (stage == KettleProcess.Stage.CHARGING) {
            if (!pourRequested) {
                hand.batch(WorkshopParts.K_RACK + p.slotOf(0), WorkshopAction.POUR, -1);
                pourRequested = true;
            }
            return;
        }
        if (stage == KettleProcess.Stage.READY) {
            if (holding) release(hand);
            if (!fillRequested) {
                hand.batch(WorkshopParts.K_SPOUT, WorkshopAction.FILL, -1);
                fillRequested = true;
            }
            return;
        }
        int target = stage == KettleProcess.Stage.RESTING ? 0 : style == Style.ROUGH ? 7
                : (int) Math.round((p.setup().idealLow() + p.setup().idealHigh()) / 2 * KettleProcess.BURNER_STEPS);
        if (p.burner() != target && cooldown == 0) {
            hand.act(WorkshopParts.K_BURNER, WorkshopAction.SET_CONTROL, target);
            cooldown = 2;
        } else if ((stage == KettleProcess.Stage.HEATING || stage == KettleProcess.Stage.WAITING_DOSE)
                && (style == Style.ROUGH || p.temperature() >= p.setup().workLow())) {
            hand.act(WorkshopParts.K_CUP + p.doses(), WorkshopAction.DOSE, p.doses());
        }
        boolean stir = stage == KettleProcess.Stage.REACTING && (style == Style.ROUGH || p.undissolved() > 0.02 || p.mixTrouble() > 0.15);
        if (stir) {
            if (!holding) {
                holding = hand.act(WorkshopParts.K_PADDLE, WorkshopAction.GRAB, 0).accepted();
                return;
            }
            double speed = style == Style.ROUGH ? 32 + random.nextDouble() * 8 : comfortable(p.setup().family());
            double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
            angle += speed / radiusPx / 20.0;
            WorkshopResult r = hand.sample(WorkshopParts.K_PADDLE, Math.cos(angle) * radius, Math.sin(angle) * radius, true, Effort.NORMAL);
            if (r.kind() == WorkshopResult.Kind.IGNORED && style == Style.COMPETENT) holding = false;
        } else if (holding && stage == KettleProcess.Stage.RESTING) {
            release(hand);
        }
    }

    private void release(WorkshopHand hand) {
        hand.act(WorkshopParts.K_PADDLE, WorkshopAction.RELEASE, 0);
        holding = false;
    }

    private void finishing(BrewersKettleBlockEntity kettle, WorkshopHand hand) {
        var snapshot = kettle.buildWorkshopSnapshot().getCompound("finishing");
        int doses = snapshot.getByte("doses"), steps = snapshot.getByte("steps");
        if (doses < steps) hand.act(WorkshopParts.K_CUP + doses, WorkshopAction.DOSE, doses);
        else if (!snapshot.getBoolean("closed")) hand.act(WorkshopParts.K_LID, WorkshopAction.SET_CONTROL, 1);
    }

    /** The stirring pace each reaction rewards (the same table the domain tests use). */
    public static double comfortable(ReactionFamily f) {
        return switch (f) {
            case LIGHT_FOAM, DELICATE_SHIMMER, SUSPENDED_BUBBLES, FLOATING_MOTES -> 10;
            case MAGMA_STREAKS -> 14;
            default -> 16;
        };
    }
}
