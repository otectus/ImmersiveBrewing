package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopKeys;
import com.otectus.immersivebrewing.client.workshop.WorkshopScreen;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Works the apparatus in the real client the way a player does (handoff 21: prove physical loading, tool contact,
 * reaction state and bottling): it reads the synced view through the same target table the working view uses and acts
 * through the client hand, so every action travels as an intent through the real network path and the server's checks.
 * In the keyboard variant it presses the working view's own keys (Tab to the part, Enter, the Work key, the arrows)
 * instead of calling the hand. Never shipped.
 */
final class WorkshopDriver {
    private final boolean keyboard;
    private boolean enabled;
    private double angle;
    private int cooldown;
    private int folding;
    private int presses;
    private boolean pourSent;
    private boolean fillSent;
    private long ticks;
    private int keyPresses;
    private boolean workHeld;

    WorkshopDriver(boolean keyboard) {
        this.keyboard = keyboard;
    }

    void enable() {
        enabled = true;
        pourSent = false;
        fillSent = false;
        folding = 0;
        cooldown = 0;
    }

    void disable() {
        enabled = false;
        if (workHeld) releaseWork();
        ClientWorkshop.putDown();
        ClientWorkshop.dropCarry();
    }

    boolean enabled() {
        return enabled;
    }

    /** Hold the current input pose for a rendering comparison, without releasing the tool. */
    void pausePresentation(boolean pause) {
        enabled = !pause;
    }

    int keyPresses() {
        return keyPresses;
    }

    void tick(Minecraft mc) {
        if (!enabled || !(mc.screen instanceof WorkshopScreen screen) || mc.level == null) return;
        ticks++;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        BlockEntity be = mc.level.getBlockEntity(screen.pos());
        WorkshopKind kind = ClientWorkshop.kind();
        if (be == null || kind == null) return;
        WorkshopTargets.View view = WorkshopTargets.view(be, kind);
        switch (kind) {
            case KETTLE -> kettle(screen, view);
            case MORTAR -> mortar(screen, view);
            case ALEMBIC -> alembic(screen, view);
            default -> { }
        }
    }

    // ---- kettle ----

    private void kettle(WorkshopScreen screen, WorkshopTargets.View v) {
        if (!v.running()) return;
        if (v.operation() == com.otectus.immersivebrewing.batch.Operation.FINISH) {
            CompoundTag f = v.finishing();
            int doses = f.getByte("doses"), steps = f.getByte("steps");
            if (doses < steps) {
                if (ClientWorkshop.carried() < 0) carry(screen, WorkshopParts.K_CUP + doses);
                else activate(screen, WorkshopParts.K_VESSEL, WorkshopAction.DOSE, doses);
                cooldown = 3;
            } else if (!f.getBoolean("closed")) {
                activate(screen, WorkshopParts.K_LID, WorkshopAction.SET_CONTROL, 1);
                cooldown = 5;
            }
            return;
        }
        CompoundTag k = v.kettle();
        if (!k.contains("stage")) return;
        KettleProcess.Stage stage = KettleProcess.Stage.values()[Math.max(0, Math.min(5, k.getByte("stage")))];
        double temp = k.getByte("temp") / 100.0, workLow = k.getByte("workLow") / 100.0;
        int target = (int) Math.round((k.getByte("idealLow") + k.getByte("idealHigh")) / 200.0 * KettleProcess.BURNER_STEPS);
        switch (stage) {
            case CHARGING -> {
                if (!pourSent) {
                    int first = k.getByteArray("slots")[0];
                    batch(screen, WorkshopParts.K_RACK + first, WorkshopAction.POUR, -1);
                    pourSent = true;
                    cooldown = 5;
                }
            }
            case READY -> {
                if (ClientWorkshop.tool() >= 0) putDown(screen);
                else if (!fillSent) {
                    batch(screen, WorkshopParts.K_SPOUT, WorkshopAction.FILL, -1);
                    fillSent = true;
                }
            }
            default -> {
                int burner = k.getByte("burner");
                if (stage == KettleProcess.Stage.RESTING) {
                    if (workHeld) releaseWork();
                    if (ClientWorkshop.tool() >= 0) {
                        putDown(screen);
                        return;
                    }
                    if (burner != 0) setControl(screen, WorkshopParts.K_BURNER, burner, 0);
                    return;
                }
                if (burner != target) {
                    setControl(screen, WorkshopParts.K_BURNER, burner, target);
                    return;
                }
                if ((stage == KettleProcess.Stage.HEATING || stage == KettleProcess.Stage.WAITING_DOSE) && temp >= workLow) {
                    if (ClientWorkshop.tool() >= 0) {
                        putDown(screen);
                        return;
                    }
                    int dose = k.getByte("doses");
                    if (ClientWorkshop.carried() < 0) carry(screen, WorkshopParts.K_CUP + dose);
                    else activate(screen, WorkshopParts.K_VESSEL, WorkshopAction.DOSE, dose);
                    cooldown = 3;
                    return;
                }
                boolean stir = stage == KettleProcess.Stage.REACTING && (k.getByte("undissolved") > 2 || k.getByte("trouble") > 15);
                if (stir) stir(screen, k);
                else if (workHeld) releaseWork();
            }
        }
    }

