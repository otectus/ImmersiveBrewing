package com.otectus.immersivebrewing.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.util.function.Supplier;

/**
 * Gameplay configuration (spec 23.1). SERVER configs are per world and synced to clients, so tooltips and screens can
 * read them once a world is joined. Every read goes through the helpers below, which fall back to the defaults while no
 * world is loaded and repair inconsistent pairs (an inverted quality range falls back to its defaults with a log line).
 */
public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.EnumValue<BrewingMode> BREWING_MODE;
    public static final ForgeConfigSpec.BooleanValue REPLACE_MODIFIERS;
    public static final ForgeConfigSpec.IntValue MAX_BATCH_SERVINGS;
    public static final ForgeConfigSpec.IntValue SERVINGS_PER_VANILLA_REAGENT;
    public static final ForgeConfigSpec.IntValue FUEL_CHARGES_PER_BLAZE_POWDER;
    public static final ForgeConfigSpec.BooleanValue ALLOW_WATER_FLUID_FILLING;
    public static final ForgeConfigSpec.BooleanValue ALLOW_AUTOMATION_INPUTS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_AUTOMATION_OUTPUTS;
    public static final ForgeConfigSpec.IntValue MAX_BREW_DURATION_SECONDS;
    public static final ForgeConfigSpec.DoubleValue QUALITY_DURATION_MIN;
    public static final ForgeConfigSpec.DoubleValue QUALITY_DURATION_MAX;
    public static final ForgeConfigSpec.BooleanValue QUALITY_AFFECTS_DRINK_TIME;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PURIFICATION;
    public static final ForgeConfigSpec.IntValue PURIFICATION_QUALITY_CAP;
    public static final ForgeConfigSpec.IntValue MAX_PURIFICATIONS_PER_LINEAGE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_RECLAMATION;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ASSISTED_CONTROLS;
    public static final ForgeConfigSpec.DoubleValue MAX_ASSIST_TIME_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue MAX_ASSIST_BAND_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue MAX_DISCOVERY_PAIRS;
    public static final ForgeConfigSpec.IntValue MAX_PLAN_STEPS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VILLAGER_TRADES;
    public static final ForgeConfigSpec.IntValue COMPLETION_EXPERIENCE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    public static final ForgeConfigSpec.DoubleValue MAX_STATION_DISTANCE;
    public static final ForgeConfigSpec.IntValue COUNT_IN_TICKS;
    public static final ForgeConfigSpec.IntValue INPUT_BUFFER_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_INPUT_BUFFER_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Relationship with the ordinary brewing stand").push("brewing");
        BREWING_MODE = b.comment("COEXIST (default): stands keep working and the Kettle offers the hands-on, graded route.",
                        "REPLACE_COVERED: effect-creating transformations the Kettle covers are refused at the stand before it spends fuel.",
                        "DISABLED: no new Kettle, Mortar or Alembic jobs; existing jobs may still resume or be recovered.")
                .defineEnum("brewingMode", BrewingMode.COEXIST);
        REPLACE_MODIFIERS = b.comment("In REPLACE_COVERED, also route covered redstone, glowstone and container conversions through the Kettle.")
                .define("replaceModifiers", false);
        b.pop();

        b.comment("Batches, reagents and fuel").push("batches");
        MAX_BATCH_SERVINGS = b.comment("Most servings (bottles) one batch may hold.").defineInRange("maxBatchServings", 6, 1, 6);
        SERVINGS_PER_VANILLA_REAGENT = b.comment("Servings one vanilla reagent covers. Fixed by the vanilla adapter contract; may only be lowered.")
                .defineInRange("servingsPerVanillaReagent", 3, 1, 3);
        FUEL_CHARGES_PER_BLAZE_POWDER = b.comment("Kettle and Alembic fuel charges per blaze powder. Each reagent application to up to three servings costs one.",
                        "The brewing stand's own value in this Forge version is read at startup and logged; document any pack override.")
                .defineInRange("fuelChargesPerBlazePowder", 20, 1, 100);
        ALLOW_WATER_FLUID_FILLING = b.comment("Water from pipes or buckets fills real empty glass bottles in an idle Kettle at 250 mB per bottle.")
                .define("allowWaterFluidFilling", true);
        ALLOW_AUTOMATION_INPUTS = b.comment("Hoppers and pipes may insert idle ingredients, bottles and fuel.").define("allowAutomationInputs", true);
        ALLOW_AUTOMATION_OUTPUTS = b.comment("Hoppers and pipes may extract completed, unreserved output.").define("allowAutomationOutputs", true);
        b.pop();

        b.comment("Quality and its effect on potions").push("quality");
        MAX_BREW_DURATION_SECONDS = b.comment("Hard cap on an unassisted productive kettle reaction, in seconds.")
                .defineInRange("maxBrewDurationSeconds", 50, 20, 50);
        QUALITY_DURATION_MIN = b.comment("Lowest duration multiplier quality may apply (quality 0 gives 0.80 before this bound).")
                .defineInRange("qualityDurationMin", 0.80, 0.50, 1.0);
        QUALITY_DURATION_MAX = b.comment("Highest duration multiplier quality may apply (quality 100 gives 1.20 before this bound).")
                .defineInRange("qualityDurationMax", 1.20, 1.0, 2.0);
        QUALITY_AFFECTS_DRINK_TIME = b.comment("Quality changes the drinking time of ordinary drinkable potions (36 ticks at 0, 32 at 50, 28 at 100).")
                .define("qualityAffectsDrinkTime", true);
        b.pop();

        b.comment("The Alembic").push("alembic");
        ALLOW_PURIFICATION = b.comment("Allow 3 -> 2 purification of compatible, unrefined potions.").define("allowPurification", true);
        PURIFICATION_QUALITY_CAP = b.comment("Highest quality purification can reach. Never reaches Masterwork.")
                .defineInRange("purificationQualityCap", 70, 35, 89);
        MAX_PURIFICATIONS_PER_LINEAGE = b.comment("How many times one batch lineage may be purified. Carried through every later transformation.")
                .defineInRange("maxPurificationsPerLineage", 1, 0, 3);
        ALLOW_RECLAMATION = b.comment("Allow reclaiming unwanted potions into Spent Alchemical Salt.").define("allowReclamation", true);
        b.pop();

        b.comment("Accessibility assistance the server will approve when a player requests it").push("assistance");
        ALLOW_ASSISTED_CONTROLS = b.comment("Honour a player's request for slower control motion and cue timing and wider bands.")
                .define("allowAssistedControls", true);
        MAX_ASSIST_TIME_MULTIPLIER = b.comment("Most slowing an assisted session may use. Assisted sessions may run past maxBrewDurationSeconds, never past 90 s.")
                .defineInRange("maxAssistTimeMultiplier", 1.5, 1.0, 1.5);
        MAX_ASSIST_BAND_MULTIPLIER = b.comment("Most widening of physical control bands an assisted session may use.")
                .defineInRange("maxAssistBandMultiplier", 1.25, 1.0, 1.25);
        b.pop();

        b.comment("Recipe discovery").push("discovery");
        MAX_DISCOVERY_PAIRS = b.comment("Bounded (potion x ingredient) queries per discovery rebuild for brewing recipes that can not be enumerated.")
                .defineInRange("maxDiscoveryPairs", 4096, 0, 65536);
        MAX_PLAN_STEPS = b.comment("Most transformation steps one plan may chain.").defineInRange("maxPlanSteps", 8, 1, 8);
        b.pop();

        b.comment("Rewards").push("rewards");
        ENABLE_VILLAGER_TRADES = b.comment("Clerics sell the Notebook, ordinary reagents and ordinary-quality potions. Off until economy testing is complete.")
                .define("enableVillagerTrades", false);
        COMPLETION_EXPERIENCE = b.comment("Experience granted per completed productive brew. 0 avoids an experience loop.")
                .defineInRange("completionExperience", 0, 0, 10);
        b.pop();

        b.comment("Sessions and validation").push("sessions");
        MAX_STATION_DISTANCE = b.comment("Maximum distance in blocks between a player and the station they are working at.")
                .defineInRange("maxStationDistance", 6.0, 2.0, 16.0);
        COUNT_IN_TICKS = b.comment("Count-in before a session starts or resumes, in ticks.").defineInRange("countInTicks", 60, 20, 200);
        INPUT_BUFFER_TICKS = b.comment("Input playout buffer: the server simulates each step this many ticks after it was due, so ordinary latency costs nothing.")
                .defineInRange("inputBufferTicks", 3, 1, 10);
        MAX_INPUT_BUFFER_TICKS = b.comment("Upper bound when the buffer grows with a player's measured latency.")
                .defineInRange("maxInputBufferTicks", 8, 1, 20);
        DEBUG_LOGGING = b.comment("Detailed adapter, plan and session tracing in the log.").define("debugLogging", false);
        b.pop();

        SPEC = b.build();
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static <T> T get(ForgeConfigSpec.ConfigValue<T> value) {
        return loaded() ? value.get() : value.getDefault();
    }

    public static BrewingMode brewingMode() {
        return get(BREWING_MODE);
    }

    public static boolean disabled() {
        return brewingMode() == BrewingMode.DISABLED;
    }

    public static double qualityDurationMin() {
        double min = get(QUALITY_DURATION_MIN);
        double max = get(QUALITY_DURATION_MAX);
        return min <= 1.0 && max >= 1.0 && min <= max ? min : 0.80;
    }

    public static double qualityDurationMax() {
        double min = get(QUALITY_DURATION_MIN);
        double max = get(QUALITY_DURATION_MAX);
        return min <= 1.0 && max >= 1.0 && min <= max ? max : 1.20;
    }

    public static boolean qualityAffectsDrinkTime() {
        return get(QUALITY_AFFECTS_DRINK_TIME);
    }

    public static int fuelChargesPerBlazePowder() {
        return Math.max(1, get(FUEL_CHARGES_PER_BLAZE_POWDER));
    }

    public static int servingsPerVanillaReagent() {
        return Math.max(1, Math.min(3, get(SERVINGS_PER_VANILLA_REAGENT)));
    }

    public static int maxBatchServings() {
        return Math.max(1, Math.min(6, get(MAX_BATCH_SERVINGS)));
    }

    public static boolean debug() {
        return get(DEBUG_LOGGING);
    }

    /** Whether a config event concerns this spec (used to invalidate caches and seal sessions on change). */
    public static boolean is(ModConfig config) {
        return config.getSpec() == SPEC;
    }

    public static <T> Supplier<T> supplier(ForgeConfigSpec.ConfigValue<T> value) {
        return () -> get(value);
    }

    private ServerConfig() {}
}
