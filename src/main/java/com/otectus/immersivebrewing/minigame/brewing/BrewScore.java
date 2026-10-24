package com.otectus.immersivebrewing.minigame.brewing;

import java.util.ArrayList;
import java.util.List;

/**
 * The resolved kettle session: control {@code C} and the three terms behind it, with per-phase and per-addition detail
 * so the result screen and the Notebook can explain the score and give one concrete tip (spec 10, 11.4).
 */
public record BrewScore(int control, double thermal, double circulation, double dosing, boolean participated,
                        KettlePhase[] phases, Signature[] signatures, double[] phaseThermal, double[] phaseCirculation,
                        double[] phaseHeatBias, boolean[] phaseCounted, KettleSimulation.Outcome[] outcomes,
                        double[] additionCredits, String[] additionItems) {

    /** A tip: a translation key plus the arguments it formats (phase key, item id). */
    public record Tip(String key, List<String> args) {}

    public Tip tip() {
        if (!participated) return new Tip("tip.immersive_brewing.brew.idle", List.of());
        for (int i = 0; i < phases.length; i++) {
            if (!phaseCounted[i]) return new Tip("tip.immersive_brewing.brew.unattended", List.of(phaseKey(i)));
        }
        double t = thermal;
        double c = circulation;
        double d = dosing;
        if (d < t && d < c && d < 0.9) {
            for (int i = 0; i < outcomes.length; i++) {
                KettleSimulation.Outcome o = outcomes[i];
                if (o == KettleSimulation.Outcome.AUTOMATIC) return new Tip("tip.immersive_brewing.brew.missed", List.of(additionItems[i]));
                if (o == KettleSimulation.Outcome.EARLY) return new Tip("tip.immersive_brewing.brew.early", List.of(additionItems[i]));
                if (o == KettleSimulation.Outcome.LATE) return new Tip("tip.immersive_brewing.brew.late", List.of(additionItems[i]));
            }
        }
        if (t <= c && t < 0.9) {
            int worst = worst(phaseThermal);
            String dir = phaseHeatBias[worst] >= 0 ? "hot" : "cold";
            return new Tip("tip.immersive_brewing.brew.heat_" + dir, List.of(phaseKey(worst)));
        }
        if (c < 0.9) {
            int worst = worst(phaseCirculation);
            return new Tip("tip.immersive_brewing.brew.signature." + signatures[worst].serialized(), List.of(phaseKey(worst)));
        }
        return new Tip("tip.immersive_brewing.brew.excellent", List.of());
    }

    public List<String> phaseSummaryKeys() {
        List<String> out = new ArrayList<>();
        for (KettlePhase p : phases) out.add("phase.immersive_brewing." + p.serialized());
        return out;
    }

    private String phaseKey(int index) {
        return "phase.immersive_brewing." + phases[index].serialized();
    }

    private static int worst(double[] values) {
        int worst = 0;
        for (int i = 1; i < values.length; i++) if (values[i] < values[worst]) worst = i;
        return worst;
    }
}
