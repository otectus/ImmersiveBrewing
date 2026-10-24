package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tipped arrows (spec 12.2), shot by bows, crossbows or dispensers, all of which call {@code setEffectsFromItem}.
 * <ul>
 * <li>At the end of {@code setEffectsFromItem} the arrow takes the stack's metadata (or drops any it had).</li>
 * <li>{@code doPostHurtEffects} maps {@code potion.getEffects()} to an eighth of the duration; the redirect supplies the
 * quality-resolved list, so the native eighth applies once. Custom effects stay verbatim.</li>
 * <li>{@code getPickupItem} rebuilds the arrow stack; the metadata is restored onto it.</li>
 * </ul>
 * Regression tests: PotionPathGameTests#arrow*.
 */
@Mixin(Arrow.class)
public abstract class ArrowMixin {
    @Inject(method = "setEffectsFromItem", at = @At("TAIL"))
    private void immersiveBrewing$tagArrow(ItemStack stack, CallbackInfo ci) {
        PotionContentsService.tagEntity((Entity) (Object) this, stack);
    }

    @Redirect(method = "doPostHurtEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/Potion;getEffects()Ljava/util/List;"))
    private List<MobEffectInstance> immersiveBrewing$effectiveEffects(Potion potion) {
        return PotionContentsService.entityPotionEffects((Entity) (Object) this, potion);
    }

    @Inject(method = "getPickupItem", at = @At("RETURN"))
    private void immersiveBrewing$restoreMetadata(CallbackInfoReturnable<ItemStack> cir) {
        PotionContentsService.copyEntityMetadata((Entity) (Object) this, cir.getReturnValue());
    }
}
