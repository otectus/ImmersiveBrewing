package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * First-use teaching (handoff 10): three small demonstrations, each pointing at the object concerned and removed after
 * its first successful use: take and put down a tool, control the burner, transfer a dose. Then the observable cues
 * guide the first real batch. Progress is a client preference; nothing here touches a batch.
 */
public final class WorkshopTutorial {
    public enum Stage { TOOL_TAKEN, TOOL_PUT_DOWN, BURNER, DOSE }

    /** A prompt and the part it points at. */
    public record Prompt(int part, Component text) {}

    private static boolean taken;

    private static int stage() {
        return ClientConfig.get(ClientConfig.SHOW_TUTORIAL) ? ClientConfig.get(ClientConfig.TUTORIAL_STAGE) : 3;
    }

    private static void advance(int to) {
        if (!ClientConfig.SPEC.isLoaded() || ClientConfig.get(ClientConfig.TUTORIAL_STAGE) >= to) return;
        ClientConfig.TUTORIAL_STAGE.set(to);
        ClientConfig.SPEC.save();
    }

    public static void done(Stage what) {
        int stage = stage();
        switch (what) {
            case TOOL_TAKEN -> {
                if (stage == 0) taken = true;
            }
            case TOOL_PUT_DOWN -> {
                if (stage == 0 && taken) advance(1);
            }
            case BURNER -> {
                if (stage == 1) advance(2);
            }
            case DOSE -> {
                if (stage == 2) advance(3);
            }
        }
    }

    @Nullable
    public static Prompt prompt(WorkshopTargets.View view, ClientWorkshop.Hand hand) {
        int stage = stage();
        if (stage >= 3) return null;
        if (stage == 0) {
            int tool = view.kind() == WorkshopKind.KETTLE ? WorkshopParts.K_PADDLE : view.kind() == WorkshopKind.MORTAR ? WorkshopParts.M_PESTLE : -1;
            if (tool < 0) return null;
            if (hand.tool() == tool) return new Prompt(tool, Component.translatable("tutorial.immersive_brewing.workshop.put_down"));
            boolean usable = view.kind() == WorkshopKind.KETTLE ? view.running() : view.snapshot().getBoolean("preparable") || view.running();
            return usable ? new Prompt(tool, Component.translatable("tutorial.immersive_brewing.workshop.take_tool")) : null;
        }
        if (view.kind() != WorkshopKind.KETTLE || !view.running() || !view.kettle().contains("stage")) return null;
        KettleProcess.Stage s = KettleProcess.Stage.values()[Math.max(0, Math.min(5, view.kettle().getByte("stage")))];
        if (stage == 1 && s != KettleProcess.Stage.CHARGING && s != KettleProcess.Stage.READY) {
            return new Prompt(WorkshopParts.K_BURNER, Component.translatable("tutorial.immersive_brewing.workshop.burner"));
        }
        if (stage == 2 && (s == KettleProcess.Stage.HEATING || s == KettleProcess.Stage.WAITING_DOSE)) {
            int cup = WorkshopParts.K_CUP + view.kettle().getByte("doses");
            return hand.carried() >= 0 ? new Prompt(WorkshopParts.K_VESSEL, Component.translatable("tutorial.immersive_brewing.workshop.tip"))
                    : new Prompt(cup, Component.translatable("tutorial.immersive_brewing.workshop.lift_dose"));
        }
        return null;
    }

    private WorkshopTutorial() {}
}
