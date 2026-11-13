package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;

/**
 * Reference mortar players acting only through intents: {@link #competent} works each zone of the bowl in turn with
 * presses and short grinding arcs, folds with the scraper whenever the material is uneven, and scrapes the result into
 * the cup once it is even and inside the endpoint. {@link #rough} grinds firmly in one spot and packages late.
 * Never shipped.
 */
public final class MortarTender {
    public enum Style { COMPETENT, ROUGH }

    private static final double[][] ZONES = {{0, 0}, {0.7, 0}, {0, 0.7}, {-0.7, 0}, {0, -0.7}};
    private final Style style;
    private int tick;
    private int tool = -1;
    private int folding;
    private double angle;

    private MortarTender(Style style) {
        this.style = style;
    }

    public static MortarTender competent() {
        return new MortarTender(Style.COMPETENT);
    }

    public static MortarTender rough() {
        return new MortarTender(Style.ROUGH);
    }

    public void tick(ApothecaryMortarBlockEntity mortar, WorkshopHand hand) {
        tick++;
        var ledger = mortar.ledger();
        if (ledger != null && ledger.state() != JobState.RUNNING) {
            tool = -1;
            return;
        }
        MortarProcess p = mortar.liveProcess();
        if (ledger == null) {
            if (mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT).isEmpty()) return;
            if (tool != WorkshopParts.M_PESTLE) {
                if (hand.act(WorkshopParts.M_PESTLE, WorkshopAction.GRAB, 0).accepted()) tool = WorkshopParts.M_PESTLE;
                return;
            }
            hand.sample(WorkshopParts.M_PESTLE, 0, 0, true, Effort.NORMAL); // the first contact begins the preparation
            return;
        }
        if (p == null) return;
        MortarProcess.Evaluation e = p.evaluate();
        boolean done = style == Style.COMPETENT
                ? e.processed() >= p.setup().endpointLow() + 0.03 && e.spread() <= 0.1 && e.clump() < 0.1
                : e.processed() >= p.setup().endpointHigh() + 0.05;
        if (done && e.usable()) {
            if (tool >= 0) {
                hand.act(tool, WorkshopAction.RELEASE, 0);
                tool = -1;
                return;
            }
            hand.act(WorkshopParts.M_CUP, WorkshopAction.FINISH, 0);
            return;
        }
        boolean uneven = style == Style.COMPETENT && (e.spread() > 0.12 && e.processed() > 0.2 || e.clump() > 0.1);
        if (uneven && folding == 0 && tool == WorkshopParts.M_PESTLE) {
            hand.act(WorkshopParts.M_PESTLE, WorkshopAction.RELEASE, 0);
            tool = -1;
            folding = 24;
            return;
        }
        if (folding > 0) {
            if (tool != WorkshopParts.M_SCRAPER) {
                if (hand.act(WorkshopParts.M_SCRAPER, WorkshopAction.GRAB, 0).accepted()) tool = WorkshopParts.M_SCRAPER;
                return;
            }
            angle += 0.2;
            if (!hand.sample(WorkshopParts.M_SCRAPER, Math.cos(angle) * 0.8, Math.sin(angle) * 0.8, true, Effort.GENTLE).accepted()) {
                tool = -1; // the lease lapsed or the station changed: take the tool up again
                return;
            }
            if (--folding == 0) {
                hand.act(WorkshopParts.M_SCRAPER, WorkshopAction.RELEASE, 0);
                tool = -1;
            }
            return;
        }
        if (tool != WorkshopParts.M_PESTLE) {
            if (hand.act(WorkshopParts.M_PESTLE, WorkshopAction.GRAB, 0).accepted()) tool = WorkshopParts.M_PESTLE;
            return;
        }
        Effort effort = style == Style.ROUGH ? Effort.FIRM : Effort.NORMAL;
        double[] z = style == Style.ROUGH ? ZONES[0] : ZONES[(tick / 30) % ZONES.length];
        boolean held;
        if (tick % 30 < 2) {
            held = hand.sample(WorkshopParts.M_PESTLE, z[0], z[1], true, effort).accepted();
            hand.press(z[0], z[1], effort);
        } else {
            angle += 1.0;
            held = hand.sample(WorkshopParts.M_PESTLE, z[0] + Math.cos(angle) * 0.15, z[1] + Math.sin(angle) * 0.15, true, effort).accepted();
        }
        if (!held) tool = -1; // the lease lapsed or the station changed: take the pestle up again
    }
}
