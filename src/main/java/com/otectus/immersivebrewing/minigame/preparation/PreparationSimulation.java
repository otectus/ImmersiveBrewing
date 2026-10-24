package com.otectus.immersivebrewing.minigame.preparation;

import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.potion.QualityMath;

/**
 * "Open the Ingredient" (spec 6), as a deterministic step simulation. The pestle moves toward the steered point at a
 * capped speed; held pressure builds and relaxes; motion under pressure processes the material according to its
 * {@link PreparationMode}; the batch slowly compacts and a lift-and-fold loosens it. The player decides when to stop.
 *
 * <p>Score: {@code E = round(100 x (0.70 x endpoint_accuracy + 0.30 x restraint))}; no productive work scores 0, and a
 * deadline resolves the current state rather than discarding the ingredient.
 */
public final class PreparationSimulation {
    public static final int STATE_VERSION = 1;
    private static final double PRESSURE_BUILD = 0.10;
    private static final double PRESSURE_RELAX = 0.22;
    private static final int FOLD_COOLDOWN = 10;
    /** Brittle material: holding pressure longer than this (one second) counts as excess force. */
    private static final int BRITTLE_HOLD_LIMIT = 20;
    /** Below this much total processing the run is not productive. */
    private static final double MIN_PRODUCTIVE_WORK = 0.02;

    private final PreparationParams params;
    private int step;
    private double px;
    private double py;
    private double pressure;
    private double processed;
    private double clump;
    private int foldCooldown;
    private int holdSteps;
    private int folds;
    private int workedSteps;
    private int overpressureSteps;
    private int wastedSteps;
    private int inputSteps;
    private double totalWork;
    private double lastWork;
    private boolean finished;
    private boolean finishedByPlayer;

    public PreparationSimulation(PreparationParams params) {
        this.params = params;
    }

    public PreparationParams params() {
        return params;
    }

    /** Advances one 50 ms step. Does nothing once finished. */
    public void step(PreparationInput input) {
        if (finished) return;
        if (input.finish() && step > 0) {
            finished = true;
            finishedByPlayer = true;
            return;
        }
        boolean any = input.press() || input.fold() || input.x() != 0 || input.y() != 0;
        if (any) inputSteps++;

        double motion = 0;
        if (input.fold() && foldCooldown == 0) {
            folds++;
            foldCooldown = FOLD_COOLDOWN;
            clump = Math.max(0, clump - params.foldRelief());
            pressure = 0;
            holdSteps = 0;
            if (params.mode() == PreparationMode.FIBROUS || params.mode() == PreparationMode.LIQUID) {
                // A fold exposes unworked fibres or folds the emulsion through: a little processing of its own.
                double gain = params.mode() == PreparationMode.LIQUID ? 0.05 : 0.025;
                processed = Math.min(1.0, processed + gain / bulk());
                totalWork += gain;
            }
        } else {
            double dx = input.targetX() - px;
            double dy = input.targetY() - py;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance > PreparationParams.MOTION_CAP) {
                dx *= PreparationParams.MOTION_CAP / distance;
                dy *= PreparationParams.MOTION_CAP / distance;
                distance = PreparationParams.MOTION_CAP;
            }
            double nx = px + dx;
            double ny = py + dy;
            double r = Math.sqrt(nx * nx + ny * ny);
            if (r > 1.0) {
                nx /= r;
                ny /= r;
            }
            motion = Math.sqrt((nx - px) * (nx - px) + (ny - py) * (ny - py));
            px = nx;
            py = ny;
            if (input.press()) {
                pressure += PRESSURE_BUILD * (1.0 - pressure);
                holdSteps++;
            } else {
                pressure -= PRESSURE_RELAX * pressure;
                holdSteps = 0;
            }
        }
        if (foldCooldown > 0) foldCooldown--;

        double work = motion * efficiency() * params.workRate() * (1.0 - 0.6 * clump) / bulk();
        boolean excessForce = input.press() && pressure > params.pressureLimit()
                || params.mode() == PreparationMode.BRITTLE && holdSteps > BRITTLE_HOLD_LIMIT;
        if (work > 0) {
            double extra = 0;
            if (excessForce && processed > params.endpointLow()) extra = work * 0.6; // fines / splatter
            processed = Math.min(1.0, processed + work + extra);
            clump = Math.min(1.0, clump + params.clumpRate() * work);
            totalWork += work + extra;
            workedSteps++;
            if (processed > params.endpointHigh()) wastedSteps++;
        }
        if (params.mode() == PreparationMode.LIQUID) clump = Math.min(1.0, clump + 0.004); // the emulsion separates at rest
        if (excessForce) overpressureSteps++;
        lastWork = work;

