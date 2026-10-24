package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.alchemy.Potion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Lingering clouds (spec 12.2): {@code AreaEffectCloud.tick} maps {@code potion.getEffects()} to a quarter duration and
 * appends its custom effects verbatim. The redirect substitutes the quality-resolved registered effects for a cloud
 * tagged by {@link ThrownPotionMixin}, so the vanilla quarter applies exactly once; untagged clouds (dragon's breath,
 * other mods) read the potion's own list. The cloud never holds a second active base list. Regression test:
 * PotionPathGameTests#lingering*.
 */
@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/alchemy/Potion;getEffects()Ljava/util/List;"))
    private List<MobEffectInstance> immersiveBrewing$effectiveEffects(Potion potion) {
        return PotionContentsService.entityPotionEffects((Entity) (Object) this, potion);
    }
}
