package com.otectus.immersivebrewing.minigame.brewing;

import com.otectus.immersivebrewing.minigame.SimData;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete, immutable parameter set of one kettle session: phases, additions, the seeded disturbance schedule and
 * the physical response constants. Resolved on the server before the count-in (including any approved assistance),
 * sent to the client, and stored with the job so a sealed brew resumes under exactly these values.
 */
public record KettleParams(List<PhaseSpec> phases, List<AdditionSpec> additions, List<Disturbance> disturbances,
                           double burnerResponse, double heatResponse, double stirResponse, double coastResponse,
                           double heatFalloff, double circFalloff, double initialHeat, double initialBurner,
                           int initialDraft, int warmupSteps, int tier) {
    public static final int VERSION = 1;
    /** Draft targets for Low / Medium / High (spec 8.1). */
    public static final double[] DRAFT_TARGETS = {0.30, 0.55, 0.82};
    public static final int MAX_PHASES = 3;
    public static final int MAX_ADDITIONS = 8;
    public static final int MAX_DISTURBANCES = 6;
    /** Hard safety limit on any productive session, assisted or not (spec 23.1): 90 s. */
    public static final int SAFETY_LIMIT_STEPS = 90 * 20;

    public KettleParams {
        phases = List.copyOf(phases);
        additions = List.copyOf(additions);
        disturbances = List.copyOf(disturbances);
        if (phases.isEmpty() || phases.size() > MAX_PHASES) throw new IllegalArgumentException("phases");
        if (additions.size() > MAX_ADDITIONS || disturbances.size() > MAX_DISTURBANCES) throw new IllegalArgumentException("too many events");
        int expected = 0;
        for (PhaseSpec p : phases) {
            if (p.start() != expected) throw new IllegalArgumentException("phases must be contiguous");
            expected = p.end();
        }
        if (expected > SAFETY_LIMIT_STEPS) throw new IllegalArgumentException("session longer than the safety limit");
        for (int i = 0; i < additions.size(); i++) {
            AdditionSpec a = additions.get(i);
            if (a.index() != i) throw new IllegalArgumentException("addition order");
            if (a.closeStep() > expected) throw new IllegalArgumentException("addition window past the end");
        }
        for (double v : new double[] {burnerResponse, heatResponse, stirResponse, coastResponse}) {
            if (!(v > 0 && v <= 20)) throw new IllegalArgumentException("response constant " + v);
        }
        if (!(heatFalloff > 0 && heatFalloff <= 1 && circFalloff > 0 && circFalloff <= 1)) throw new IllegalArgumentException("falloff");
        if (!(initialHeat >= 0 && initialHeat <= 1 && initialBurner >= 0 && initialBurner <= 1)) throw new IllegalArgumentException("initial heat");
        if (initialDraft < 0 || initialDraft > 2) throw new IllegalArgumentException("initial draft");
    }

    public int totalSteps() {
        return phases.get(phases.size() - 1).end();
    }

    public PhaseSpec phaseAt(int step) {
        for (PhaseSpec p : phases) if (p.contains(step)) return p;
        return phases.get(phases.size() - 1);
    }

    public int phaseIndex(int step) {
        for (int i = 0; i < phases.size(); i++) if (phases.get(i).contains(step)) return i;
        return phases.size() - 1;
    }

    public long[] encode() {
        SimData.Writer w = new SimData.Writer().putInt(VERSION).putInt(tier);
        w.putInt(phases.size());
        for (PhaseSpec p : phases) {
            w.putInt(p.phase().ordinal()).putInt(p.start()).putInt(p.length()).putDouble(p.heatLow()).putDouble(p.heatHigh())
                    .putDouble(p.circLow()).putDouble(p.circHigh()).putInt(p.signature().ordinal()).putDouble(p.initialTrouble())
                    .putInt(p.reverseStep());
        }
        w.putInt(additions.size());
        for (AdditionSpec a : additions) {
            w.putInt(a.index()).putInt(a.phase().ordinal()).putInt(a.cueStep()).putInt(a.openStep()).putInt(a.closeStep())
                    .putDouble(a.cooling()).putDouble(a.kick());
            putString(w, a.item());
        }
        w.putInt(disturbances.size());
        for (Disturbance d : disturbances) {
            w.putInt(d.kind().ordinal()).putInt(d.warnStep()).putInt(d.step()).putDouble(d.magnitude());
        }
        return w.putDouble(burnerResponse).putDouble(heatResponse).putDouble(stirResponse).putDouble(coastResponse)
                .putDouble(heatFalloff).putDouble(circFalloff).putDouble(initialHeat).putDouble(initialBurner)
                .putInt(initialDraft).putInt(warmupSteps).toArray();
    }

    public static KettleParams decode(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, VERSION);
        if (version != VERSION) throw new IllegalArgumentException("kettle params version " + version);
        int tier = r.getInt(0, 3);
        int phaseCount = r.getInt(1, MAX_PHASES);
        List<PhaseSpec> phases = new ArrayList<>(phaseCount);
        for (int i = 0; i < phaseCount; i++) {
            phases.add(new PhaseSpec(KettlePhase.values()[r.getInt(0, 2)], r.getInt(0, SAFETY_LIMIT_STEPS), r.getInt(1, SAFETY_LIMIT_STEPS),
                    r.getDouble(0, 1), r.getDouble(0, 1), r.getDouble(0, 1), r.getDouble(0, 1),
                    Signature.values()[r.getInt(0, Signature.values().length - 1)], r.getDouble(0, 1), r.getInt(-1, SAFETY_LIMIT_STEPS)));
        }
        int additionCount = r.getInt(0, MAX_ADDITIONS);
        List<AdditionSpec> additions = new ArrayList<>(additionCount);
        for (int i = 0; i < additionCount; i++) {
            additions.add(new AdditionSpec(r.getInt(0, MAX_ADDITIONS - 1), KettlePhase.values()[r.getInt(0, 2)], r.getInt(0, SAFETY_LIMIT_STEPS),
                    r.getInt(0, SAFETY_LIMIT_STEPS), r.getInt(0, SAFETY_LIMIT_STEPS), r.getDouble(0, 0.5), r.getDouble(0, 1), getString(r)));
        }
        int disturbanceCount = r.getInt(0, MAX_DISTURBANCES);
        List<Disturbance> disturbances = new ArrayList<>(disturbanceCount);
        for (int i = 0; i < disturbanceCount; i++) {
            disturbances.add(new Disturbance(Disturbance.Kind.values()[r.getInt(0, Disturbance.Kind.values().length - 1)],
                    r.getInt(0, SAFETY_LIMIT_STEPS), r.getInt(0, SAFETY_LIMIT_STEPS), r.getDouble(0, 1)));
        }
        return new KettleParams(phases, additions, disturbances, r.getDouble(), r.getDouble(), r.getDouble(), r.getDouble(),
                r.getDouble(), r.getDouble(), r.getDouble(0, 1), r.getDouble(0, 1), r.getInt(0, 2), r.getInt(0, SAFETY_LIMIT_STEPS), tier);
    }

    /** Short ASCII strings (registry ids) packed eight characters per long. */
    static void putString(SimData.Writer w, String s) {
        String v = s == null ? "" : s;
        if (v.length() > 128) v = v.substring(0, 128);
        w.putInt(v.length());
        long acc = 0;
        int n = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            acc |= ((long) (c < 128 ? c : '?')) << (8 * n);
            if (++n == 8) {
                w.putLong(acc);
                acc = 0;
                n = 0;
            }
        }
        if (n > 0) w.putLong(acc);
    }

    static String getString(SimData.Reader r) {
        int length = r.getInt(0, 128);
        StringBuilder sb = new StringBuilder(length);
        long acc = 0;
        for (int i = 0; i < length; i++) {
            if (i % 8 == 0) acc = r.getLong();
            sb.append((char) ((acc >>> (8 * (i % 8))) & 0x7F));
        }
        return sb.toString();
    }
}
