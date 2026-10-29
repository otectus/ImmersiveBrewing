package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Observes the composed event duration once; animation never applies quality a second time. */
@Mod.EventBusSubscriber(modid=ImmersiveBrewing.MOD_ID,value=Dist.CLIENT)
public final class ClientDrinkTiming {
    private static ItemStack source=ItemStack.EMPTY;
    private static int duration;
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void start(LivingEntityUseItemEvent.Start event) {
        if(event.getEntity()!=Minecraft.getInstance().player)return;
        source=event.getItem().copy(); duration=event.getDuration();
    }
    public static int duration(ItemStack stack) {
        return stack.is(Items.POTION)&&BrewingMetadata.read(stack).isPresent()&&duration>0&&ItemStack.isSameItemSameTags(source,stack)
                ?duration:stack.getUseDuration();
    }
    private ClientDrinkTiming() {}
}
