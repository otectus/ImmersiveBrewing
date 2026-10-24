package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.recipe.DistillationRecipe;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.ReclamationRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRecipeTypes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<RecipeType<InfusionRecipe>> INFUSION = register("infusion");
    public static final RegistryObject<RecipeType<DistillationRecipe>> DISTILLATION = register("distillation");
    public static final RegistryObject<RecipeType<ReclamationRecipe>> RECLAMATION = register("reclamation");

    private static <T extends Recipe<?>> RegistryObject<RecipeType<T>> register(String name) {
        return RECIPE_TYPES.register(name, () -> RecipeType.simple(ImmersiveBrewing.id(name)));
    }

    private ModRecipeTypes() {}
}
