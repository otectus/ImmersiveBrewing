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
 * The hands-on Alembic purification (handoff 9). The charge warms while the apparatus is clamped and the burner valve
 * is open; vapour rises with temperature, and only what the coolant-fed condenser catches advances the run. The run
 * passes through cloudy heads, a clear heart with a forgiving plateau and hazy tails; the diverter, a real lever,
 * routes the stream to the waste collector or the receiver.
 *
 * <p>Score {@code D = distillation(collection, thermal, cooling)} with the existing weights. Collection is the heart
 * caught minus 1.5x any heads or tails caught, relative to the whole heart; transitional condensate counts half each
 * way. Thermal and cooling are sampled per unit of condensate, so a cold, closed or idle still waits without penalty and
 * there is no exercise timeout or input count. Yield, purification count and the quality cap stay with the recipe.
 */
public final class AlembicProcess {
    public static final int VERSION = 1;
    public static final int BURNER_STEPS = 4;
    public static final int VALVE_STEPS = 4;
    public static final int MAX_PARTICIPANTS = 5;
    /** Default end of the cloudy heads and start of the hazy tails, as parts of the run. */
    public static final double HEADS_END = 0.20;
    public static final double TAILS_START = 0.78;
    public static final double RUN_SECONDS = 27.0;
    /** Half-width of the readable transition into and out of the clear heart. */
    public static final double TRANSITION = 0.03;
    private static final double[] HEAT = {0, 0.40, 0.58, 0.74, 0.90};
    private static final double[] FLOW = {0, 0.25, 0.50, 0.75, 1.0};

    public enum Condensate { NONE, CLOUDY, CLEARING, CLEAR, HAZE_RISING, HAZY }

    public record Setup(int groups, int groupSize, int yield, int cap, int qualityIn, int coolantMb, double headsEnd, double tailsStart,
                        double runSeconds, String profile) {
        public Setup {
            headsEnd = Credit.clamp(headsEnd, 0.08, 0.40);
            tailsStart = Credit.clamp(tailsStart, headsEnd + 0.25, 0.92);
            runSeconds = Credit.clamp(runSeconds, 10, 60);
        }

        public Setup(int groups, int groupSize, int yield, int cap, int qualityIn, int coolantMb) {
            this(groups, groupSize, yield, cap, qualityIn, coolantMb, HEADS_END, TAILS_START, RUN_SECONDS, "");
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("groups", groups);
            t.putInt("groupSize", groupSize);
            t.putInt("yield", yield);
            t.putInt("cap", cap);
            t.putInt("qualityIn", qualityIn);
            t.putInt("coolantMb", coolantMb);
            t.putDouble("headsEnd", headsEnd);
            t.putDouble("tailsStart", tailsStart);
            t.putDouble("runSeconds", runSeconds);
            t.putString("profile", profile);
            return t;
        }

        public static Setup load(CompoundTag t) {
            return new Setup(Math.max(1, Math.min(2, t.getInt("groups"))), Math.max(1, Math.min(6, t.getInt("groupSize"))),
                    Math.max(0, Math.min(6, t.getInt("yield"))), Math.max(0, Math.min(100, t.getInt("cap"))),
                    Math.max(0, Math.min(100, t.getInt("qualityIn"))), Math.max(0, t.getInt("coolantMb")),
                    t.contains("headsEnd", Tag.TAG_DOUBLE) ? KettleProcess.finite(t.getDouble("headsEnd"), 0, 1) : HEADS_END,
                    t.contains("tailsStart", Tag.TAG_DOUBLE) ? KettleProcess.finite(t.getDouble("tailsStart"), 0, 1) : TAILS_START,
                    t.contains("runSeconds", Tag.TAG_DOUBLE) ? KettleProcess.finite(t.getDouble("runSeconds"), 10, 60) : RUN_SECONDS,
                    t.getString("profile"));
        }
    }

    private final Setup setup;
    private boolean clamped;
    private int burner;
    private int valve;
    private boolean receiver;
    private double temp = 0.1;
    private double coolant;
    private double fraction;
    private double heartCaught;
    private double impurityCaught;
    private double toWaste;
    private double toReceiver;
    private double thermalSum;
    private double coolingSum;
    private double weight;
    private long ticks;
    private final List<UUID> participants = new ArrayList<>();

    private AlembicProcess(Setup setup) {
        this.setup = setup;
    }

