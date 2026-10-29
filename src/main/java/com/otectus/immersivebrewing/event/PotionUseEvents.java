package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drinking time (spec 11.2). {@code LivingEntity.startUsingItem} asks Forge for the duration on both sides, so a
 * graded potion's use time is set here without a mixin. The baseline is whatever the event carries (32 ticks for
 * vanilla potions, or another mod's adjustment), shifted by {@code round(4 - 0.08Q)}. The first-person drinking
 * animation uses the same composed duration captured by ClientDrinkTiming.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class PotionUseEvents {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        int duration = event.getDuration();
        if (duration <= 0) return;
        int ticks = PotionContentsService.effectiveUseTicks(event.getItem(), duration);
        if (ticks != duration) event.setDuration(ticks);
    }

    private PotionUseEvents() {}
}
