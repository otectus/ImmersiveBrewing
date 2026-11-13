package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;

/**
 * Reference Alembic players acting only through intents: {@link #competent} clamps the still, sets a steady burner and a
 * matched coolant flow and moves the diverter to the receiver exactly while the stream is clear; {@link #sloppy} runs it
 * hot and collects everything. Never shipped.
 */
public final class AlembicTender {
    private final boolean competent;
    private int cooldown;

    private AlembicTender(boolean competent) {
        this.competent = competent;
    }

    public static AlembicTender competent() {
        return new AlembicTender(true);
    }

    public static AlembicTender sloppy() {
        return new AlembicTender(false);
    }

    public void tick(AlembicBlockEntity alembic, WorkshopHand hand) {
        var ledger = alembic.ledger();
        if (ledger == null) return;
        if (ledger.state() != JobState.RUNNING || !ledger.physical()) return;
        AlembicProcess p = alembic.liveProcess();
        if (p == null) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = 1;
        if (!p.clamped()) hand.act(WorkshopParts.A_CLAMP, WorkshopAction.SET_CONTROL, 1);
        else if (p.burner() != (competent ? 3 : 4)) hand.act(WorkshopParts.A_BURNER, WorkshopAction.SET_CONTROL, competent ? 3 : 4);
        else if (p.valve() != 2) hand.act(WorkshopParts.A_VALVE, WorkshopAction.SET_CONTROL, 2);
        else {
            boolean want = !competent || p.condensate() == AlembicProcess.Condensate.CLEAR;
            if (p.toReceiver() != want) hand.act(WorkshopParts.A_DIVERTER, WorkshopAction.SET_CONTROL, want ? 1 : 0);
            else cooldown = 0;
        }
    }
}
