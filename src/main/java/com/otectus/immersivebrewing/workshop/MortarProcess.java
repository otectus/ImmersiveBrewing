package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.minigame.Credit;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.potion.QualityMath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The hands-on mortar (handoff 6): a persistent material operation on one to four reagent units worked together. The
 * bowl is five material zones (centre and four quarters); each holds its processed fraction and the fines that
 * overprocessing makes. Clumping covers each family's correctable mistake (paste on the rim, aeration). Work comes only
 * from pestle contact in the bowl (bounded by {@link ToolTrack}), presses at a selected effort, and scraper folds that
 * redistribute material.
 *
 * <p>There is no countdown: finishing becomes available as soon as the material is usable, resting the pestle is safe,
 * and continuing past the endpoint only adds fines and overwork. Extraction {@code E} keeps the existing formula, with
 * accuracy from the endpoint and evenness and restraint from fines, overwork and clumping.
 */
public final class MortarProcess {
    public static final int VERSION = 1;
    public static final int ZONES = 5;
    public static final int MAX_UNITS = 4;
    public static final int MAX_PARTICIPANTS = 5;
    /** Server steps between two accepted presses (five a second). */
    public static final int PRESS_COOLDOWN = 4;

    /** Material operations a profile can allow (handoff 13). */
    public static final int OP_PRESS = 1, OP_GRIND = 2, OP_FOLD = 4, OP_SWEEP = 8, OP_ALL = 15;

    public static int operations(java.util.List<String> names) {
        int mask = 0;
        for (String n : names) {
            mask |= switch (n) {
                case "press" -> OP_PRESS;
                case "grind" -> OP_GRIND;
                case "fold" -> OP_FOLD;
                case "sweep" -> OP_SWEEP;
                default -> 0;
            };
        }
        return mask == 0 ? OP_ALL : mask;
    }

    /**
     * @param workScale    1 / required work: how much processing one unit of accepted tool work does
     * @param maxWorkPerTick the most mean processing (over the bowl) that may accrue per server step on average; a press
     *                     may spend up to a second of it at once
     * @param operations   which operations work this material ({@link #OP_PRESS} ...); others do nothing to it
     */
    public record Setup(PreparationMode mode, int units, double endpointLow, double endpointHigh, double overworkStart,
                        double usefulLow, double usefulHigh, double workScale, double maxWorkPerTick, int operations, String profile, int generation) {
        public Setup(PreparationMode mode, int units, double endpointLow, double endpointHigh, double overworkStart,
                     double usefulLow, double usefulHigh, double workScale, String profile, int generation) {
            this(mode, units, endpointLow, endpointHigh, overworkStart, usefulLow, usefulHigh, workScale, 0.05, OP_ALL, profile, generation);
        }

        public Setup {
            maxWorkPerTick = Credit.clamp(maxWorkPerTick, 0.005, 0.2);
            operations = operations & OP_ALL;
            if (operations == 0) operations = OP_ALL;
            units = Math.max(1, Math.min(MAX_UNITS, units));
            endpointLow = Credit.clamp(endpointLow, 0.05, 0.95);
            endpointHigh = Credit.clamp(endpointHigh, endpointLow + 0.04, 0.98);
            overworkStart = Credit.clamp(overworkStart, endpointHigh + 0.01, 0.99);
            usefulLow = Credit.clamp(usefulLow, 0.05, 0.9);
            usefulHigh = Credit.clamp(usefulHigh, usefulLow + 0.05, 1.0);
            workScale = Credit.clamp(workScale, 0.25, 4);
        }

        /** Shared work for a larger batch: four units take about 1.6 times the work of one, never four repeats. */
        public double bulk() {
            return 1 + 0.2 * (units - 1);
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("mode", mode.serialized());
            t.putInt("units", units);
            t.putDouble("endpointLow", endpointLow);
            t.putDouble("endpointHigh", endpointHigh);
            t.putDouble("overworkStart", overworkStart);
            t.putDouble("usefulLow", usefulLow);
            t.putDouble("usefulHigh", usefulHigh);
            t.putDouble("workScale", workScale);
            t.putDouble("maxWorkPerTick", maxWorkPerTick);
            t.putInt("operations", operations);
            t.putString("profile", profile);
            t.putInt("generation", generation);
            return t;
        }

        public static Setup load(CompoundTag t) {
            PreparationMode mode = PreparationMode.parse(t.getString("mode"));
            if (mode == null) throw new IllegalArgumentException("unknown preparation family " + t.getString("mode"));
            return new Setup(mode, t.getInt("units"), KettleProcess.finite(t.getDouble("endpointLow"), 0, 1),
                    KettleProcess.finite(t.getDouble("endpointHigh"), 0, 1), KettleProcess.finite(t.getDouble("overworkStart"), 0, 1),
                    KettleProcess.finite(t.getDouble("usefulLow"), 0, 1), KettleProcess.finite(t.getDouble("usefulHigh"), 0, 1),
                    KettleProcess.finite(t.getDouble("workScale"), 0.25, 4),
                    t.contains("maxWorkPerTick", Tag.TAG_DOUBLE) ? KettleProcess.finite(t.getDouble("maxWorkPerTick"), 0.005, 0.2) : 0.05,
                    t.contains("operations", Tag.TAG_INT) ? t.getInt("operations") : OP_ALL, t.getString("profile"), t.getInt("generation"));
        }
    }