    public static AlembicProcess begin(Setup setup, UUID brewer) {
        AlembicProcess p = new AlembicProcess(setup);
        if (brewer != null) p.participants.add(brewer);
        return p;
    }

    public Setup setup() {
        return setup;
    }

    public boolean clamped() {
        return clamped;
    }

    public void setClamped(boolean value) {
        clamped = value;
    }

    public int burner() {
        return burner;
    }

    public boolean setBurner(int v) {
        v = Math.max(0, Math.min(BURNER_STEPS, v));
        if (v == burner) return false;
        burner = v;
        return true;
    }

    public int valve() {
        return valve;
    }

    public boolean setValve(int v) {
        v = Math.max(0, Math.min(VALVE_STEPS, v));
        if (v == valve) return false;
        valve = v;
        return true;
    }

    public boolean toReceiver() {
        return receiver;
    }

    public boolean setDiverter(boolean toReceiver) {
        if (receiver == toReceiver) return false;
        receiver = toReceiver;
        return true;
    }

    /** Nothing has condensed yet: the charge can still be withdrawn unchanged. */
    public boolean untouched() {
        return fraction <= 0;
    }

    public boolean finished() {
        return fraction >= 1;
    }

    public void tick() {
        if (finished()) return;
        ticks++;
        double dt = KettleProcess.DT;
        double heat = clamped ? HEAT[burner] : Math.min(HEAT[burner], 0.3);
        temp = Credit.clamp01(temp + (Math.max(0.1, heat) - temp) * 0.5 * dt);
        coolant = Credit.clamp01(coolant + (FLOW[valve] - coolant) * Math.min(1, 2 * dt));
        double vapour = clamped ? Math.max(0, temp - 0.38) * 1.6 : 0;
        double condensed = Math.min(vapour, coolant);
        if (coolant > vapour + 0.45) condensed *= 0.7; // a choked, over-cold coil runs sluggishly
        // At a steady, matched condensate rate of 0.5 the run takes the setup's run time.
        double df = Math.min(1 - fraction, condensed * (2.0 / setup.runSeconds()) * dt);
        if (df <= 0) return;
        double heartPart = heartShare(fraction, fraction + df) * df;
        double impurity = df - heartPart;
        if (receiver) {
            heartCaught += heartPart;
            impurityCaught += impurity;
            toReceiver += df;
        } else {
            toWaste += df;
        }
        thermalSum += Credit.band(temp, 0.60, 0.80, 0.12) * df;
        coolingSum += Credit.band(coolant - vapour, -0.05, 0.35, 0.25) * df;
        weight += df;
        fraction += df;
        if (fraction > 1 - 1e-9) fraction = 1;
    }

    /** Part of the run between {@code a} and {@code b} that is heart; transitional condensate counts half. */
    double heartShare(double a, double b) {
        double mid = (a + b) / 2;
        double heads = setup.headsEnd(), tails = setup.tailsStart();
        if (mid < heads - TRANSITION || mid > tails + TRANSITION) return 0;
        if (mid < heads + TRANSITION || mid > tails - TRANSITION) return 0.5;
        return 1;
    }

    public Condensate condensate() {
        if (fraction <= 0 && weight <= 0) return Condensate.NONE;
        double heads = setup.headsEnd(), tails = setup.tailsStart();
        if (fraction < heads - TRANSITION) return Condensate.CLOUDY;
        if (fraction < heads + TRANSITION) return Condensate.CLEARING;
        if (fraction < tails - TRANSITION) return Condensate.CLEAR;
        if (fraction < tails + TRANSITION) return Condensate.HAZE_RISING;
        return Condensate.HAZY;
    }

    public double collection() {
        return Credit.clamp01((heartCaught - 1.5 * impurityCaught) / (setup.tailsStart() - setup.headsEnd()));
    }

    public double thermal() {
        return weight <= 0 ? 0 : thermalSum / weight;
    }

    public double cooling() {
        return weight <= 0 ? 0 : coolingSum / weight;
    }

    public int distillation() {
        return QualityMath.distillation(collection(), thermal(), cooling());
    }

    public double fraction() {
        return fraction;
    }

    public double temperature() {
        return temp;
    }

    public double coolant() {
        return coolant;
    }

    /** Liquid routed to the receiver and the waste collector, as parts of the whole run (presentation). */
    public double receiverFill() {
        return toReceiver;
    }

    public double wasteFill() {
        return toWaste;
    }