    private void stir(WorkshopScreen screen, CompoundTag k) {
        if (ClientWorkshop.tool() != WorkshopParts.K_PADDLE) {
            takeTool(screen, WorkshopParts.K_PADDLE);
            cooldown = 2;
            return;
        }
        if (keyboard) {
            if (!workHeld) pressWork(screen);
            return;
        }
        int family = k.getByte("family");
        double speed = family == 1 || family == 3 || family == 5 || family == 8 ? 10 : family == 4 ? 14 : 16;
        double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
        angle += speed / radiusPx / 20.0;
        ClientWorkshop.moveTool(Math.cos(angle) * radius, Math.sin(angle) * radius, true);
    }

    // ---- mortar ----

    private void mortar(WorkshopScreen screen, WorkshopTargets.View v) {
        CompoundTag m = v.mortar();
        if (v.state() == null) {
            // Taking up the pestle and touching the material begins the preparation.
            if (!v.snapshot().getBoolean("preparable")) return;
            if (ClientWorkshop.tool() != WorkshopParts.M_PESTLE) takeTool(screen, WorkshopParts.M_PESTLE);
            else ClientWorkshop.moveTool(0.1, 0.05, true);
            cooldown = 2;
            return;
        }
        if (!v.running() || !m.contains("zones")) return;
        byte[] zones = m.getByteArray("zones");
        int min = 100, max = 0;
        for (byte z : zones) {
            min = Math.min(min, z);
            max = Math.max(max, z);
        }
        int processed = m.getByte("processed"), clump = m.getByte("clump");
        boolean done = m.getBoolean("usable") && processed >= m.getByte("endpointLow") + 3 && max - min <= 10 && clump < 10;
        if (done) {
            if (workHeld) releaseWork();
            if (ClientWorkshop.tool() >= 0) putDown(screen);
            else activate(screen, WorkshopParts.M_CUP, WorkshopAction.FINISH, 0);
            cooldown = 4;
            return;
        }
        boolean uneven = (max - min > 12 && processed > 20) || clump > 10;
        if (uneven && folding == 0 && ClientWorkshop.tool() == WorkshopParts.M_PESTLE) {
            if (workHeld) releaseWork();
            putDown(screen);
            folding = 24;
            return;
        }
        if (folding > 0) {
            if (ClientWorkshop.tool() != WorkshopParts.M_SCRAPER) {
                takeTool(screen, WorkshopParts.M_SCRAPER);
                return;
            }
            if (keyboard) {
                if (!workHeld) pressWork(screen);
            } else {
                angle += 0.2;
                ClientWorkshop.moveTool(Math.cos(angle) * 0.8, Math.sin(angle) * 0.8, true);
            }
            if (--folding == 0) {
                if (workHeld) releaseWork();
                putDown(screen);
            }
            return;
        }
        if (ClientWorkshop.tool() != WorkshopParts.M_PESTLE) {
            takeTool(screen, WorkshopParts.M_PESTLE);
            return;
        }
        double[][] centres = {{0, 0}, {0.7, 0}, {0, 0.7}, {-0.7, 0}, {0, -0.7}};
        double[] c = centres[(int) (ticks / 30) % centres.length];
        if (keyboard) {
            if (ticks % 30 == 0 && workHeld) releaseWork();
            if (ticks % 30 == 1) {
                // Move the pestle to the next zone with the arrows, press it once, then grind there.
                moveWithKeys(screen, c[0], c[1]);
                activate(screen, WorkshopParts.M_BOWL, WorkshopAction.PRESS, 0);
                presses++;
            } else if (!workHeld) pressWork(screen);
            return;
        }
        if (ticks % 30 < 2) {
            ClientWorkshop.moveTool(c[0], c[1], true);
            ClientWorkshop.send(WorkshopParts.M_BOWL, WorkshopAction.PRESS, 0, false);
            presses++;
        } else {
            angle += 1.0;
            ClientWorkshop.moveTool(c[0] + Math.cos(angle) * 0.15, c[1] + Math.sin(angle) * 0.15, true);
        }
    }

    int presses() {
        return presses;
    }

    // ---- alembic ----

    private void alembic(WorkshopScreen screen, WorkshopTargets.View v) {
        if (!v.running()) return;
        CompoundTag a = v.alembic();
        cooldown = 2;
        if (!a.getBoolean("clamped")) {
            activate(screen, WorkshopParts.A_CLAMP, WorkshopAction.SET_CONTROL, 1);
            return;
        }
        if (a.getByte("burner") != 3) {
            setControl(screen, WorkshopParts.A_BURNER, a.getByte("burner"), 3);
            return;
        }
        if (a.getByte("valve") != 2) {
            setControl(screen, WorkshopParts.A_VALVE, a.getByte("valve"), 2);
            return;
        }
        boolean clear = a.getByte("condensate") == AlembicProcess.Condensate.CLEAR.ordinal();
        if (a.getBoolean("receiver") != clear) activate(screen, WorkshopParts.A_DIVERTER, WorkshopAction.SET_CONTROL, clear ? 1 : 0);
        else cooldown = 0;
    }

