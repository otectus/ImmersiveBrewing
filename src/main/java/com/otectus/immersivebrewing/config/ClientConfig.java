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
    public static final ForgeConfigSpec.BooleanValue REDUCED_INPUT;
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
    public static final ForgeConfigSpec.EnumValue<HudAnchor> PINNED_HUD_ANCHOR;
    public static final ForgeConfigSpec.IntValue PINNED_HUD_Y_OFFSET;
    public static final ForgeConfigSpec.BooleanValue PINNED_HUD_COMPACT;
    public static final ForgeConfigSpec.BooleanValue PINNED_HUD_HIDE_SCREEN;
    public static final ForgeConfigSpec.BooleanValue INSPECTION_HINTS;
    public static final ForgeConfigSpec.BooleanValue REQUEST_ASSIST;
    public static final ForgeConfigSpec.DoubleValue ASSIST_TIME;
    public static final ForgeConfigSpec.DoubleValue ASSIST_BAND;

    public enum HudAnchor { LEFT, RIGHT }

    /**
     * 1.2.0 before the close-up camera: FOCUS turned the view toward the apparatus, DIRECT kept it. Still read while
     * {@link #CAMERA_STYLE} is AUTO, so a player who chose DIRECT is never given a moving camera by an update.
     */
    public enum CameraMode { FOCUS, DIRECT }

    /** How the working view frames a station (handoff 5.5). */
    public enum CameraStyle {
        /** CLOSE_UP for new settings; EYE when the legacy cameraMode was DIRECT; INSTANT when reduced motion is on. */
        AUTO,
        /** The camera glides above the apparatus (a short eased move, interruptible) and back. */
        CLOSE_UP,
        /** The same close-up view without travel: a cut in and a cut out. */
        INSTANT,
        /** Your own view from your eyes; nothing moves the camera. */
        EYE
    }

    /** How much the working view says in words (handoff 10). */
    public enum Guidance {
        /** DETAILED when numericDisplay is on, otherwise GUIDED. */
        AUTO,
        /** The apparatus, the hovered part's short label, refusals and anything urgent. */
        MINIMAL,
        /** Also the one useful next action and the first-use demonstrations. */
        GUIDED,
        /** Also numbers, the full control help and diagnostics. */
        DETAILED
    }

    /** What is drawn gripping a tool or vessel at a station. */
    public enum HandStyle { ARMS, TOOLS_ONLY }

    public static final ForgeConfigSpec.EnumValue<CameraMode> CAMERA_MODE;
    public static final ForgeConfigSpec.EnumValue<CameraStyle> CAMERA_STYLE;
    public static final ForgeConfigSpec.DoubleValue CAMERA_SECONDS;
    public static final ForgeConfigSpec.IntValue CLOSE_UP_FOV;
    public static final ForgeConfigSpec.EnumValue<Guidance> GUIDANCE;
    public static final ForgeConfigSpec.EnumValue<HandStyle> HANDS;
    public static final ForgeConfigSpec.DoubleValue ARM_OPACITY;
    public static final ForgeConfigSpec.BooleanValue HOLD_TO_GRAB;
    public static final ForgeConfigSpec.DoubleValue TOOL_SENSITIVITY;
    public static final ForgeConfigSpec.DoubleValue TOOL_DEAD_ZONE;
    public static final ForgeConfigSpec.DoubleValue TOOL_SMOOTHING;
    public static final ForgeConfigSpec.BooleanValue MIRROR_TOOLS;
    public static final ForgeConfigSpec.BooleanValue PART_HINTS;
    public static final ForgeConfigSpec.IntValue TUTORIAL_STAGE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        // 1.2: the minigame options keep their keys and carry a player's choice into the hands-on workshop.
        b.comment("Controls").push("controls");
        TOGGLE_STIR = b.comment("Tap the Work key to start or stop working a held tool (stirring, grinding) instead of holding it.",
                "Was: toggle stirring in the Kettle minigame.").define("toggleStir", false);
        TOGGLE_PRESSURE = b.comment("Firm pestle pressure toggles with the press key instead of following a held mouse button.",
                "Was: toggle pestle pressure in the Mortar minigame.").define("togglePressure", false);
        MOUSE_STIRRING = b.comment("A held tool follows the mouse over its work surface. Off: tools move only with the keys and the Work action.",
                "Was: mouse circles also stir.").define("mouseStirring", true);
        REDUCED_INPUT = b.comment("One-key paths everywhere: every hold becomes a toggle and nothing needs two keys at once.").define("reducedInput", false);
        HOLD_TO_GRAB = b.comment("Hold the mouse button to keep a tool in hand (release puts it down). Off: click to take, click its rest to put down.")
                .define("holdToGrab", false);
        TOOL_SENSITIVITY = b.comment("How far a held tool moves for a given mouse movement or key press.").defineInRange("toolSensitivity", 1.0, 0.25, 3.0);
        TOOL_DEAD_ZONE = b.comment("Mouse movement (part of the work surface) ignored before a held tool moves; steadies shaky hands.")
                .defineInRange("toolDeadZone", 0.0, 0.0, 0.2);
        TOOL_SMOOTHING = b.comment("Visual weight of a held tool following the hand, 0 (none) to 0.9. Never delays what the server receives.")
                .defineInRange("toolSmoothing", 0.35, 0.0, 0.9);
        b.pop();

        b.comment("The working view at a station").push("workshop");
        CAMERA_STYLE = b.comment("How the working view frames a station.",
                "CLOSE_UP: the camera glides (about a third of a second) above and toward the apparatus, so you look into the vessel.",
                "INSTANT: the same close-up view without the glide. EYE: your own view from your eyes, nothing moves.",
                "AUTO (the default): CLOSE_UP, except EYE if cameraMode below is DIRECT and INSTANT if reducedMotion is on.",
                "The camera never passes through walls; where there is no room it works from your eyes and says so.")
                .defineEnum("cameraStyle", CameraStyle.AUTO);
        CAMERA_MODE = b.comment("Legacy setting from before the close-up camera, read only while cameraStyle is AUTO.",
                "DIRECT keeps your own view (as EYE); FOCUS allows the close-up camera.").defineEnum("cameraMode", CameraMode.FOCUS);
        CAMERA_SECONDS = b.comment("How long the close-up camera takes to glide in and out, in seconds.").defineInRange("cameraSeconds", 0.35, 0.2, 0.6);
        CLOSE_UP_FOV = b.comment("The close-up camera's preferred vertical field of view, in degrees. Narrow windows widen it as needed.")
                .defineInRange("closeUpFov", 44, 35, 55);
        GUIDANCE = b.comment("How much the working view says in words.",
                "MINIMAL: the apparatus, the hovered part's short label, refusals and urgent warnings.",
                "GUIDED: also the one useful next action and the first-use demonstrations.",
                "DETAILED: also numbers, the full control help and diagnostics.",
                "AUTO (the default): DETAILED if numericDisplay is on, otherwise GUIDED.").defineEnum("guidance", Guidance.AUTO);
        HANDS = b.comment("ARMS draws your translucent, vanilla-sized arm gripping the tool or vessel; TOOLS_ONLY draws only the tool.")
                .defineEnum("hands", HandStyle.ARMS);
        ARM_OPACITY = b.comment("Workshop hand opacity, 0 (hidden) to 1 (opaque skin). Translucent arms fade toward the shoulder; sleeves use a lighter overlay.",
                "Only affects the arms drawn at brewing stations; arm size and skin proportions always match vanilla.")
                .defineInRange("armOpacity", 0.45, 0.0, 1.0);
        MIRROR_TOOLS = b.comment("Left-handed presentation: rests and held tools are drawn mirrored. The parts you act on stay the same.")
                .define("mirrorTools", false);
        PART_HINTS = b.comment("Show the short action label beside the part under the cursor or the selected part.").define("partHints", true);
        TUTORIAL_STAGE = b.comment("First-use demonstrations seen so far (0 = none; 3 = tool, burner and dose all shown).")
                .defineInRange("tutorialStage", 0, 0, 3);
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
        PINNED_HUD_ANCHOR = b.comment("Anchor for the pinned Notebook reminder.").defineEnum("pinnedHudAnchor", HudAnchor.RIGHT);
        PINNED_HUD_Y_OFFSET = b.comment("Vertical offset for the pinned Notebook reminder.").defineInRange("pinnedHudYOffset", 0, -120, 120);
        PINNED_HUD_COMPACT = b.comment("Show only item icons and counts in the pinned Notebook reminder.").define("pinnedHudCompact", false);
        PINNED_HUD_HIDE_SCREEN = b.comment("Hide the pinned Notebook reminder while a screen is open.").define("pinnedHudHideWhileScreenOpen", true);
        INSPECTION_HINTS = b.comment("Show a concise station status hint when looking at a station without Jade.").define("inspectionHints", true);
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

    /**
     * The camera style in effect (handoff 5.5 migration): an explicit choice wins; AUTO keeps an existing player's
     * choice from before the close-up camera (DIRECT stays at the eyes, reduced motion gets the cut without travel).
     */
    public static CameraStyle cameraStyle() {
        CameraStyle style = get(CAMERA_STYLE);
        if (style != CameraStyle.AUTO) return style;
        if (get(CAMERA_MODE) == CameraMode.DIRECT) return CameraStyle.EYE;
        return get(REDUCED_MOTION) ? CameraStyle.INSTANT : CameraStyle.CLOSE_UP;
    }

    /** The guidance level in effect: an explicit choice, or DETAILED for players who asked for numbers, else GUIDED. */
    public static Guidance guidance() {
        Guidance level = get(GUIDANCE);
        if (level != Guidance.AUTO) return level;
        return get(NUMERIC_DISPLAY) ? Guidance.DETAILED : Guidance.GUIDED;
    }

    private ClientConfig() {}
}
