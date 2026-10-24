package com.otectus.immersivebrewing.minigame.brewing;

import com.otectus.immersivebrewing.minigame.SimRandom;
import com.otectus.immersivebrewing.minigame.StepClock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Seeded disturbance placement (spec 7). Every disturbance is warned 2.5 s ahead, lands in Infuse or Settle, keeps
 * clear of addition cues and windows (so at most two control concerns are ever active) and of other disturbances,
 * and only appears where it fits: a schedule never makes a high score impossible.
 */
public final class DisturbanceSchedule {
    /**
     * Clearance after an addition window: the disturbance's own warning must start after the window closed, so a warning
     * and an addition are never live at once.
     */
    private static final int ADDITION_CLEARANCE_AFTER = Disturbance.LEAD + StepClock.seconds(0.5);
    private static final int MIN_GAP = StepClock.seconds(5);
    private static final int EDGE = StepClock.seconds(2);

    public static List<Disturbance> generate(SimRandom random, int tier, List<PhaseSpec> phases, List<AdditionSpec> additions,
                                             List<Disturbance.Kind> allowed, int planSteps) {
        int count = switch (tier) {
            case KettleSetup.GENTLE -> Math.max(0, Math.min(1, 3 - additions.size()));
            case KettleSetup.PRACTICED -> 2;
            default -> Math.max(1, Math.min(3, 5 - additions.size()));
        };
        if (phases.size() == 1) count = Math.min(count, 1); // modifier-only sessions are short
        List<Disturbance.Kind> kinds = chooseKinds(random, tier, count, allowed);
        List<Disturbance> out = new ArrayList<>();
        for (Disturbance.Kind kind : kinds) {
            List<int[]> slots = freeSlots(phases, additions, out);
            int total = 0;
            for (int[] s : slots) total += s[1] - s[0];
            if (total <= 0) break;
            int pick = random.nextInt(total);
            int step = -1;
            for (int[] s : slots) {
                int len = s[1] - s[0];
                if (pick < len) {
                    step = s[0] + pick;
                    break;
                }
                pick -= len;
            }
            if (step < 0) break;
            double magnitude = switch (kind) {
                case COLD_DRAFT -> random.range(0.10, 0.14);
                case BURNER_FLARE -> random.range(0.18, 0.24);
                case SURGE -> random.range(0.25, 0.35);
            };
            out.add(new Disturbance(kind, step - Disturbance.LEAD, step, magnitude));
        }
        out.sort(Comparator.comparingInt(Disturbance::step));
        return out;
    }

    private static List<Disturbance.Kind> chooseKinds(SimRandom random, int tier, int count, List<Disturbance.Kind> allowed) {
        List<Disturbance.Kind> pool = allowed == null || allowed.isEmpty() ? List.of(Disturbance.Kind.values()) : allowed;
        List<Disturbance.Kind> thermal = pool.stream().filter(k -> k != Disturbance.Kind.SURGE).toList();
        List<Disturbance.Kind> out = new ArrayList<>();
        if (tier == KettleSetup.PRACTICED && count >= 2) {
            // One thermal disturbance and one circulation change (spec 8.3).
            out.add(thermal.isEmpty() ? Disturbance.Kind.COLD_DRAFT : thermal.get(random.nextInt(thermal.size())));
            out.add(Disturbance.Kind.SURGE);
            return out;
        }
        for (int i = 0; i < count; i++) out.add(pool.get(random.nextInt(pool.size())));
        return out;
    }

    /** Half-open step intervals where a disturbance may land. */
    private static List<int[]> freeSlots(List<PhaseSpec> phases, List<AdditionSpec> additions, List<Disturbance> placed) {
        List<int[]> out = new ArrayList<>();
        for (PhaseSpec p : phases) {
            if (p.phase() == KettlePhase.BLOOM) continue;
            int lo = Math.max(p.start() + EDGE, Disturbance.LEAD + EDGE);
            int hi = p.end() - EDGE - Disturbance.COLD_SPREAD;
            if (hi <= lo) continue;
            List<int[]> blocked = new ArrayList<>();
            for (AdditionSpec a : additions) {
                blocked.add(new int[] {a.cueStep() - Disturbance.LEAD, a.closeStep() + ADDITION_CLEARANCE_AFTER});
            }
            for (Disturbance d : placed) blocked.add(new int[] {d.step() - MIN_GAP, d.step() + MIN_GAP});
            if (p.reverseStep() >= 0) blocked.add(new int[] {p.reverseStep() - Disturbance.LEAD, p.reverseStep() + MIN_GAP});
            blocked.sort(Comparator.comparingInt(b -> b[0]));
            int cursor = lo;
            for (int[] b : blocked) {
                if (b[1] <= cursor) continue;
                if (b[0] >= hi) break;
                if (b[0] > cursor) out.add(new int[] {cursor, Math.min(b[0], hi)});
                cursor = Math.max(cursor, b[1]);
            }
            if (cursor < hi) out.add(new int[] {cursor, hi});
        }
        return out;
    }

    private DisturbanceSchedule() {}
}
