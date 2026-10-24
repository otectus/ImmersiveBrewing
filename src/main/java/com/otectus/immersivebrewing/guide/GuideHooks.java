package com.otectus.immersivebrewing.guide;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/**
 * Common code's door to the client-only guide screens. {@code ClientSetup} installs the opener; on a dedicated server it
 * stays a no-op, so common classes never load client classes.
 */
public final class GuideHooks {
    private static volatile BiConsumer<ItemStack, InteractionHand> notebookOpener = (stack, hand) -> {};

    public static void install(BiConsumer<ItemStack, InteractionHand> opener) {
        notebookOpener = opener;
    }

    public static void openNotebook(ItemStack otherHand, InteractionHand hand) {
        notebookOpener.accept(otherHand, hand);
    }

    private GuideHooks() {}
}
