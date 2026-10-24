package com.otectus.immersivebrewing.smoketest;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Opens the real JEI categories for the visual matrix without adding a production test hook. */
@JeiPlugin
public final class VisualJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(SmokeTestAgent.MOD_ID, "visual_review");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime value) { runtime = value; }

    static void show(String category) {
        var types = runtime.getRecipeManager().createRecipeCategoryLookup().get()
                .<mezz.jei.api.recipe.RecipeType<?>>map(c -> c.getRecipeType())
                .filter(t -> t.getUid().equals(new ResourceLocation("immersive_brewing", category))).toList();
        if (types.isEmpty()) throw new IllegalStateException("missing JEI category " + category);
        runtime.getRecipesGui().showTypes(types);
    }
}
