package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.event.BrewingStandGuard;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replacement mode (spec 14): {@code BrewingStandBlockEntity.serverTick} consumes fuel and starts the 400-tick timer as
 * soon as the private {@code isBrewable(items)} is true, and {@code isBrewable} is called from nowhere else. Answering
 * false for a covered transformation stops the stand before it spends fuel or time; {@code PotionBrewEvent.Pre}
 * cancellation in {@link BrewingStandGuard} is only the backstop. In the default COEXIST mode this never answers.
 * Regression test: ReplacementModeGameTests.
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin {
    @Inject(method = "isBrewable", at = @At("HEAD"), cancellable = true)
    private static void immersiveBrewing$guardCovered(NonNullList<ItemStack> items, CallbackInfoReturnable<Boolean> cir) {
        if (BrewingStandGuard.blocks(items)) cir.setReturnValue(false);
    }
}
