package com.otectus.immersivebrewing.mixin;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Splash and lingering bottles, thrown by players or dispensers (spec 12.2).
 * <ul>
 * <li>{@code onHit} reads {@code getMobEffects(stack)} once and passes the list to {@code applySplash}, which applies the
 * vanilla distance attenuation; the redirect supplies the effective list, so attenuation applies once to the
 * quality-resolved duration.</li>
 * <li>{@code makeAreaOfEffectCloud} builds the cloud from the potion id; the cloud is tagged with the bottle's metadata
 * just before it is added, and {@link AreaEffectCloudMixin} resolves it when the cloud applies.</li>
 * </ul>
 * Regression tests: PotionPathGameTests#splash*, #lingering*, #dispenser*.
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionMixin {
    @Redirect(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/alchemy/PotionUtils;getMobEffects(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;"))
    private List<MobEffectInstance> immersiveBrewing$effectiveEffects(ItemStack stack) {
        return PotionContentsService.effectiveEffects(stack);
    }

    @ModifyArg(method = "makeAreaOfEffectCloud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private Entity immersiveBrewing$tagCloud(Entity cloud) {
        PotionContentsService.tagEntity(cloud, ((ThrownPotion) (Object) this).getItem());
        return cloud;
    }
}
