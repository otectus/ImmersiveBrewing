package com.otectus.immersivebrewing.mixin;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Use the actual Forge-composed drink duration for the first-person animation's denominator. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Redirect(method="applyEatTransform",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/ItemStack;getUseDuration()I"))
    private int immersiveBrewing$drinkDuration(ItemStack stack) {
        return com.otectus.immersivebrewing.client.ClientDrinkTiming.duration(stack);
    }
}