    public String tipKey() {
        String base = "tip.immersive_brewing.workshop.alembic.";
        double c = collection();
        if (distillation() >= 90) return base + "clean";
        double lossCollect = 0.5 * (1 - c), lossThermal = 0.3 * (1 - thermal()), lossCool = 0.2 * (1 - cooling());
        if (lossCollect >= lossThermal && lossCollect >= lossCool) return impurityCaught > 0.05 ? base + "impure" : base + "missed_heart";
        if (lossThermal >= lossCool) return base + "heat";
        return base + "coolant";
    }

    public String cueKey() {
        String base = "cue.immersive_brewing.alembic.";
        if (!clamped) return base + "clamp";
        if (burner == 0) return base + "heat";
        if (valve == 0 && temp > 0.38) return base + "coolant";
        Condensate c = condensate();
        return switch (c) {
            case NONE -> temp < 0.42 ? base + "warming" : base + "coolant";
            case CLOUDY -> receiver ? base + "divert_waste" : base + "cloudy";
            case CLEARING -> base + "clearing";
            case CLEAR -> receiver ? base + "collect" : base + "divert_receiver";
            case HAZE_RISING -> base + "haze";
            case HAZY -> receiver ? base + "divert_waste" : base + "hazy";
        };
    }

    public void seal() {
        burner = 0;
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
        t.putString("kind", "alembic");
        t.putInt("version", VERSION);
        t.put("setup", setup.save());
        t.putBoolean("clamped", clamped);
        t.putInt("burner", burner);
        t.putInt("valve", valve);
        t.putBoolean("receiver", receiver);
        t.putDouble("temp", temp);
        t.putDouble("coolant", coolant);
        t.putDouble("fraction", fraction);
        t.putDouble("heartCaught", heartCaught);
        t.putDouble("impurityCaught", impurityCaught);
        t.putDouble("toWaste", toWaste);
        t.putDouble("toReceiver", toReceiver);
        t.putDouble("thermalSum", thermalSum);
        t.putDouble("coolingSum", coolingSum);
        t.putDouble("weight", weight);
        t.putLong("ticks", ticks);
        ListTag people = new ListTag();
        for (UUID id : participants) people.add(StringTag.valueOf(id.toString()));
        t.put("participants", people);
        return t;
    }

    public static AlembicProcess load(CompoundTag t) {
        if (!"alembic".equals(t.getString("kind"))) throw new IllegalArgumentException("not an alembic process");
        if (t.getInt("version") != VERSION) throw new IllegalArgumentException("alembic process version " + t.getInt("version"));
        AlembicProcess p = new AlembicProcess(Setup.load(t.getCompound("setup")));
        p.clamped = t.getBoolean("clamped");
        p.burner = Math.max(0, Math.min(BURNER_STEPS, t.getInt("burner")));
        p.valve = Math.max(0, Math.min(VALVE_STEPS, t.getInt("valve")));
        p.receiver = t.getBoolean("receiver");
        p.temp = KettleProcess.finite(t.getDouble("temp"), 0, 1);
        p.coolant = KettleProcess.finite(t.getDouble("coolant"), 0, 1);
        p.fraction = KettleProcess.finite(t.getDouble("fraction"), 0, 1);
        p.heartCaught = KettleProcess.finite(t.getDouble("heartCaught"), 0, 1);
        p.impurityCaught = KettleProcess.finite(t.getDouble("impurityCaught"), 0, 1);
        p.toWaste = KettleProcess.finite(t.getDouble("toWaste"), 0, 1);
        p.toReceiver = KettleProcess.finite(t.getDouble("toReceiver"), 0, 1);
        p.thermalSum = KettleProcess.finite(t.getDouble("thermalSum"), 0, 1);
        p.coolingSum = KettleProcess.finite(t.getDouble("coolingSum"), 0, 1);
        p.weight = KettleProcess.finite(t.getDouble("weight"), 0, 1);
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
        t.putBoolean("clamped", clamped);
        t.putByte("burner", (byte) burner);
        t.putByte("valve", (byte) valve);
        t.putBoolean("receiver", receiver);
        t.putByte("temp", KettleProcess.q(temp));
        t.putByte("coolant", KettleProcess.q(coolant));
        t.putByte("fraction", KettleProcess.q(fraction));
        t.putByte("toReceiver", KettleProcess.q(toReceiver));
        t.putByte("toWaste", KettleProcess.q(toWaste));
        t.putByte("condensate", (byte) condensate().ordinal());
        t.putByte("groups", (byte) setup.groups());
        t.putString("cue", cueKey());
    }
}