        step++;
        if (step >= params.durationSteps()) finished = true;
    }

    private double efficiency() {
        double p = pressure;
        double x = processed;
        return switch (params.mode()) {
            case CRYSTALLINE -> p * (1.15 - 0.6 * x);
            case FIBROUS -> 0.8 * (0.35 + 0.65 * Math.min(p, 0.6) / 0.6);
            case SOFT -> Math.max(0, 1.0 - Math.abs(p - 0.35) * 2.2);
            case BRITTLE -> p > 0.15 ? 1.2 * p : 0;
            case LIQUID -> 0.45;
        };
    }

    private double bulk() {
        return 1.0 + 0.15 * (params.kits() - 1);
    }

    public boolean finished() {
        return finished;
    }

    public boolean finishedByPlayer() {
        return finishedByPlayer;
    }

    public int step() {
        return step;
    }

    public int remainingSteps() {
        return Math.max(0, params.durationSteps() - step);
    }

    public double pestleX() {
        return px;
    }

    public double pestleY() {
        return py;
    }

    public double pressure() {
        return pressure;
    }

    public double processed() {
        return processed;
    }

    public double clump() {
        return clump;
    }

    public int folds() {
        return folds;
    }

    /** Processing done in the last step, for sound and particle intensity. */
    public double lastWork() {
        return lastWork;
    }

    public boolean overpressure() {
        return pressure > params.pressureLimit();
    }

    public Texture texture() {
        if (processed > params.overworkThreshold()) return Texture.OVERWORKED;
        if (processed > params.endpointHigh()) return Texture.FINE;
        if (processed >= params.endpointLow()) return Texture.ENDPOINT;
        if (processed >= params.endpointLow() * 0.5) return Texture.BREAKING;
        return Texture.COARSE;
    }

    /** The texture the player reads, described by text and shape as well as colour. */
    public enum Texture { COARSE, BREAKING, ENDPOINT, FINE, OVERWORKED }

    /** How much of the batch reached the requested window (spec 6). */
    public double endpointAccuracy() {
        double x = processed;
        double lo = params.endpointLow();
        double hi = params.endpointHigh();
        double ow = params.overworkThreshold();
        if (x < lo) return Math.pow(x / lo, 1.5);
        if (x <= hi) return 1.0;
        if (x <= ow) return 1.0 - 0.4 * (x - hi) / (ow - hi);
        return 0.6 * Math.max(0, 1.0 - (x - ow) / (1.0 - ow));
    }

    /** Time spent avoiding overpressure and unnecessary work past the endpoint. */
    public double restraint() {
        if (workedSteps == 0) return 0;
        return Math.max(0, 1.0 - (double) (overpressureSteps + wastedSteps) / workedSteps);
    }

    public boolean productive() {
        return totalWork >= MIN_PRODUCTIVE_WORK && inputSteps > 0;
    }

    public PreparationScore score() {
        double accuracy = endpointAccuracy();
        double restraint = restraint();
        int extraction = productive() ? QualityMath.extraction(accuracy, restraint) : 0;
        return new PreparationScore(extraction, accuracy, restraint, texture(), folds, overpressureSteps, wastedSteps,
                finishedByPlayer, productive());
    }

    public long[] saveState() {
        return new SimData.Writer().putInt(STATE_VERSION).putInt(step).putDouble(px).putDouble(py).putDouble(pressure)
                .putDouble(processed).putDouble(clump).putInt(foldCooldown).putInt(holdSteps).putInt(folds)
                .putInt(workedSteps).putInt(overpressureSteps).putInt(wastedSteps).putInt(inputSteps)
                .putDouble(totalWork).putBool(finished).putBool(finishedByPlayer).toArray();
    }

    public void loadState(long[] data) {
        SimData.Reader r = new SimData.Reader(data);
        int version = r.getInt(1, STATE_VERSION);
        if (version != STATE_VERSION) throw new IllegalArgumentException("preparation state version " + version);
        step = r.getInt(0, params.durationSteps());
        px = r.getDouble(-1, 1);
        py = r.getDouble(-1, 1);
        pressure = r.getDouble(0, 1);
        processed = r.getDouble(0, 1);
        clump = r.getDouble(0, 1);
        foldCooldown = r.getInt(0, FOLD_COOLDOWN);
        holdSteps = r.getInt(0, Integer.MAX_VALUE);
        folds = r.getInt(0, Integer.MAX_VALUE);
        workedSteps = r.getInt(0, Integer.MAX_VALUE);
        overpressureSteps = r.getInt(0, Integer.MAX_VALUE);
        wastedSteps = r.getInt(0, Integer.MAX_VALUE);
        inputSteps = r.getInt(0, Integer.MAX_VALUE);
        totalWork = r.getDouble(0, 1000);
        finished = r.getBool();
        finishedByPlayer = r.getBool();
    }

    /** Held pressure is released when a sealed preparation resumes (spec 20.4: held input resets to neutral). */
    public void neutraliseHeldInput() {
        pressure = 0;
        holdSteps = 0;
    }
}
