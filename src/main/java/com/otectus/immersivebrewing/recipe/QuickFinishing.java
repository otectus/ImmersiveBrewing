package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.registries.ForgeRegistries;

/** Container-only proof. A role label alone never authorizes quality preservation. */
public final class QuickFinishing {
    public static boolean containers(ItemStack before,ItemStack after) {
        return before.is(Items.POTION)&&after.is(Items.SPLASH_POTION) || before.is(Items.SPLASH_POTION)&&after.is(Items.LINGERING_POTION);
    }
    public static boolean vanillaWinner(ItemStack before,ItemStack reagent) {
        for(var recipe:BrewingRecipeRegistry.getRecipes()) if(!recipe.getOutput(before.copyWithCount(1),reagent.copyWithCount(1)).isEmpty())
            return recipe.getClass()==net.minecraftforge.common.brewing.VanillaBrewingRecipe.class;
        return false;
    }
    public static ItemStack preserve(ItemStack before,ItemStack reagent,ItemStack actual) {
        if(!containers(before,actual)||PotionUtils.getPotion(before)!=PotionUtils.getPotion(actual)) return actual;
        if(!java.util.Objects.equals(PotionContentsService.canonicalTag(before),PotionContentsService.canonicalTag(actual))
                && !vanillaWinner(PotionContentsService.canonical(before),reagent)) return actual;
        // Preserve foreign NBT and capabilities as well as every quality/provenance field, including newer schemas.
        CompoundTag tag=before.save(new CompoundTag());
        tag.putString("id",String.valueOf(ForgeRegistries.ITEMS.getKey(actual.getItem())));
        tag.putByte("Count",(byte)1);
        return ItemStack.of(tag);
    }
    public static boolean eligible(BrewPlan plan) {
        if(plan.steps().isEmpty()) return false;
        for(var s:plan.steps()) {
            if(s.spec().role()!=com.otectus.immersivebrewing.minigame.brewing.KettleSetup.Role.CONTAINER || !containers(s.before(),s.after())) return false;
            ItemStack output=preserve(s.before(),new ItemStack(s.reagent()),s.after());
            if(!java.util.Objects.equals(PotionContentsService.canonicalTag(s.before()),PotionContentsService.canonicalTag(output))) return false;
        }
        return true;
    }
    private QuickFinishing() {}
}
