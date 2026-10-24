package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Drinking (spec 12.2): {@code PotionItem.finishUsingItem} reads {@code PotionUtils.getMobEffects(stack)} once, on the
 * server, and applies each effect. The redirect returns the effective list instead: identical for untagged potions,
 * scaled durations for graded ones, instant effects untouched (applied once by vanilla). Regression test:
 * PotionPathGameTests#drinking*.
 */
@Mixin(PotionItem.class)
public abstract class PotionItemMixin {
    @Redirect(method = "finishUsingItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/alchemy/PotionUtils;getMobEffects(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;"))
    private List<MobEffectInstance> immersiveBrewing$effectiveEffects(ItemStack stack) {
        return PotionContentsService.effectiveEffects(stack);
    }
}
