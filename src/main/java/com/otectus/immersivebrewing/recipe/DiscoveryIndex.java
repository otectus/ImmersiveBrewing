package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.adapter.RegistryQuery;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.brewing.BrewingRecipe;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.brewing.IBrewingRecipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded discovery of what the brewing registry can actually do (spec 15.3), compared with the validated plans:
 * <ol>
 * <li>finite Forge {@link BrewingRecipe}s are enumerated from their public inputs and ingredients;</li>
 * <li>opaque recipes (vanilla mixing and arbitrary {@link IBrewingRecipe}s) are probed with every registered potion in
 * the three vanilla containers against every valid ingredient, up to {@code maxDiscoveryPairs} queries.</li>
 * </ol>
 * A transformation no validated plan step covers is reported as opaque/query-only: it stays usable through a contextual
 * Kettle query when its adapter supports it, but has no catalog entry. One whose input, ingredient or output a datapack
 * excludes is reported as excluded instead, and a loose match (a plain Forge recipe answering for a potion its declared
 * input does not name, {@link RegistryQuery}) as loose: both are deliberately not offered. Built on demand for the report
 * and the coverage GameTest, cached per recipe manager and data generation; never per tick or per tooltip.
 */
public final class DiscoveryIndex {
    /** One observed transformation. */
    public record Found(ItemStack input, Item ingredient, ItemStack output, boolean covered, boolean excluded, boolean finite, boolean loose) {}

    private static volatile DiscoveryIndex cached;

    private final RecipeManager recipes;
    private final int generation;
    private final int budget;
    private final List<Found> found = new ArrayList<>();
    private int queries;
    private boolean truncated;

    private DiscoveryIndex(RecipeManager recipes, int generation, int budget) {
        this.recipes = recipes;
        this.generation = generation;
        this.budget = budget;
    }

    public static DiscoveryIndex get(RecipeManager recipes) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        DiscoveryIndex current = cached;
        if (current != null && current.recipes == recipes && current.generation == defs.generation()) return current;
        DiscoveryIndex index = new DiscoveryIndex(recipes, defs.generation(), ServerConfig.get(ServerConfig.MAX_DISCOVERY_PAIRS));
        index.build(PlanIndex.get(recipes));
        cached = index;
        return index;
    }

    /**
     * A fresh, uncached discovery with its own query budget: a large modded registry needs far more than the default to
     * be probed completely (the coverage GameTests use this; the server never does).
     */
    public static DiscoveryIndex withBudget(RecipeManager recipes, int budget) {
        DiscoveryIndex index = new DiscoveryIndex(recipes, BrewingDefinitions.server().generation(), budget);
        index.build(PlanIndex.get(recipes));
        return index;
    }

    public List<Found> found() {
        return found;
    }

    public int queries() {
        return queries;
    }

    public boolean truncated() {
        return truncated;
    }

    /** Transformations neither covered by a plan, excluded by a datapack nor loose. */
    public long uncovered() {
        return found.stream().filter(f -> !f.covered() && !f.excluded() && !f.loose()).count();
    }

    public long loose() {
        return found.stream().filter(Found::loose).count();
    }

    public long excluded() {
        return found.stream().filter(Found::excluded).count();
    }

    private void build(PlanIndex plans) {
        Set<String> covered = coveredSteps(plans);
        Set<String> seen = new HashSet<>();
        // 1. Finite Forge recipes: their inputs and ingredients are public.
        for (IBrewingRecipe recipe : BrewingRecipeRegistry.getRecipes()) {
            if (!(recipe instanceof BrewingRecipe finite)) continue;
            for (ItemStack input : finite.getInput().getItems()) {
                for (ItemStack ingredient : finite.getIngredient().getItems()) {
                    if (queries >= budget) {
                        truncated = true;
                        return;
                    }
                    probe(input, ingredient.getItem(), covered, seen, true);
                }
            }
        }
        // 2. Opaque recipes: probe registered potions in the vanilla containers with every valid ingredient.
        List<Item> ingredients = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (BrewingRecipeRegistry.isValidIngredient(new ItemStack(item))) ingredients.add(item);
        }
        for (Item container : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION)) {
            for (Potion potion : ForgeRegistries.POTIONS) {
                ItemStack input = PotionUtils.setPotion(new ItemStack(container), potion);
                for (Item ingredient : ingredients) {
                    if (queries >= budget) {
                        truncated = true;
                        return;
                    }
                    probe(input, ingredient, covered, seen, false);
                }
            }
        }
    }

    private void probe(ItemStack input, Item ingredient, Set<String> covered, Set<String> seen, boolean finite) {
        ItemStack one = input.copyWithCount(1);
        String key = PotionContentsService.fingerprint(one) + "|" + ForgeRegistries.ITEMS.getKey(ingredient);
        if (!seen.add(key)) return;
        queries++;
        RegistryQuery.Result result = RegistryQuery.query(one, new ItemStack(ingredient));
        ItemStack output = result.output();
        if (output.isEmpty() || PotionContentsService.sameCanonical(output, one)) return;
        var exclusions = BrewingDefinitions.server().exclusions();
        boolean excluded = exclusions.excludesItem(one.getItem()) || exclusions.excludesItem(ingredient) || exclusions.excludesItem(output.getItem());
        found.add(new Found(one, ingredient, output.copyWithCount(1), covered.contains(key), excluded, finite, result.loose()));
    }

    /** (input fingerprint, ingredient) of every validated plan step, including container conversions. */
    private static Set<String> coveredSteps(PlanIndex plans) {
        Set<String> out = new LinkedHashSet<>();
        for (Item container : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION)) {
            for (Potion potion : ForgeRegistries.POTIONS) {
                ItemStack input = PotionUtils.setPotion(new ItemStack(container), potion);
                for (BrewPlan plan : plans.plansFrom(input)) {
                    BrewPlan.Step first = plan.steps().get(0);
                    out.add(PotionContentsService.fingerprint(first.before()) + "|" + ForgeRegistries.ITEMS.getKey(first.reagent()));
                }
            }
        }
        return out;
    }
}
