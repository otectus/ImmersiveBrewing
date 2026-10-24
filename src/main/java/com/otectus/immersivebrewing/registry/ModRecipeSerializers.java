package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.recipe.BrewingTippedArrowRecipe;
import com.otectus.immersivebrewing.recipe.DistillationRecipe;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.ReclamationRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<SimpleCraftingRecipeSerializer<BrewingTippedArrowRecipe>> TIPPED_ARROW =
            RECIPE_SERIALIZERS.register("tipped_arrow", () -> new SimpleCraftingRecipeSerializer<>(BrewingTippedArrowRecipe::new));

    public static final RegistryObject<InfusionRecipe.Serializer> INFUSION = RECIPE_SERIALIZERS.register("infusion", InfusionRecipe.Serializer::new);
    public static final RegistryObject<DistillationRecipe.Serializer> DISTILLATION = RECIPE_SERIALIZERS.register("distillation", DistillationRecipe.Serializer::new);
    public static final RegistryObject<ReclamationRecipe.Serializer> RECLAMATION = RECIPE_SERIALIZERS.register("reclamation", ReclamationRecipe.Serializer::new);

    private ModRecipeSerializers() {}
}