    /** Per-family material response. */
    private record Response(double press, double grind, double fines, double rimPaste, double foldMix) {}

    private static Response response(PreparationMode mode) {
        return switch (mode) {
            case CRYSTALLINE -> new Response(0.14, 0.035, 0.55, 0, 0);
            case FIBROUS -> new Response(0.05, 0.040, 0.35, 0, 0);
            case SOFT -> new Response(0.14, 0.030, 0.25, 0.30, 0);
            case BRITTLE -> new Response(0.26, 0.030, 1.10, 0, 0);
            case LIQUID -> new Response(0.012, 0.015, 0.10, 0, 0.05);
        };
    }

    /** What the material is like now. */
    public record Evaluation(double processed, double spread, double fines, double clump, double overwork,
                             double accuracy, double restraint, int extraction, boolean usable, String textureKey) {}

    private final Setup setup;
    private final double[] processed = new double[ZONES];
    private final double[] fines = new double[ZONES];
    private double clump;
    private double overwork;
    private int presses;
    private double grindPx;
    private double foldPx;
    private long ticks;
    private long lastPress = -PRESS_COOLDOWN;
    private boolean workedThisTick;
    /** Mean-processing budget (token bucket): refills {@code maxWorkPerTick} a step, holds one second's worth. */
    private double budget = -1;
    private final List<UUID> participants = new ArrayList<>();

    private MortarProcess(Setup setup) {
        this.setup = setup;
    }

    public static MortarProcess begin(Setup setup, UUID brewer) {
        MortarProcess p = new MortarProcess(setup);
        if (brewer != null) p.participants.add(brewer);
        return p;
    }

    public Setup setup() {
        return setup;
    }

    /** The material zone under a normalised bowl point: the centre, or one of four quarters. */
    public static int zoneAt(double u, double v) {
        double r = Math.sqrt(u * u + v * v);
        if (r < 0.38) return 0;
        double angle = Math.atan2(v, u);
        int quarter = (int) Math.floor((angle + Math.PI) / (Math.PI / 2)) % 4;
        return 1 + Math.max(0, quarter);
    }

    private double useful(double effort) {
        return Credit.band(effort, setup.usefulLow(), setup.usefulHigh(), 0.25);
    }

    private double scale() {
        return setup.workScale() / setup.bulk();
    }

    private double capacity() {
        return setup.maxWorkPerTick() * 20;
    }

    /** The part of {@code amount} (applied to one zone) the work budget allows; the rest is not done. */
    private double allow(double amount) {
        if (budget < 0) budget = capacity();
        double allowed = Math.min(amount, budget * ZONES);
        budget -= allowed / ZONES;
        return Math.max(0, allowed);
    }

    /** Applies processing to one zone; work past the overwork start turns into fines instead. */
    private void process(int zone, double amount) {
        amount = allow(amount);
        if (amount <= 0) return;
        Response r = response(setup.mode());
        if (processed[zone] >= setup.overworkStart()) {
            fines[zone] = Math.min(1, fines[zone] + amount * r.fines());
        } else {
            processed[zone] = Math.min(1, processed[zone] + amount * (1 - processed[zone]));
        }
        if (mean() > setup.endpointHigh()) overwork = Math.min(2, overwork + amount * 0.5);
    }

