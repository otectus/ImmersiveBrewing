package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.BrewingMode;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.recipe.CoverageIndex;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.brewing.PotionBrewEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Replacement mode at the ordinary brewing stand (spec 14). In {@link BrewingMode#REPLACE_COVERED} a stand whose
 * ingredient and bottles form a transformation the Kettle covers is stopped before it spends fuel or time (through
 * {@code BrewingStandBlockEntityMixin}); {@link PotionBrewEvent.Pre} cancellation is the backstop at completion. A
 * cycle mixing covered and uncovered bottles passes through whole, as the spec's conservative exception requires.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class BrewingStandGuard {

    /** Stand slot layout: bottles 0..2, ingredient 3. */
    public static boolean blocks(NonNullList<ItemStack> items) {
        if (ServerConfig.brewingMode() != BrewingMode.REPLACE_COVERED) return false;
        if (items.size() < 4 || items.get(3).isEmpty()) return false;
        return coverage().coversStand(items.get(3), items.get(0), items.get(1), items.get(2));
    }

    /** The coverage of the current plan index (built lazily, then cached until recipes, data or config change). */
    private static CoverageIndex coverage() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) PlanIndex.get(server.getRecipeManager());
        return CoverageIndex.server();
    }

    /**
     * Explains a refused stand to whoever has it open (spec 14: explain the blocked operation in the UI), at most once per
     * second per player and only while the stand actually holds a covered transformation.
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
        if (ServerConfig.brewingMode() != BrewingMode.REPLACE_COVERED || !(player.containerMenu instanceof BrewingStandMenu menu)) return;
        NonNullList<ItemStack> items = NonNullList.withSize(4, ItemStack.EMPTY);
        for (int i = 0; i < 4 && i < menu.slots.size(); i++) items.set(i, menu.slots.get(i).getItem());
        if (blocks(items)) {
            ModNetwork.notice(player, Component.translatable("notice.immersive_brewing.stand_covered"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBrew(PotionBrewEvent.Pre event) {
        if (ServerConfig.brewingMode() != BrewingMode.REPLACE_COVERED) return;
        // The event carries a copy of the stand's whole item list: bottles 0..2, ingredient 3, fuel 4.
        if (event.getLength() < 4) return;
        if (CoverageIndex.server().coversStand(event.getItem(3), event.getItem(0), event.getItem(1), event.getItem(2))) {
            event.setCanceled(true);
        }
    }

    private BrewingStandGuard() {}
}
