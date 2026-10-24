package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.advancement.Milestone;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.advancements.Advancement;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Milestone 6 (spec 23.4, 25): the advancement tree loads whole, one advancement per milestone under one root, with no
 * rewards that could pay twice; and the optional cleric trades stay out of the game while switched off.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContentGameTests {
    @GameTest(template = "empty")
    public static void theAdvancementTreeLoads(GameTestHelper h) {
        var advancements = h.getLevel().getServer().getAdvancements();
        Advancement root = advancements.getAdvancement(ImmersiveBrewing.id("root"));
        h.assertTrue(root != null && root.getParent() == null, "the root loaded");
        for (Milestone m : Milestone.values()) {
            Advancement a = advancements.getAdvancement(ImmersiveBrewing.id(m.id()));
            h.assertTrue(a != null, "advancement for " + m.id());
            h.assertTrue(a.getCriteria().containsKey(m.id()), m.id() + " is awarded by its milestone");
            h.assertTrue(a.getRewards() == net.minecraft.advancements.AdvancementRewards.EMPTY, m.id() + " pays nothing extra");
            Advancement up = a;
            while (up.getParent() != null) up = up.getParent();
            h.assertTrue(up == root, m.id() + " hangs under the root");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void clericTradesAreOffByDefault(GameTestHelper h) {
        h.assertTrue(!ServerConfig.get(ServerConfig.ENABLE_VILLAGER_TRADES), "enableVillagerTrades defaults to false");
        Villager villager = new Villager(EntityType.VILLAGER, h.getLevel());
        var levels = VillagerTrades.TRADES.get(VillagerProfession.CLERIC);
        h.assertTrue(levels != null, "clerics have trades");
        for (var listings : levels.values()) {
            for (VillagerTrades.ItemListing listing : listings) {
                MerchantOffer offer = listing.getOffer(villager, h.getLevel().getRandom());
                h.assertTrue(offer == null || !offer.getResult().is(ModItems.BREWERS_NOTEBOOK.get()), "no Notebook trade while switched off");
            }
        }
        villager.discard();
        h.succeed();
    }

    private ContentGameTests() {}
}