    /**
     * A press at the pestle's position with the selected effort. Returns false when it came too soon after the last one
     * (rate-limited: holding a key or flooding clicks does not crush faster).
     */
    public boolean press(double u, double v, double effort, long tick) {
        if ((setup.operations() & OP_PRESS) == 0) return false;
        if (tick - lastPress < PRESS_COOLDOWN) return false;
        lastPress = tick;
        presses++;
        workedThisTick = true;
        Response r = response(setup.mode());
        double dp = r.press() * (0.35 + 0.65 * useful(effort)) * scale();
        int zone = zoneAt(u, v);
        process(zone, dp);
        for (int z = 0; z < ZONES; z++) if (z != zone && neighbours(zone, z)) process(z, dp * 0.25);
        if (effort > setup.usefulHigh()) {
            double excess = effort - setup.usefulHigh();
            fines[zone] = Math.min(1, fines[zone] + excess * 0.08 * r.fines() * scale());
            if (r.rimPaste() > 0) clump = Math.min(1, clump + excess * r.rimPaste());
            if (setup.mode() == PreparationMode.LIQUID) clump = Math.min(1, clump + excess * 0.2);
        }
        return true;
    }

    private static boolean neighbours(int a, int b) {
        if (a == 0 || b == 0) return true;
        int d = Math.abs(a - b);
        return d == 1 || d == 3;
    }

    /** Grinding: pestle displacement in contact this step (pixels, already bounded) at the pestle's position. */
    public void grind(double px, double u, double v, double effort) {
        if (px <= 0 || (setup.operations() & (OP_GRIND | OP_SWEEP)) == 0) return;
        workedThisTick = true;
        grindPx += px;
        Response r = response(setup.mode());
        process(zoneAt(u, v), px * r.grind() * (0.35 + 0.65 * useful(effort)) * scale());
        if (setup.mode() == PreparationMode.LIQUID && px > 1.0) clump = Math.min(1, clump + (px - 1.0) * 0.05);
        if (r.rimPaste() > 0 && effort > setup.usefulHigh()) clump = Math.min(1, clump + px * 0.004);
    }

    /** Scraper folding: redistributes material toward even and scrapes rim paste or froth back in. */
    public void fold(double px) {
        if (px <= 0 || (setup.operations() & OP_FOLD) == 0) return;
        workedThisTick = true;
        foldPx += px;
        double f = Math.min(1, px * 0.05);
        double mean = mean();
        double meanFines = meanFines();
        for (int z = 0; z < ZONES; z++) {
            processed[z] += (mean - processed[z]) * f;
            fines[z] += (meanFines - fines[z]) * f;
        }
        clump = Math.max(0, clump - px * 0.05);
        Response r = response(setup.mode());
        if (r.foldMix() > 0) for (int z = 0; z < ZONES; z++) process(z, px * r.foldMix() * scale() / ZONES);
    }

    /** One server step: an emulsion that is left alone loses its froth. */
    public void tick() {
        ticks++;
        budget = Math.min(capacity(), (budget < 0 ? capacity() : budget) + setup.maxWorkPerTick());
        if (!workedThisTick && setup.mode() == PreparationMode.LIQUID) clump = Math.max(0, clump - 0.12 * KettleProcess.DT);
        workedThisTick = false;
    }

    public double mean() {
        double s = 0;
        for (double p : processed) s += p;
        return s / ZONES;
    }

    private double meanFines() {
        double s = 0;
        for (double f : fines) s += f;
        return s / ZONES;
    }

