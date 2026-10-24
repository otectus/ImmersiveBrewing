package com.otectus.immersivebrewing.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Integration switches. COMMON (not per world) because recipe conditions read them while datapacks load, before any
 * SERVER config exists.
 */
public final class CommonConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue SMITHING_INTEGRATION;
    public static final ForgeConfigSpec.BooleanValue TANNING_INTEGRATION;
    public static final ForgeConfigSpec.BooleanValue ENCHANTING_INTEGRATION;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Optional sister-mod integrations. Each applies only when the mod is installed.").push("integrations");
        SMITHING_INTEGRATION = b.comment("Immersive Smithing: the iron brewing paddle is smithed (its crafting recipe stays as the fallback when off or absent).")
                .define("smithing", true);
        TANNING_INTEGRATION = b.comment("Immersive Tanning: offer the leather carrying-strap recipe for the Batch Carrier.")
                .define("tanning", true);
        ENCHANTING_INTEGRATION = b.comment("Brewing paddles accept Unbreaking and Mending at enchanting tables (Immersive Enchanting supplies its ritual there).")
                .define("enchanting", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean get(ForgeConfigSpec.BooleanValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private CommonConfig() {}
}
