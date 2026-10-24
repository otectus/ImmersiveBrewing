package com.otectus.immersivebrewing.recipe.adapter;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.api.BrewingAdapter;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;

/**
 * The built-in adapter: delegates each step to Forge's {@link BrewingRecipeRegistry#getOutput}, which covers vanilla
 * mixing ({@code VanillaBrewingRecipe}) and every registered {@code IBrewingRecipe}. Queries use the canonical stack (this
 * mod's compound stripped) and count-1 copies, exactly as a brewing stand slot would hold them. Batching follows the
 * stand: one reagent per group of up to three bottles. A loose match (a plain Forge recipe answering for a potion its
 * declared input does not name, see {@link RegistryQuery}) is refused: the Kettle brews what a recipe declares.
 */
public final class ForgeRegistryAdapter implements BrewingAdapter {
    public static final ResourceLocation ID = ImmersiveBrewing.id("forge_registry");
    public static final int VERSION = 1;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean applies(ItemStack input, ItemStack ingredient) {
        return !input.isEmpty() && !ingredient.isEmpty() && BrewingRecipeRegistry.isValidInput(input)
                && BrewingRecipeRegistry.isValidIngredient(ingredient);
    }

    @Override
    public Preview preview(ItemStack input, ItemStack ingredient) {
        if (input.isEmpty() || ingredient.isEmpty()) return Preview.unsupported("plan.immersive_brewing.unsupported.empty");
        RegistryQuery.Result result = RegistryQuery.query(one(PotionContentsService.canonical(input)), one(ingredient));
        if (!result.found()) return Preview.unsupported("plan.immersive_brewing.unsupported.no_recipe");
        if (result.loose()) return Preview.unsupported("plan.immersive_brewing.unsupported.loose_input");
        ItemStack output = result.output();
        if (PotionContentsService.sameCanonical(output, input) && output.getCount() == 1) {
            return Preview.unsupported("plan.immersive_brewing.unsupported.no_change");
        }
        return Preview.of(output.copyWithCount(1));
    }

    @Override
    public BatchPolicy batchPolicy(ItemStack input, ItemStack ingredient) {
        return new BatchPolicy(ServerConfig.servingsPerVanillaReagent(), 6, 1, true);
    }

    @Override
    public ItemStack commit(ItemStack input, ItemStack ingredient) {
        RegistryQuery.Result result = RegistryQuery.query(one(PotionContentsService.canonical(input)), one(ingredient));
        return !result.found() || result.loose() ? ItemStack.EMPTY : result.output().copyWithCount(1);
    }

    @Override
    public ContentsPolicy contentsPolicy(ItemStack output) {
        return PotionContentsService.effectsSupported(output) ? ContentsPolicy.EFFECTS : ContentsPolicy.METADATA_ONLY;
    }

    private static ItemStack one(ItemStack stack) {
        return stack.copyWithCount(1);
    }
}