    // ---- the two ways of acting: the client hand, or the working view's keys ----

    private void takeTool(WorkshopScreen screen, int part) {
        if (keyboard) {
            if (select(screen, part)) press(screen, WorkshopKeys.ACTIVATE);
        } else {
            ClientWorkshop.takeTool(part);
        }
    }

    private void putDown(WorkshopScreen screen) {
        if (keyboard) press(screen, WorkshopKeys.PUT_DOWN);
        else ClientWorkshop.putDown();
    }

    private void carry(WorkshopScreen screen, int part) {
        if (keyboard) {
            if (select(screen, part)) press(screen, WorkshopKeys.ACTIVATE);
        } else {
            ClientWorkshop.carry(part);
        }
    }

    /** Activates a part the way the view would: keyboard selection and Enter, or the same intent the click sends. */
    private void activate(WorkshopScreen screen, int part, WorkshopAction action, int argument) {
        if (keyboard) {
            if (select(screen, part)) press(screen, WorkshopKeys.ACTIVATE);
            return;
        }
        if (action == WorkshopAction.DOSE && ClientWorkshop.carried() >= 0) ClientWorkshop.commitCarry(WorkshopAction.DOSE, argument, false, com.otectus.immersivebrewing.interaction.WorkshopIntent.NO_REVISION);
        else ClientWorkshop.send(part, action, argument, false);
    }

    private void batch(WorkshopScreen screen, int part, WorkshopAction action, int argument) {
        if (keyboard) {
            if (!select(screen, part)) return;
            press(screen, WorkshopKeys.BATCH);
            press(screen, WorkshopKeys.ACTIVATE);
            return;
        }
        ClientWorkshop.send(part, action, argument, true);
    }

    private void setControl(WorkshopScreen screen, int part, int current, int target) {
        if (keyboard) {
            if (!select(screen, part)) return;
            press(screen, target > current ? WorkshopKeys.INCREASE : WorkshopKeys.DECREASE);
            return;
        }
        ClientWorkshop.send(part, WorkshopAction.SET_CONTROL, target, false);
    }

    /** Tabs through the parts until {@code part} is selected (bounded). */
    private boolean select(WorkshopScreen screen, int part) {
        for (int i = 0; i < 48; i++) {
            if (screen.target() != null && screen.target().part().id() == part && screen.keyboardSelection()) return true;
            press(screen, WorkshopKeys.NEXT_TARGET);
        }
        return screen.target() != null && screen.target().part().id() == part;
    }

    private void press(WorkshopScreen screen, KeyMapping key) {
        int code = key.getKey().getValue();
        screen.keyPressed(code, 0, 0);
        screen.keyReleased(code, 0, 0);
        keyPresses++;
    }

    private Boolean leftRaisesU;
    private Boolean forwardRaisesV;

    /** Moves the held tool toward a work-plane point with the arrow keys only (bounded presses). */
    private void moveWithKeys(WorkshopScreen screen, double u, double v) {
        for (int i = 0; i < 32; i++) {
            double du = u - ClientWorkshop.toolU(), dv = v - ClientWorkshop.toolV();
            if (Math.abs(du) <= 0.08 && Math.abs(dv) <= 0.08) return;
            if (Math.abs(du) > 0.08) {
                if (leftRaisesU == null) {
                    // Learn once which way the left key moves the tool on this facing and presentation.
                    double before = ClientWorkshop.toolU();
                    press(screen, WorkshopKeys.TOOL_LEFT);
                    if (ClientWorkshop.toolU() != before) leftRaisesU = ClientWorkshop.toolU() > before;
                    continue;
                }
                press(screen, (du > 0) == leftRaisesU ? WorkshopKeys.TOOL_LEFT : WorkshopKeys.TOOL_RIGHT);
            } else {
                if (forwardRaisesV == null) {
                    double before = ClientWorkshop.toolV();
                    press(screen, WorkshopKeys.TOOL_FORWARD);
                    if (ClientWorkshop.toolV() != before) forwardRaisesV = ClientWorkshop.toolV() > before;
                    continue;
                }
                press(screen, (dv > 0) == forwardRaisesV ? WorkshopKeys.TOOL_FORWARD : WorkshopKeys.TOOL_BACK);
            }
        }
    }

    private void pressWork(WorkshopScreen screen) {
        screen.keyPressed(WorkshopKeys.WORK.getKey().getValue(), 0, 0);
        workHeld = true;
        keyPresses++;
    }

    private void releaseWork() {
        if (Minecraft.getInstance().screen instanceof WorkshopScreen screen) screen.keyReleased(WorkshopKeys.WORK.getKey().getValue(), 0, 0);
        workHeld = false;
    }
}
