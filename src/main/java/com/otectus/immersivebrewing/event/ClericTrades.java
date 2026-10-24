package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Optional cleric trades (spec 23.4), off by default ({@code enableVillagerTrades}; read when the server starts). Clerics
 * sell the Notebook, an ordinary reagent and an ordinary potion: nothing they sell carries brewing data, and they buy
 * nothing this mod makes, so purchased stock can not feed a profitable salvage loop.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class ClericTrades {
    @SubscribeEvent
    public static void onTrades(VillagerTradesEvent event) {
        if (event.getType() != VillagerProfession.CLERIC || !ServerConfig.get(ServerConfig.ENABLE_VILLAGER_TRADES)) return;
        event.getTrades().get(1).add((trader, random) -> new MerchantOffer(new ItemStack(Items.EMERALD, 4),
                new ItemStack(ModItems.BREWERS_NOTEBOOK.get()), 4, 5, 0.05F));
        event.getTrades().get(2).add((trader, random) -> new MerchantOffer(new ItemStack(Items.EMERALD, 2),
                new ItemStack(Items.NETHER_WART, 3), 12, 5, 0.05F));
        event.getTrades().get(3).add((trader, random) -> new MerchantOffer(new ItemStack(Items.EMERALD, 5),
                PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS), 4, 10, 0.05F));
    }

    private ClericTrades() {}
}
