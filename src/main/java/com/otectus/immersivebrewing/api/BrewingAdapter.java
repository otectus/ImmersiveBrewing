package com.otectus.immersivebrewing.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A source brewing system the Kettle can delegate one transformation step to (spec 15.2). The source system decides
 * the result; Immersive Brewing only reserves materials, runs the reaction game and records quality.
 *
 * <p>Contract: every method is called on the server thread. {@link #preview} works on copies and must not consume,
 * reroll or mutate anything; {@link #commit} performs the validated step exactly once for one serving and must return
 * what {@link #preview} returned for the same inputs, or the job is held for recovery. An adapter that can not keep that
 * promise can not support queued multi-step plans and should not be registered. Adapters resolve by explicit priority,
 * then id; an explicit recipe may name one. API version {@link ImmersiveBrewingAPI#VERSION}.
 */
public interface BrewingAdapter {

    /** The step's result for one serving, or why it is unsupported. */
    record Preview(ItemStack output, @Nullable String unsupportedReason) {
        public static Preview of(ItemStack output) {
            return new Preview(output, null);
        }

        public static Preview unsupported(String reason) {
            return new Preview(ItemStack.EMPTY, reason);
        }

        public boolean supported() {
            return unsupportedReason == null && !output.isEmpty();
        }
    }

    /**
     * Real batching semantics (spec 5.2): how many servings one reagent application covers, the most servings one
     * reaction may hold, how many reagents one application consumes, and whether identical servings share a reaction.
     */
    record BatchPolicy(int servingsPerReagent, int maxServings, int reagentsPerApplication, boolean sharedReaction) {
        public BatchPolicy {
            if (servingsPerReagent < 1 || maxServings < 1 || reagentsPerApplication < 1) throw new IllegalArgumentException("batch policy");
        }

        /** Reagents one step consumes for {@code servings}: {@code ceil(servings / servingsPerReagent) x reagentsPerApplication}. */
        public int reagentCost(int servings) {
            return applications(servings) * reagentsPerApplication;
        }

        /** Reagent applications (and fuel charges) one step needs. */
        public int applications(int servings) {
            return (servings + servingsPerReagent - 1) / servingsPerReagent;
        }
    }

    /** What quality may do to an adapter's output (spec 15.2 contentsPolicy). */
    enum ContentsPolicy {
        /** Effective effects and drinking time resolve through the effect policies (vanilla potion items only). */
        EFFECTS,
        /** Quality is recorded and shown; the output behaves exactly as its source made it. */
        METADATA_ONLY
    }

    ResourceLocation id();

    /** Contract version of this adapter; stored with every job so a changed adapter is detected on resume. */
    int version();

    /** Higher runs first among adapters that apply. */
    int priority();

    /** Cheap check, no allocation-heavy work: could this adapter handle this pair at all? */
    boolean applies(ItemStack input, ItemStack ingredient);

    Preview preview(ItemStack input, ItemStack ingredient);

    BatchPolicy batchPolicy(ItemStack input, ItemStack ingredient);

    ItemStack commit(ItemStack input, ItemStack ingredient);

    ContentsPolicy contentsPolicy(ItemStack output);
}
