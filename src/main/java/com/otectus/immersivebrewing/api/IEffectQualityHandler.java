package com.otectus.immersivebrewing.api;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;

/**
 * Lets another mod declare how quality applies to its own potion-like items (spec 16.7). Without a handler, items other
 * than the vanilla potion, splash, lingering and tipped-arrow items are metadata-only. A handler must return fresh
 * effect instances and must be idempotent: the same stack always resolves to the same list.
 */
public interface IEffectQualityHandler {
    /**
     * Application paths the handler resolves itself; the caller still applies vanilla conversions on those paths. API
     * version 1 consults handlers on {@link #DRINK} and {@link #SPLASH}, where the stack itself is applied; lingering
     * clouds and arrows keep only the potion, so on {@link #LINGERING} and {@link #ARROW} such items stay metadata-only.
     */
    enum Path { DRINK, SPLASH, LINGERING, ARROW }

    boolean handles(ItemStack stack);

    EnumSet<Path> paths();

    /** Effective effects for {@code stack} at {@code quality}; must not mutate the stack or any shared list. */
    List<MobEffectInstance> resolve(ItemStack stack, int quality);
}
