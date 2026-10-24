package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.potion.EffectPolicies;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Config events, which Forge posts on the mod bus (spec 23.1). A server config edit can change plan limits, effect
 * ranges and the brewing mode, so the caches built from them are rebuilt on the server thread (the file watcher posts
 * from its own). Running jobs carry on: each one snapshots its plan, parameters and caps when it starts, so no setting
 * invalidates it (spec 23.1 seals only on changes that would).
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ConfigEvents {
    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (!ServerConfig.is(event.getConfig())) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            EffectPolicies.invalidate();
            PlanIndex.invalidate();
            return;
        }
        server.execute(() -> {
            EffectPolicies.invalidate();
            PlanIndex.invalidate();
        });
    }

    private ConfigEvents() {}
}