    public double spread() {
        double min = 1, max = 0;
        for (double p : processed) {
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        return max - min;
    }

    public double processed(int zone) {
        return processed[zone];
    }

    public double fines(int zone) {
        return fines[zone];
    }

    public double clump() {
        return clump;
    }

    public int presses() {
        return presses;
    }

    public Evaluation evaluate() {
        double p = mean();
        double s = spread();
        double f = meanFines();
        double accuracy = Credit.band(p, setup.endpointLow(), setup.endpointHigh(), 0.15) * (1 - 0.8 * Credit.clamp01((s - 0.12) / 0.4));
        double restraint = Credit.clamp01(1 - 1.6 * f - 1.2 * Math.min(1, overwork) - 0.9 * clump);
        int e = QualityMath.extraction(accuracy, restraint);
        boolean usable = p >= Math.max(0.2, setup.endpointLow() - 0.2);
        return new Evaluation(p, s, f, clump, overwork, accuracy, restraint, e, usable, texture(p, s, f));
    }

    private String texture(double p, double s, double f) {
        String family = setup.mode().serialized();
        String base = "texture.immersive_brewing.workshop.";
        if (p < 0.15) return base + family + ".whole";
        if (f > 0.18 || overwork > 0.3) return base + family + ".overworked";
        if (clump > 0.3) return base + family + ".clump";
        if (s > 0.25) return base + family + ".uneven";
        if (p < setup.endpointLow()) return base + family + ".coarse";
        if (p <= setup.endpointHigh()) return base + family + ".ready";
        return base + family + ".fine";
    }

    /** The single most useful observation once the kits are made (handoff 10). */
    public String tipKey() {
        Evaluation e = evaluate();
        String base = "tip.immersive_brewing.workshop.mortar.";
        if (e.extraction() >= 95) return base + "clean";
        double lossAccuracy = 0.70 * (1 - e.accuracy());
        double lossRestraint = 0.30 * (1 - e.restraint());
        if (lossRestraint > lossAccuracy) {
            if (clump > 0.2) return base + "clump." + setup.mode().serialized();
            return base + "overworked";
        }
        if (e.spread() > 0.2) return base + "uneven";
        return e.processed() < setup.endpointLow() ? base + "coarse" : base + "fine";
    }

    public String cueKey() {
        return evaluate().textureKey();
    }

    public void participate(UUID player) {
        if (player == null || participants.contains(player) || participants.size() >= MAX_PARTICIPANTS) return;
        participants.add(player);
    }

    public List<UUID> participants() {
        return participants;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "mortar");
        t.putInt("version", VERSION);
        t.put("setup", setup.save());
        ListTag zones = new ListTag();
        for (int z = 0; z < ZONES; z++) {
            CompoundTag zone = new CompoundTag();
            zone.putDouble("p", processed[z]);
            zone.putDouble("f", fines[z]);
            zones.add(zone);
        }
        t.put("zones", zones);
        t.putDouble("clump", clump);
        t.putDouble("overwork", overwork);
        t.putInt("presses", presses);
        t.putDouble("budget", budget);
        t.putDouble("grindPx", grindPx);
        t.putDouble("foldPx", foldPx);
        t.putLong("ticks", ticks);
        ListTag people = new ListTag();
        for (UUID id : participants) people.add(StringTag.valueOf(id.toString()));
        t.put("participants", people);
        return t;
    }

    public static MortarProcess load(CompoundTag t) {
        if (!"mortar".equals(t.getString("kind"))) throw new IllegalArgumentException("not a mortar process");
        if (t.getInt("version") != VERSION) throw new IllegalArgumentException("mortar process version " + t.getInt("version"));
        MortarProcess p = new MortarProcess(Setup.load(t.getCompound("setup")));
        ListTag zones = t.getList("zones", Tag.TAG_COMPOUND);
        if (zones.size() != ZONES) throw new IllegalArgumentException("mortar zones " + zones.size());
        for (int z = 0; z < ZONES; z++) {
            p.processed[z] = KettleProcess.finite(zones.getCompound(z).getDouble("p"), 0, 1);
            p.fines[z] = KettleProcess.finite(zones.getCompound(z).getDouble("f"), 0, 1);
        }
        p.clump = KettleProcess.finite(t.getDouble("clump"), 0, 1);
        p.overwork = KettleProcess.finite(t.getDouble("overwork"), 0, 2);
        p.presses = Math.max(0, t.getInt("presses"));
        p.budget = t.contains("budget", Tag.TAG_DOUBLE) ? KettleProcess.finite(t.getDouble("budget"), -1, 4) : -1;
        p.grindPx = KettleProcess.finite(t.getDouble("grindPx"), 0, 1e9);
        p.foldPx = KettleProcess.finite(t.getDouble("foldPx"), 0, 1e9);
        p.ticks = Math.max(0, t.getLong("ticks"));
        ListTag people = t.getList("participants", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(MAX_PARTICIPANTS, people.size()); i++) {
            try {
                p.participants.add(UUID.fromString(people.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // attribution only
            }
        }
        return p;
    }

    public void writeSnapshot(CompoundTag t) {
        Evaluation e = evaluate();
        t.putByte("family", (byte) setup.mode().ordinal());
        t.putByte("units", (byte) setup.units());
        t.putByte("ops", (byte) setup.operations());
        byte[] zones = new byte[ZONES];
        byte[] dust = new byte[ZONES];
        for (int z = 0; z < ZONES; z++) {
            zones[z] = KettleProcess.q(processed[z]);
            dust[z] = KettleProcess.q(fines[z]);
        }
        t.putByteArray("zones", zones);
        t.putByteArray("fines", dust);
        t.putByte("clump", KettleProcess.q(clump));
        t.putByte("processed", KettleProcess.q(e.processed()));
        t.putByte("endpointLow", KettleProcess.q(setup.endpointLow()));
        t.putByte("endpointHigh", KettleProcess.q(setup.endpointHigh()));
        t.putBoolean("usable", e.usable());
        t.putString("cue", e.textureKey());
    }

    public static String familyKey(PreparationMode mode) {
        return "family.immersive_brewing." + mode.name().toLowerCase(Locale.ROOT);
    }
}
