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

    /** Supported catalog plans from this input and their costs for {@code servings}. Server thread only. */
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
