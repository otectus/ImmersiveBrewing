package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonObject;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.CommonConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Recipe conditions for the optional sister-mod paths, read while datapacks load (so they use the COMMON config).
 * <ul>
 * <li>{@code immersive_brewing:smithing_fallback}: true when the iron paddle is not smithed (Immersive Smithing absent or
 * its integration switched off), so the crafting recipe is the fallback (spec 17).</li>
 * <li>{@code immersive_brewing:smithing_active}: the opposite, guarding the smithing recipe for the iron paddle.</li>
 * <li>{@code immersive_brewing:tanning_enabled}: true when Immersive Tanning is present and its integration is on.</li>
 * </ul>
 */
public final class ModConditions {

    public record Simple(ResourceLocation id, java.util.function.BooleanSupplier test) implements ICondition {
        @Override
        public ResourceLocation getID() {
            return id;
        }

        @Override
        public boolean test(IContext context) {
            return test.getAsBoolean();
        }
    }

    private record Serializer(ResourceLocation id, Simple instance) implements IConditionSerializer<Simple> {
        @Override
        public void write(JsonObject json, Simple value) {}

        @Override
        public Simple read(JsonObject json) {
            return instance;
        }

        @Override
        public ResourceLocation getID() {
            return id;
        }
    }

    public static final ResourceLocation SMITHING_FALLBACK = ImmersiveBrewing.id("smithing_fallback");
    public static final ResourceLocation SMITHING_ACTIVE = ImmersiveBrewing.id("smithing_active");
    public static final ResourceLocation TANNING_ENABLED = ImmersiveBrewing.id("tanning_enabled");

    public static boolean smithingActive() {
        return ModList.get().isLoaded("immersive_smithing") && CommonConfig.get(CommonConfig.SMITHING_INTEGRATION);
    }

    public static boolean tanningActive() {
        return ModList.get().isLoaded("immersive_tanning") && CommonConfig.get(CommonConfig.TANNING_INTEGRATION);
    }

    public static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ForgeRegistries.Keys.RECIPE_SERIALIZERS)) return;
        CraftingHelper.register(new Serializer(SMITHING_FALLBACK, new Simple(SMITHING_FALLBACK, () -> !smithingActive())));
        CraftingHelper.register(new Serializer(SMITHING_ACTIVE, new Simple(SMITHING_ACTIVE, ModConditions::smithingActive)));
        CraftingHelper.register(new Serializer(TANNING_ENABLED, new Simple(TANNING_ENABLED, ModConditions::tanningActive)));
    }

    private ModConditions() {}
}
