package com.otectus.immersivebrewing.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Presentation and accessibility (spec 23.2). Nothing here changes a score; the one exception that touches gameplay,
 * {@link #REQUEST_ASSIST}, is only a request the server may approve within its own caps.
 */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    public enum Palette { STANDARD, HIGH_CONTRAST, COLORBLIND_SAFE }

    public static final ForgeConfigSpec.BooleanValue TOGGLE_STIR;
    public static final ForgeConfigSpec.BooleanValue TOGGLE_PRESSURE;
    public static final ForgeConfigSpec.BooleanValue MOUSE_STIRRING;
    public static final ForgeConfigSpec.EnumValue<Palette> PALETTE;
    public static final ForgeConfigSpec.BooleanValue LARGE_MARKERS;
    public static final ForgeConfigSpec.BooleanValue REDUCED_MOTION;
    public static final ForgeConfigSpec.BooleanValue SCREEN_SHAKE;
    public static final ForgeConfigSpec.DoubleValue PARTICLE_DENSITY;
    public static final ForgeConfigSpec.DoubleValue ANIMATION_INTENSITY;
    public static final ForgeConfigSpec.DoubleValue APPARATUS_VOLUME;
    public static final ForgeConfigSpec.DoubleValue CUE_VOLUME;
    public static final ForgeConfigSpec.BooleanValue WRITTEN_CUES;
    public static final ForgeConfigSpec.BooleanValue NUMERIC_DISPLAY;
    public static final ForgeConfigSpec.BooleanValue SHOW_TUTORIAL;
    public static final ForgeConfigSpec.BooleanValue STATION_NOTICES;
    public static final ForgeConfigSpec.IntValue NOTICE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue REQUEST_ASSIST;
    public static final ForgeConfigSpec.DoubleValue ASSIST_TIME;
    public static final ForgeConfigSpec.DoubleValue ASSIST_BAND;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Controls").push("controls");
        TOGGLE_STIR = b.comment("Tap a stir key to start or stop stirring instead of holding it.").define("toggleStir", false);
        TOGGLE_PRESSURE = b.comment("Tap the press key to start or stop pressing the pestle instead of holding it.").define("togglePressure", false);
        MOUSE_STIRRING = b.comment("Circles drawn with the mouse over the liquid also stir (capped exactly like the keys).").define("mouseStirring", true);
        b.pop();

        b.comment("Display").push("display");
        PALETTE = b.comment("STANDARD, HIGH_CONTRAST or COLORBLIND_SAFE. Every cue also has text and shape; colour is never the only signal.")
                .defineEnum("palette", Palette.STANDARD);
        LARGE_MARKERS = b.comment("Larger apparatus markers and band indicators.").define("largeMarkers", false);
        REDUCED_MOTION = b.comment("Calmer liquid animation and no camera effects.").define("reducedMotion", false);
        SCREEN_SHAKE = b.comment("Small screen shake on boiling over and burner flares.").define("screenShake", false);
        PARTICLE_DENSITY = b.comment("Station particle density, 0 (none) to 1.").defineInRange("particleDensity", 1.0, 0.0, 1.0);
        ANIMATION_INTENSITY = b.comment("Apparatus animation intensity, 0 to 1.").defineInRange("animationIntensity", 1.0, 0.0, 1.0);
        WRITTEN_CUES = b.comment("Show written cues (\"Gentle simmer\", \"Foam rising\") beside the apparatus.").define("writtenCues", true);
        NUMERIC_DISPLAY = b.comment("Show heat, circulation and quality as numbers too.").define("numericDisplay", false);
        SHOW_TUTORIAL = b.comment("Offer the tutorial overlay on a station screen until it has been completed once.").define("showTutorial", true);
        STATION_NOTICES = b.comment("Keep station messages readable above the hotbar instead of the vanilla action bar.").define("stationNotices", true);
        NOTICE_SECONDS = b.comment("How long a station notice stays.").defineInRange("noticeSeconds", 6, 2, 30);
        b.pop();

        b.comment("Sound").push("sound");
        APPARATUS_VOLUME = b.comment("Volume of bubbling, grinding and condensate sounds.").defineInRange("apparatusVolume", 1.0, 0.0, 1.0);
        CUE_VOLUME = b.comment("Volume of cue sounds (additions, warnings, completion).").defineInRange("cueVolume", 1.0, 0.0, 1.0);
        b.pop();

        b.comment("Accessibility assistance: requested from the server, which may approve it within its own limits").push("assistance");
        REQUEST_ASSIST = b.comment("Ask for slower control motion and cue timing and wider bands when a session starts.").define("requestAssist", false);
        ASSIST_TIME = b.comment("Requested slowing, 1.0 to 1.5.").defineInRange("assistTime", 1.25, 1.0, 1.5);
        ASSIST_BAND = b.comment("Requested band widening, 1.0 to 1.25.").defineInRange("assistBand", 1.15, 1.0, 1.25);
        b.pop();
        SPEC = b.build();
    }

    public static <T> T get(ForgeConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private ClientConfig() {}
}
