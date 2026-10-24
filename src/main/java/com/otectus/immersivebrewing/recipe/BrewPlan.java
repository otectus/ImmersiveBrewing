package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.api.BrewingAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resolved, validated transformation path from one canonical input to one canonical output (spec 13, 15): every
 * step's adapter, reagent and the exact canonical stack after it. Costs follow each step's batch policy. Immutable.
 *
 * @param contextual true for an opaque adapter query offered only because of what is in the tray (spec 15.3)
 */
public record BrewPlan(PlanKey key, ItemStack input, List<Step> steps, ItemStack output, ResourceLocation brewProfile,
                       int maxServings, boolean contextual) {

    /**
     * One step: where it came from (recipe id or the contextual adapter), its spec, the concrete reagent item, the
     * adapter, the batch policy and the canonical stacks before and after.
     */
    public record Step(ResourceLocation source, PlanStep spec, Item reagent, ResourceLocation adapter, int adapterVersion,
                       BrewingAdapter.BatchPolicy batch, ItemStack before, ItemStack after) {}

    /** One reagent line of the cost preview. */
    public record Cost(Item item, int count, @Nullable ResourceLocation preparationProfile) {}

    public BrewPlan {
        steps = List.copyOf(steps);
    }

    /** Reagents per item for {@code servings}, in step order (spec 5.2). */
    public List<Cost> costs(int servings) {
        Map<Item, int[]> counts = new LinkedHashMap<>();
        Map<Item, ResourceLocation> profiles = new LinkedHashMap<>();
        for (Step s : steps) {
            counts.computeIfAbsent(s.reagent(), k -> new int[1])[0] += s.batch().reagentCost(servings);
            if (s.spec().preparationProfile() != null) profiles.putIfAbsent(s.reagent(), s.spec().preparationProfile());
        }
        List<Cost> out = new ArrayList<>();
        counts.forEach((item, n) -> out.add(new Cost(item, n[0], profiles.get(item))));
        return out;
    }

    /** Fuel charges: one per reagent application, every planned step (spec 5.2). */
    public int fuelCharges(int servings) {
        int total = 0;
        for (Step s : steps) total += s.batch().applications(servings);
        return total;
    }

    /** Containers returned by consumed reagents (dragon's breath returns its bottle), exactly once each. */
    public List<ItemStack> remainders(int servings) {
        List<ItemStack> out = new ArrayList<>();
        for (Step s : steps) {
            ItemStack reagent = new ItemStack(s.reagent());
            if (!reagent.hasCraftingRemainingItem()) continue;
            ItemStack remainder = reagent.getCraftingRemainingItem();
            if (remainder.isEmpty()) continue;
            int n = s.batch().reagentCost(servings);
            for (int i = 0; i < n; i++) out.add(remainder.copy());
        }
        return out;
    }

    /** The most servings every step allows. */
    public int servingLimit() {
        int limit = maxServings;
        for (Step s : steps) limit = Math.min(limit, s.batch().maxServings());
        return limit;
    }

    /** True when every step's adapter lets quality change the output's effects. */
    public boolean qualityAffectsEffects(BrewingAdapter.ContentsPolicy finalPolicy) {
        return finalPolicy == BrewingAdapter.ContentsPolicy.EFFECTS;
    }

    /** Index of the last step that creates or changes an effect, or -1 for base-only and modifier-only plans. */
    public int lastEffectStep() {
        for (int i = steps.size() - 1; i >= 0; i--) if (steps.get(i).spec().createsEffect()) return i;
        return -1;
    }
}
