package com.otectus.immersivebrewing.recipe.adapter;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.brewing.BrewingRecipe;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.brewing.IBrewingRecipe;

/**
 * One brewing registry query as the Forge adapter and discovery see it. The output is exactly
 * {@link BrewingRecipeRegistry#getOutput}; {@link Result#loose()} marks an answer from a plain Forge
 * {@link BrewingRecipe} whose declared input names potion contents (an input stack with NBT) that the queried bottle does
 * not have. A vanilla {@link Ingredient} compares items only, so such a recipe turns every other potion in that container
 * into its output as well: RPG Potions 1.2.0 declares its recipes from Awkward, yet a stand also makes Smoke from Water
 * or Swiftness and Charcoal. The Kettle brews what the recipe declares, which is also what JEI shows for it. A subclass
 * with its own {@code isInput} has decided its inputs itself and is never loose.
 */
public final class RegistryQuery {
    public record Result(ItemStack output, boolean loose) {
        static final Result NONE = new Result(ItemStack.EMPTY, false);

        public boolean found() {
            return !output.isEmpty();
        }
    }

    private static final ClassValue<Boolean> OWN_INPUT_TEST = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("isInput", ItemStack.class).getDeclaringClass() != BrewingRecipe.class;
            } catch (NoSuchMethodException e) {
                return true;
            }
        }
    };

    /** Queries count-1 stacks, as a brewing stand slot holds them. */
    public static Result query(ItemStack input, ItemStack ingredient) {
        ItemStack output = BrewingRecipeRegistry.getOutput(input, ingredient);
        if (output.isEmpty()) return Result.NONE;
        // The registry answers with its first recipe that has an output; find that recipe to judge the match.
        for (IBrewingRecipe recipe : BrewingRecipeRegistry.getRecipes()) {
            if (recipe.getOutput(input, ingredient).isEmpty()) continue;
            return new Result(output, recipe instanceof BrewingRecipe finite && loose(finite, input));
        }
        return new Result(output, false);
    }

    /** Whether a plain Forge recipe matched {@code input} by item alone, against the contents its declared input names. */
    static boolean loose(BrewingRecipe recipe, ItemStack input) {
        if (OWN_INPUT_TEST.get(recipe.getClass())) return false;
        Ingredient declared = recipe.getInput();
        if (!declared.isVanilla()) return false;
        boolean named = false;
        for (ItemStack stack : declared.getItems()) {
            if (!stack.is(input.getItem())) continue;
            CompoundTag wanted = stack.getTag();
            if (wanted == null || wanted.isEmpty()) return false;
            named = true;
            if (NbtUtils.compareNbt(wanted, input.getTag(), true)) return false;
        }
        return named;
    }

    private RegistryQuery() {}
}
