package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModRecipeSerializers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.TippedArrowRecipe;

/**
 * Vanilla's tipped-arrow recipe, unchanged, plus the lingering potion's brewing metadata copied onto the arrows
 * (spec 12.2). Vanilla's {@code assemble} builds a fresh stack from the potion id and custom effects only, which would
 * drop the metadata. This mod's data overrides {@code minecraft:tipped_arrow} with this serializer; a startup check
 * reports when another pack replaced the override (docs/ARCHITECTURE.md, ADR-1).
 */
public class BrewingTippedArrowRecipe extends TippedArrowRecipe {
    public BrewingTippedArrowRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
        ItemStack result = super.assemble(container, access);
        if (result.isEmpty()) return result;
        ItemStack potion = container.getItem(1 + container.getWidth());
        BrewingMetadata.read(potion).ifPresent(meta -> BrewingMetadata.write(result, meta));
        return result;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.TIPPED_ARROW.get();
    }
}
