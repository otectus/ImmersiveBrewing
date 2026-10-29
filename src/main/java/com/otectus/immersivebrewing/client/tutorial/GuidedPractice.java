package com.otectus.immersivebrewing.client.tutorial;

import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.minigame.brewing.AdditionSpec;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.PhaseSpec;
import com.otectus.immersivebrewing.minigame.brewing.Signature;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Four short, replayable kettle lessons. They are client-only practice sessions: no packet, inventory transaction,
 * recipe output or advancement can result. Each lesson removes unrelated scoring demands and the recovery lesson starts
 * deliberately above its band so returning to the band is the task rather than a perfect untouched run.
 */
public final class GuidedPractice {
    public enum Stage { HEAT, CIRCULATION, ADDITION, RECOVERY }

    public static KettleParams params(Stage stage) {
        int length = stage == Stage.ADDITION ? 240 : 200;
        double heatLow = switch (stage) {
            case HEAT, RECOVERY -> 0.42;
            default -> 0.0;
        };
        double heatHigh = switch (stage) {
            case HEAT, RECOVERY -> 0.58;
            default -> 1.0;
        };
        double circLow = stage == Stage.CIRCULATION ? 0.24 : 0.0;
        double circHigh = stage == Stage.CIRCULATION ? 0.55 : 1.0;
        PhaseSpec phase = new PhaseSpec(KettlePhase.INFUSE, 0, length, heatLow, heatHigh, circLow, circHigh,
                Signature.NONE, 0, -1);
        List<AdditionSpec> additions = stage == Stage.ADDITION
                ? List.of(new AdditionSpec(0, KettlePhase.INFUSE, 70, 95, 145, 0.04, 0.08, "minecraft:sugar"))
                : List.of();
        double initialHeat = stage == Stage.RECOVERY ? 0.82 : stage == Stage.HEAT ? 0.20 : 0.50;
        double initialBurner = stage == Stage.RECOVERY ? 0.82 : stage == Stage.HEAT ? 0.30 : 0.50;
        int draft = stage == Stage.RECOVERY ? 2 : stage == Stage.HEAT ? 0 : 1;
        return new KettleParams(List.of(phase), additions, List.of(), 1.8, 1.0, 2.8, 0.8,
                0.12, 0.22, initialHeat, initialBurner, draft, 0, 0);
    }

    public static String id(Stage stage) {
        return stage.name().toLowerCase(Locale.ROOT);
    }

    public static Stage stage(ClientSession session) {
        if (!session.practice || !session.display.getBoolean("guided")) return null;
        try {
            return Stage.valueOf(session.display.getString("guidedStage").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Short live instruction for GameScreen to draw beside the relevant control. */
    public static Component cue(ClientSession session) {
        Stage stage = stage(session);
        if (stage == null || !(session.sim() instanceof SimHandles.Brewing brewing)) return Component.empty();
        var sim = brewing.sim();
        String suffix = switch (stage) {
            case HEAT -> sim.heat() < sim.phase().heatLow() ? "raise" : sim.heat() > sim.phase().heatHigh() ? "lower" : "hold";
            case CIRCULATION -> Math.abs(sim.circulation()) < sim.phase().circLow() ? "stir" : "coast";
            case ADDITION -> sim.nextAddition() == 0 && sim.step() >= sim.params().additions().get(0).openStep()
                    && sim.step() <= sim.params().additions().get(0).closeStep() ? "add_now" : "wait_for_cue";
            case RECOVERY -> sim.heat() > sim.phase().heatHigh() ? "cut_heat" : sim.heat() < sim.phase().heatLow() ? "raise_gently" : "recovered";
        };
        return Component.translatable("tutorial.immersive_brewing." + id(stage) + "." + suffix);
    }

    /** Integration helper for KettleBrewScreen: ignore inputs unrelated to the current one-concept lesson. */
    public static boolean allows(Stage stage, KettleInput input) {
        return switch (stage) {
            case HEAT, RECOVERY -> input.stir() == 0 && !input.add();
            case CIRCULATION -> input.draft() == 1 && !input.add();
            case ADDITION -> input.draft() == 1 && input.stir() == 0;
        };
    }

    /** Retains the lesson's relevant control and neutralises the others, so an accidental input cannot stall teaching. */
    public static KettleInput filter(Stage stage, KettleInput input, int currentDraft) {
        return switch (stage) {
            case HEAT, RECOVERY -> new KettleInput(input.draft(), 0, false);
            case CIRCULATION -> new KettleInput(currentDraft, input.stir(), false);
            case ADDITION -> new KettleInput(currentDraft, 0, input.add());
        };
    }

    private GuidedPractice() {}
}
