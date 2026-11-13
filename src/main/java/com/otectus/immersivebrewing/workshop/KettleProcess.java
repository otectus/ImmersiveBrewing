package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.minigame.Credit;
import com.otectus.immersivebrewing.potion.QualityMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The hands-on kettle (handoff 7, 8, 11, 14): one deterministic, bounded, condition-driven process per batch, advanced
 * exactly once per server step by its block entity. There are no phase clocks, scheduled disturbances or participation
 * counts: progress comes only from the actual liquid state.
 *
 * <p>Physical state: a burner control (0..{@link #BURNER_STEPS}, 0 = off) drives a flame that follows promptly; the
 * liquid temperature follows the flame with thermal inertia that grows with the charged volume; circulation follows
 * accepted paddle displacement and coasts down when the paddle stops. A dose sets the undissolved fraction, which
 * paddle work under workable heat removes; the reaction step progresses at a rate set by temperature and dissolution.
 * The family's two trouble quantities (agitation and heat) make the material legible.
 *
 * <p>Workmanship {@code C = control(thermal, incorporation, dosing)} with the existing weights. Thermal and
 * incorporation are sampled per unit of reaction progress, never per idle tick, so waiting, reading or holding a
 * finished batch changes nothing, and extra stirring after a step completes can not raise the score. Dosing credits the
 * temperature at each addition.
 *
 * <p>Ownership is not here: portions are positions in the owning ledger's {@code inputs} list (same order), so the
 * process never holds a second copy of a potion.
 */
public final class KettleProcess {
    public static final int VERSION = 1;
    public static final int BURNER_STEPS = 8;
    public static final int MAX_SERVINGS = 6;
    public static final int MAX_STEPS = 8;
    public static final int MAX_PARTICIPANTS = 5;
    public static final double DT = 0.05;
    /** Paddle speed, in pixels per second, that brings circulation to 1. */
    public static final double REF_SPEED_PX = 30.0;
    /** Circulation at or below which the surface counts as calm for resting. */
    public static final double CALM = 0.12;
    /** Total trouble at or below which the surface counts as settled for resting. */
    public static final double SETTLED = 0.25;

    public enum Stage { CHARGING, HEATING, REACTING, WAITING_DOSE, RESTING, READY }

    public enum Portion { STAGED, CHARGED, READY }

    /**
     * Parameters resolved when the batch began (handoff 13: a job records the resolved profile identity and the
     * parameters it needs, so a later data reload can not rewrite it into a different process).
     */
    public record Setup(ReactionFamily family, double idealLow, double idealHigh, double workLow, double workHigh, double inertia,
                        double stepSeconds, double restSeconds, double[] cooling, double[] kick, String profile, int generation) {
        public Setup {
            if (cooling.length != kick.length || cooling.length < 1 || cooling.length > MAX_STEPS) {
                throw new IllegalArgumentException("kettle setup needs 1.." + MAX_STEPS + " steps");
            }
            idealLow = Credit.clamp(idealLow, 0.2, 0.9);
            idealHigh = Credit.clamp(idealHigh, idealLow + 0.04, 0.95);
            workLow = Credit.clamp(workLow, 0.15, idealLow);
            workHigh = Credit.clamp(workHigh, idealHigh, 0.98);
            inertia = Credit.clamp(inertia, 0.5, 2.0);
            stepSeconds = Credit.clamp(stepSeconds, 3, 30);
            restSeconds = Credit.clamp(restSeconds, 1, 10);
            cooling = cooling.clone();
            kick = kick.clone();
        }

        public int steps() {
            return cooling.length;
        }

        /** Highest temperature at which the brew counts as resting (off the boil). */
        public double restMax() {
            return Math.min(0.9, idealHigh + 0.04);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("family", family.serialized());
            t.putDouble("idealLow", idealLow);
            t.putDouble("idealHigh", idealHigh);
            t.putDouble("workLow", workLow);
            t.putDouble("workHigh", workHigh);
            t.putDouble("inertia", inertia);
            t.putDouble("stepSeconds", stepSeconds);
            t.putDouble("restSeconds", restSeconds);
            ListTag c = new ListTag();
            ListTag k = new ListTag();
            for (int i = 0; i < cooling.length; i++) {
                c.add(net.minecraft.nbt.DoubleTag.valueOf(cooling[i]));
                k.add(net.minecraft.nbt.DoubleTag.valueOf(kick[i]));
            }
            t.put("cooling", c);
            t.put("kick", k);
            t.putString("profile", profile);
            t.putInt("generation", generation);
            return t;
        }

        public static Setup load(CompoundTag t) {
            ReactionFamily family = ReactionFamily.parse(t.getString("family"));
            if (family == null) throw new IllegalArgumentException("unknown reaction family " + t.getString("family"));
            ListTag c = t.getList("cooling", Tag.TAG_DOUBLE);
            ListTag k = t.getList("kick", Tag.TAG_DOUBLE);
            int n = Math.min(MAX_STEPS, Math.min(c.size(), k.size()));
            double[] cooling = new double[n];
            double[] kick = new double[n];
            for (int i = 0; i < n; i++) {
                cooling[i] = finite(c.getDouble(i), 0, 0.3);
                kick[i] = finite(k.getDouble(i), 0, 1);
            }
            return new Setup(family, finite(t.getDouble("idealLow"), 0, 1), finite(t.getDouble("idealHigh"), 0, 1),
                    finite(t.getDouble("workLow"), 0, 1), finite(t.getDouble("workHigh"), 0, 1), finite(t.getDouble("inertia"), 0.5, 2),
                    finite(t.getDouble("stepSeconds"), 3, 30), finite(t.getDouble("restSeconds"), 1, 10), cooling, kick,
                    t.getString("profile"), t.getInt("generation"));
        }
    }

    private final Setup setup;
    private final List<Integer> slots;
    private final List<Portion> portions;
    private int doses;
    private final double[] doseCredit = new double[MAX_STEPS];
    private int burner;
    private double flame;
    private double temp;
    private double circ;
    private double undissolved;
    private double mixTrouble;
    private double heatTrouble;
    private double progress;
    private double rest;
    private double thermalSum;
    private double thermalOver;
    private double thermalUnder;
    private double mixSum;
    private double weight;
    private boolean finalized;
    private int control = -1;
    private long ticks;
    private final List<UUID> participants = new ArrayList<>();

    private KettleProcess(Setup setup, List<Integer> slots, List<Portion> portions) {
        this.setup = setup;
        this.slots = slots;
        this.portions = portions;
    }

    /** A new batch: every serving staged in its rack position. */
    public static KettleProcess begin(Setup setup, int[] rackSlots, UUID brewer) {
        if (rackSlots.length < 1 || rackSlots.length > MAX_SERVINGS) throw new IllegalArgumentException("1.." + MAX_SERVINGS + " servings");
        List<Integer> slots = new ArrayList<>();
        List<Portion> portions = new ArrayList<>();
        for (int s : rackSlots) {
            slots.add(s);
            portions.add(Portion.STAGED);
        }
        KettleProcess p = new KettleProcess(setup, slots, portions);
        p.temp = 0.05;
        if (brewer != null) p.participants.add(brewer);
        return p;
    }

    public Setup setup() {
        return setup;
    }

    // ---- portions ----

    public int portions() {
        return portions.size();
    }

    public Portion portion(int i) {
        return portions.get(i);
    }

    public int slotOf(int i) {
        return slots.get(i);
    }

    /** The portion index reserved at a rack slot, or -1. */
    public int portionAtSlot(int slot) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i) == slot) return i;
        return -1;
    }

    public int count(Portion state) {
        int n = 0;
        for (Portion p : portions) if (p == state) n++;
        return n;
    }

    public boolean allCharged() {
        return count(Portion.STAGED) == 0;
    }

    /** Tips a staged serving into the vessel. Its cold liquid mixes into the warm charge. False when not staged. */
    public boolean pour(int i) {
        if (i < 0 || i >= portions.size() || portions.get(i) != Portion.STAGED || finalized) return false;
        int before = count(Portion.CHARGED);
        portions.set(i, Portion.CHARGED);
        temp = before == 0 ? Math.min(temp, 0.05) : (temp * before + 0.05) / (before + 1);
        circ = Math.min(1, circ + 0.25);
        return true;
    }

    /** Removes a bottled serving (the owner removed the same index from its inputs). */
    public void removePortion(int i) {
        if (portions.get(i) != Portion.READY) throw new IllegalStateException("only a ready portion can be bottled");
        portions.remove(i);
        slots.remove(i);
    }

    // ---- doses ----

    public int doses() {
        return doses;
    }

    /** Why the dose for {@code step} can not be tipped now (a translation key), or null when it can. */
    public String doseProblem(int step) {
        if (finalized) return "workshop.immersive_brewing.kettle.dose.done";
        if (step != doses) return step < doses ? "workshop.immersive_brewing.kettle.dose.added" : "workshop.immersive_brewing.kettle.dose.order";
        if (step >= setup.steps()) return "workshop.immersive_brewing.kettle.dose.added";
        if (!allCharged()) return "workshop.immersive_brewing.kettle.dose.charge_first";
        if (doses > 0 && progress < 1) return "workshop.immersive_brewing.kettle.dose.wait";
        return null;
    }

    /** Tips the next dose once (the owner moved nothing: the reserved stack stays owned for its by-product). */
    public boolean dose(int step) {
        if (doseProblem(step) != null) return false;
        doseCredit[step] = 0.55 + 0.45 * Credit.band(temp, setup.workLow(), setup.workHigh(), 0.18);
        temp = Math.max(0.02, temp - setup.cooling()[step]);
        undissolved = 1.0;
        mixTrouble = Math.min(1, mixTrouble + setup.kick()[step]);
        progress = 0;
        rest = 0;
        doses++;
        return true;
    }

    // ---- controls ----

    public int burner() {
        return burner;
    }

    public boolean setBurner(int value) {
        int v = Math.max(0, Math.min(BURNER_STEPS, value));
        if (v == burner) return false;
        burner = v;
        return true;
    }

    // ---- stepping ----

    public Stage stage() {
        if (count(Portion.STAGED) > 0) return Stage.CHARGING;
        if (finalized) return Stage.READY;
        if (doses == 0) return Stage.HEATING;
        if (progress < 1) return Stage.REACTING;
        if (doses < setup.steps()) return Stage.WAITING_DOSE;
        return Stage.RESTING;
    }

    /**
     * One 50 ms server step. {@code stirPx} is the paddle displacement credited this step (already bounded by
     * {@link ToolTrack}); {@code broad} its recent breadth.
     */
    public void tick(double stirPx, double broad) {
        ticks++;
        int charged = count(Portion.CHARGED) + count(Portion.READY);
        flame += (burner / (double) BURNER_STEPS - flame) * Math.min(1, 8 * DT);
        if (charged == 0) {
            temp = 0.05;
            circ = 0;
            return;
        }
        double k = 0.42 / (setup.inertia() * (0.75 + 0.07 * charged));
        double equilibrium = Math.max(0.05, flame);
        temp = Credit.clamp01(temp + (equilibrium - temp) * k * DT);

        double speed = stirPx / DT;
        double target = Math.min(1, speed / REF_SPEED_PX);
        circ = stirPx > 0 ? circ + (target - circ) * Math.min(1, 4 * DT) : circ * (1 - Math.min(1, 0.9 * DT));
        if (circ < 1e-4) circ = 0;

        Stage stage = stage();
        boolean reacting = stage == Stage.REACTING;
        double tf = thermalFactor(temp);
        if (undissolved > 0) {
            // Paddle work dissolves fastest; convection alone still finishes the job slowly, even in a cooling brew.
            double dissolve = stirPx * 0.02 * (0.3 + 0.7 * tf) + 0.035 * Math.max(0.25, tf) * DT;
            undissolved = Math.max(0, undissolved - dissolve);
        }
        mixTrouble = Credit.clamp01(mixTrouble + setup.family().mixRate(circ, temp, undissolved, broad, mixTrouble, reacting) * DT);
        heatTrouble = Credit.clamp01(heatTrouble + setup.family().heatRate(temp, heatTrouble) * DT);

        if (reacting) {
            double rate = tf * (1 - 0.65 * undissolved) / setup.stepSeconds();
            double dp = Math.min(1 - progress, rate * DT);
            if (dp > 0) {
                double band = Credit.band(temp, setup.idealLow(), setup.idealHigh(), 0.12);
                thermalSum += band * dp;
                if (temp > setup.idealHigh()) thermalOver += (1 - band) * dp;
                else if (temp < setup.idealLow()) thermalUnder += (1 - band) * dp;
                mixSum += Credit.below(mixTrouble, 0.2, 0.7) * dp;
                weight += dp;
                progress += dp;
            }
            if (progress >= 1 - 1e-9) progress = 1;
        } else if (stage == Stage.RESTING) {
            boolean calm = circ <= CALM && temp <= setup.restMax() && trouble() <= SETTLED && burner <= 2;
            rest = calm ? Math.min(1, rest + DT / setup.restSeconds()) : Math.max(0, rest - 0.5 * DT);
        }
    }

    /** How strongly the current temperature drives the reaction: none when cold, full in the workable band. */
    double thermalFactor(double t) {
        if (t < 0.2) return 0;
        if (t < setup.workLow()) return (t - 0.2) / Math.max(1e-6, setup.workLow() - 0.2);
        if (t <= setup.workHigh()) return 1;
        return Math.max(0.6, 1 - (t - setup.workHigh()) * 1.5);
    }

    /** The chemistry is done and the surface has rested: the station may commit the outputs now. */
    public boolean readyToFinalize() {
        return !finalized && stage() == Stage.RESTING && rest >= 1;
    }

    /** Freezes workmanship and marks every charged serving ready (the owner has written the finished stacks). */
    public int finalizeControl() {
        if (control < 0) control = computeControl();
        return control;
    }

    public void markFinalized() {
        finalizeControl();
        finalized = true;
        for (int i = 0; i < portions.size(); i++) if (portions.get(i) == Portion.CHARGED) portions.set(i, Portion.READY);
        burner = 0;
    }

    public boolean finalized() {
        return finalized;
    }

    public int computeControl() {
        return QualityMath.control(thermal(), incorporation(), dosing());
    }

    public double thermal() {
        return weight <= 0 ? 0 : thermalSum / weight;
    }

    public double incorporation() {
        return weight <= 0 ? 0 : mixSum / weight;
    }

    public double dosing() {
        if (setup.steps() == 0) return 0;
        double s = 0;
        for (int i = 0; i < setup.steps(); i++) s += doseCredit[i];
        return s / setup.steps();
    }

    /**
     * The single most useful observation for the batch journal (handoff 10: explain the largest actionable mistake, not
     * a wall of scores). A translation key.
     */
    public String tipKey() {
        double heatLoss = 0.40 * (1 - thermal());
        double mixLoss = 0.35 * (1 - incorporation());
        double doseLoss = 0.25 * (1 - dosing());
        double worst = Math.max(heatLoss, Math.max(mixLoss, doseLoss));
        if (worst < 0.02) return "tip.immersive_brewing.workshop.kettle.clean." + setup.family().serialized();
        if (worst == heatLoss) return thermalOver >= thermalUnder ? "tip.immersive_brewing.workshop.kettle.too_hot" : "tip.immersive_brewing.workshop.kettle.too_cool";
        if (worst == mixLoss) return "tip.immersive_brewing.workshop.kettle.trouble." + setup.family().serialized();
        return "tip.immersive_brewing.workshop.kettle.dose_cold";
    }

    // ---- readings ----

    public double temperature() {
        return temp;
    }

    public double flame() {
        return flame;
    }

    public double circulation() {
        return circ;
    }

    public double undissolved() {
        return undissolved;
    }

    public double progress() {
        return progress;
    }

    public double restProgress() {
        return rest;
    }

    public double mixTrouble() {
        return mixTrouble;
    }

    public double heatTrouble() {
        return heatTrouble;
    }

    /** What the surface shows: both kinds of trouble together. */
    public double trouble() {
        return Math.min(1, mixTrouble + heatTrouble);
    }

    public long ticks() {
        return ticks;
    }

    /** Temperature band for text and narration: 0 cold, 1 warm, 2 simmer, 3 boil, 4 rolling boil. */
    public static int heatBand(double t) {
        if (t < 0.2) return 0;
        if (t < 0.42) return 1;
        if (t < 0.68) return 2;
        if (t < 0.85) return 3;
        return 4;
    }

    /** The next useful observation for the player, as a translation key (handoff 10: observable guidance). */
    public String cueKey() {
        Stage stage = stage();
        String base = "cue.immersive_brewing.kettle.";
        return switch (stage) {
            case CHARGING -> base + "charge";
            case HEATING -> temp < setup.workLow() ? base + "warm" : base + "dose";
            case REACTING -> {
                if (temp < 0.2) yield base + "too_cold";
                if (temp > setup.workHigh()) yield base + "too_hot";
                if (mixTrouble > 0.35 || heatTrouble > 0.35) yield setup.family().troubleKey();
                if (undissolved > 0.3) yield base + "dissolve";
                if (temp < setup.idealLow()) yield base + "warmer";
                yield base + "reacting";
            }
            case WAITING_DOSE -> temp < setup.workLow() ? base + "warm" : base + "dose";
            case RESTING -> {
                if (burner > 2 || temp > setup.restMax()) yield base + "ease_heat";
                if (circ > CALM) yield base + "stop_stirring";
                if (trouble() > SETTLED) yield setup.family().troubleKey();
                yield base + "resting";
            }
            case READY -> base + "ready";
        };
    }

    // ---- participants (handoff 16: the brewer and helpers who did productive work) ----

    public void participate(UUID player) {
        if (player == null || participants.contains(player) || participants.size() >= MAX_PARTICIPANTS) return;
        participants.add(player);
    }

    public List<UUID> participants() {
        return participants;
    }

    /** Clears every control input on sealing: the flame is out, the paddle rests (state otherwise frozen). */
    public void seal() {
        burner = 0;
        flame = 0;
        circ = 0;
    }

    // ---- persistence ----

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "kettle");
        t.putInt("version", VERSION);
        t.put("setup", setup.save());
        t.putIntArray("slots", slots.stream().mapToInt(Integer::intValue).toArray());
        byte[] states = new byte[portions.size()];
        for (int i = 0; i < states.length; i++) states[i] = (byte) portions.get(i).ordinal();
        t.putByteArray("portions", states);
        t.putInt("doses", doses);
        ListTag credits = new ListTag();
        for (int i = 0; i < setup.steps(); i++) credits.add(net.minecraft.nbt.DoubleTag.valueOf(doseCredit[i]));
        t.put("doseCredit", credits);
        t.putInt("burner", burner);
        t.putDouble("flame", flame);
        t.putDouble("temp", temp);
        t.putDouble("circ", circ);
        t.putDouble("undissolved", undissolved);
        t.putDouble("mixTrouble", mixTrouble);
        t.putDouble("heatTrouble", heatTrouble);
        t.putDouble("progress", progress);
        t.putDouble("rest", rest);
        t.putDouble("thermalSum", thermalSum);
        t.putDouble("thermalOver", thermalOver);
        t.putDouble("thermalUnder", thermalUnder);
        t.putDouble("mixSum", mixSum);
        t.putDouble("weight", weight);
        t.putBoolean("finalized", finalized);
        t.putInt("control", control);
        t.putLong("ticks", ticks);
        ListTag people = new ListTag();
        for (UUID id : participants) people.add(StringTag.valueOf(id.toString()));
        t.put("participants", people);
        return t;
    }

    /** Decodes a saved process. Throws on an unknown kind, a newer version or inconsistent sizes (the caller holds the job). */
    public static KettleProcess load(CompoundTag t) {
        if (!"kettle".equals(t.getString("kind"))) throw new IllegalArgumentException("not a kettle process");
        int version = t.getInt("version");
        if (version != VERSION) throw new IllegalArgumentException("kettle process version " + version + " is not " + VERSION);
        Setup setup = Setup.load(t.getCompound("setup"));
        int[] slotArray = t.getIntArray("slots");
        byte[] states = t.getByteArray("portions");
        if (slotArray.length != states.length || slotArray.length > MAX_SERVINGS) throw new IllegalArgumentException("portion table mismatch");
        List<Integer> slots = new ArrayList<>();
        List<Portion> portions = new ArrayList<>();
        for (int i = 0; i < slotArray.length; i++) {
            if (slotArray[i] < 0 || slotArray[i] >= MAX_SERVINGS || states[i] < 0 || states[i] >= Portion.values().length) {
                throw new IllegalArgumentException("bad portion " + i);
            }
            slots.add(slotArray[i]);
            portions.add(Portion.values()[states[i]]);
        }
        KettleProcess p = new KettleProcess(setup, slots, portions);
        p.doses = Math.max(0, Math.min(setup.steps(), t.getInt("doses")));
        ListTag credits = t.getList("doseCredit", Tag.TAG_DOUBLE);
        for (int i = 0; i < Math.min(setup.steps(), credits.size()); i++) p.doseCredit[i] = finite(credits.getDouble(i), 0, 1);
        p.burner = Math.max(0, Math.min(BURNER_STEPS, t.getInt("burner")));
        p.flame = finite(t.getDouble("flame"), 0, 1);
        p.temp = finite(t.getDouble("temp"), 0, 1);
        p.circ = finite(t.getDouble("circ"), 0, 1);
        p.undissolved = finite(t.getDouble("undissolved"), 0, 1);
        p.mixTrouble = finite(t.getDouble("mixTrouble"), 0, 1);
        p.heatTrouble = finite(t.getDouble("heatTrouble"), 0, 1);
        p.progress = finite(t.getDouble("progress"), 0, 1);
        p.rest = finite(t.getDouble("rest"), 0, 1);
        p.thermalSum = finite(t.getDouble("thermalSum"), 0, MAX_STEPS);
        p.thermalOver = finite(t.getDouble("thermalOver"), 0, MAX_STEPS);
        p.thermalUnder = finite(t.getDouble("thermalUnder"), 0, MAX_STEPS);
        p.mixSum = finite(t.getDouble("mixSum"), 0, MAX_STEPS);
        p.weight = finite(t.getDouble("weight"), 0, MAX_STEPS);
        p.finalized = t.getBoolean("finalized");
        p.control = t.contains("control", Tag.TAG_INT) ? Math.max(-1, Math.min(100, t.getInt("control"))) : -1;
        p.ticks = Math.max(0, t.getLong("ticks"));
        ListTag people = t.getList("participants", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(MAX_PARTICIPANTS, people.size()); i++) {
            try {
                p.participants.add(UUID.fromString(people.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // A malformed participant entry only loses attribution, never the batch.
            }
        }
        if (p.finalized && p.count(Portion.CHARGED) > 0) throw new IllegalArgumentException("finalized batch with unready portions");
        return p;
    }

    static double finite(double v, double min, double max) {
        if (!Double.isFinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    // ---- presentation snapshot (compact; what observers need, nothing private) ----

    public void writeSnapshot(CompoundTag t) {
        t.putByte("stage", (byte) stage().ordinal());
        t.putByte("family", (byte) setup.family().ordinal());
        t.putByte("burner", (byte) burner);
        t.putByte("flame", q(flame));
        t.putByte("temp", q(temp));
        t.putByte("circ", q(circ));
        t.putByte("undissolved", q(undissolved));
        t.putByte("trouble", q(trouble()));
        t.putByte("heatTrouble", q(heatTrouble));
        t.putByte("progress", q(progress));
        t.putByte("rest", q(rest));
        t.putByte("doses", (byte) doses);
        t.putByte("steps", (byte) setup.steps());
        t.putByte("idealLow", q(setup.idealLow()));
        t.putByte("idealHigh", q(setup.idealHigh()));
        t.putByte("workLow", q(setup.workLow()));
        t.putByte("workHigh", q(setup.workHigh()));
        byte[] states = new byte[portions.size()];
        byte[] at = new byte[portions.size()];
        for (int i = 0; i < states.length; i++) {
            states[i] = (byte) portions.get(i).ordinal();
            at[i] = (byte) (int) slots.get(i);
        }
        t.putByteArray("portions", states);
        t.putByteArray("slots", at);
        t.putString("cue", cueKey());
    }

    static byte q(double v) {
        return (byte) Math.round(Credit.clamp01(v) * 100);
    }
}
