package com.otectus.immersivebrewing.api;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The small, versioned public API (spec 16.7). Stable across 1.x: adapters, effect-quality handlers, metadata and plan
 * queries, and the events in {@code api.event}. Everything else in this mod is internal.
 *
 * <p>Registration: call the {@code register*} methods during common setup, or send IMC to {@code immersive_brewing} with
 * method {@link #IMC_ADAPTER} or {@link #IMC_EFFECT_HANDLER}: {@code InterModComms.sendTo("immersive_brewing",
 * "register_adapter", () -> adapter)}. Adapters freeze when IMC processing ends.
 */
public final class ImmersiveBrewingAPI {
    public static final int VERSION = 1;
    public static final String IMC_ADAPTER = "register_adapter";
    public static final String IMC_EFFECT_HANDLER = "register_effect_handler";

    private static final List<IEffectQualityHandler> HANDLERS = new CopyOnWriteArrayList<>();

    /** Registers a brewing adapter. Returns false if its id is already owned (logged as a conflict). */
    public static boolean registerAdapter(BrewingAdapter adapter) {
        return AdapterRegistry.register(adapter);
    }

    public static void registerEffectHandler(IEffectQualityHandler handler) {
        HANDLERS.add(handler);
    }

    public static List<IEffectQualityHandler> effectHandlers() {
        return HANDLERS;
    }

    /** Immutable brewing metadata of a stack, if it has any. */
    public static Optional<BrewingMetadataView> metadata(ItemStack stack) {
        return BrewingMetadataView.of(stack);
    }

    /** Effective quality: the recorded score, or 50 for an ordinary potion. */
    public static int quality(ItemStack stack) {
        return PotionContentsService.quality(stack);
    }

    /** The stack without this mod's compound: what brewing queries and fingerprints use. */
    public static ItemStack canonical(ItemStack stack) {
        return PotionContentsService.canonical(stack);
    }

    /** Legacy bounded list (at most 512 routes), retained unchanged for API v1 callers. Use catalog for discovery. Server thread only. */
    public static List<PlanQuery> plans(MinecraftServer server, ItemStack input, int servings) {
        List<PlanQuery> out = new ArrayList<>();
        for (BrewPlan plan : PlanIndex.get(server.getRecipeManager()).plansFrom(input)) {
            List<Item> items = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (BrewPlan.Cost c : plan.costs(servings)) {
                items.add(c.item());
                counts.add(c.count());
            }
            out.add(new PlanQuery(plan.key().serialize(), plan.input().copy(), plan.output().copy(), List.copyOf(items), List.copyOf(counts),
                    plan.fuelCharges(servings), plan.remainders(servings), plan.contextual()));
        }
        return out;
    }

    /** Incremental search by stable output/reagent/source ids, filtering before 24-route pages. Server thread only.
     * Each call advances at most 64 graph operations; callers must schedule calls, not spin until complete. */
    public static CatalogPage catalog(MinecraftServer server, ItemStack input, int servings, String search, int page, int workBudget) {
        if (!server.isSameThread()) throw new IllegalStateException("Catalog queries require the server thread");
        if (servings < 1 || servings > 6 || page < 0 || page > 4096 || search.length() > 128)
            throw new IllegalArgumentException("catalog query bounds");
        var index = PlanIndex.get(server.getRecipeManager());
        var catalog = index.catalog(input);
        catalog.advance(Math.max(0, Math.min(64, workBudget)));
        String query = search.toLowerCase(java.util.Locale.ROOT);
        var matches = catalog.plans().stream().filter(plan -> query.isBlank()
                || String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(plan.output().getItem())).contains(query)
                || String.valueOf(net.minecraftforge.registries.ForgeRegistries.POTIONS.getKey(net.minecraft.world.item.alchemy.PotionUtils.getPotion(plan.output()))).contains(query)
                || plan.steps().stream().anyMatch(step -> step.source().toString().contains(query)
                    || String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(step.reagent())).contains(query))).toList();
        int first = Math.min(matches.size(), page * 24);
        return new CatalogPage(index.generation(), matches.subList(first, Math.min(matches.size(), first + 24)).stream()
                .map(plan -> query(plan, servings)).toList(), matches.size(), !catalog.done(), catalog.budgetLimited(), catalog.omittedAlternatives());
    }

    /** Resolves the exact route against current recipes and adapters, independent of discovery page limits. */
    public static Optional<PlanQuery> resolve(MinecraftServer server, String key, ItemStack input, List<ItemStack> tray, int servings) {
        if (!server.isSameThread()) throw new IllegalStateException("Plan resolution requires the server thread");
        var parsed = com.otectus.immersivebrewing.recipe.PlanKey.parse(key);
        if (parsed == null || servings < 1 || servings > 6 || tray.size() > 8) return Optional.empty();
        BrewPlan plan = PlanIndex.get(server.getRecipeManager()).resolve(parsed, input, tray);
        return plan == null || servings > plan.servingLimit() ? Optional.empty() : Optional.of(query(plan, servings));
    }

    private static PlanQuery query(BrewPlan plan, int servings) {
        var costs = plan.costs(servings);
        return new PlanQuery(plan.key().serialize(), plan.input().copy(), plan.output().copy(), costs.stream().map(BrewPlan.Cost::item).toList(),
                costs.stream().map(BrewPlan.Cost::count).toList(), plan.fuelCharges(servings), plan.remainders(servings), plan.contextual());
    }

    public static void processIMC(InterModProcessEvent event) {
        InterModComms.getMessages(ImmersiveBrewing.MOD_ID).forEach(msg -> {
            try {
                // The usual form is sendTo(id, method, () -> instance); a Supplier of the instance is accepted too.
                Object value = msg.messageSupplier().get();
                if (value instanceof Supplier<?> supplier) value = supplier.get();
                if (IMC_ADAPTER.equals(msg.method()) && value instanceof BrewingAdapter adapter) registerAdapter(adapter);
                else if (IMC_EFFECT_HANDLER.equals(msg.method()) && value instanceof IEffectQualityHandler handler) registerEffectHandler(handler);
                else ImmersiveBrewing.LOGGER.warn("Immersive Brewing: ignored IMC '{}' from {}", msg.method(), msg.senderModId());
            } catch (RuntimeException e) {
                ImmersiveBrewing.LOGGER.error("Immersive Brewing: IMC '{}' from {} failed", msg.method(), msg.senderModId(), e);
            }
        });
    }

    private ImmersiveBrewingAPI() {}
}
