package com.otectus.immersivebrewing.smoketest;

import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Test-only external duration modifier. Exercises the real use lifecycle, never ships in the jar. */
@Mod.EventBusSubscriber(modid = SmokeTestAgent.MOD_ID)
public final class DrinkTimingFixture {
    public static volatile int serverDuration;
    @SubscribeEvent
    public static void modify(LivingEntityUseItemEvent.Start event) {
        if (event.getItem().hasTag() && event.getItem().getTag().getBoolean("IBSmokeDrink")) event.setDuration(24);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void observe(LivingEntityUseItemEvent.Start event) {
        if (!event.getEntity().level().isClientSide && event.getItem().hasTag()
                && event.getItem().getTag().getBoolean("IBSmokeDrink")) serverDuration = event.getDuration();
    }
    private DrinkTimingFixture() {}
}
