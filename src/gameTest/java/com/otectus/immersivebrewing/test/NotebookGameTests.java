package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Milestone 4 (spec 10, 25.1): the Notebook's player data stays bounded. Favourites are capped, unique and validated
 * against real plan keys and keep only an item and potion id for display; the preferred batch size is clamped; the
 * Notebook is granted once.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NotebookGameTests {
    private static ListTag favorites(FakePlayer player) {
        return PlayerGuide.data(player).getList("favorites", Tag.TAG_COMPOUND);
    }

    @GameTest(template = "empty")
    public static void favoritesStayBounded(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Reader");
        PlanIndex index = PlanIndex.get(h.getLevel().getServer().getRecipeManager());
        List<String> keys = new ArrayList<>();
        for (BrewPlan plan : index.plansFrom(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD))) {
            String key = plan.key().serialize();
            if (!keys.contains(key)) keys.add(key);
        }
        h.assertTrue(keys.size() > PlayerGuide.MAX_FAVORITES, "enough distinct plans to overflow: " + keys.size());
        ItemStack display = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        display.getOrCreateTag().put("junk", new CompoundTag());
        display.getOrCreateTag().putString("huge", "x".repeat(10_000));
        for (String key : keys) PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, key, display, 0));
        ListTag list = favorites(player);
        h.assertTrue(list.size() == PlayerGuide.MAX_FAVORITES, "capped at " + PlayerGuide.MAX_FAVORITES + ": " + list.size());
        h.assertTrue(list.getCompound(list.size() - 1).getString("key").equals(keys.get(keys.size() - 1)), "the newest kept, the oldest dropped");
        CompoundTag shown = list.getCompound(0).getCompound("output").getCompound("tag");
        h.assertTrue(!shown.contains("junk") && !shown.contains("huge") && shown.contains("Potion"), "only the item and potion id are kept: " + shown);

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, keys.get(keys.size() - 1), display, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES, "a duplicate is ignored");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.REMOVE_FAVORITE, keys.get(keys.size() - 1), ItemStack.EMPTY, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES - 1, "removed");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, "not a plan key", display, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES - 1, "a malformed key is refused");

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SERVINGS, "", ItemStack.EMPTY, 99));
        h.assertTrue(PlayerGuide.data(player).getByte("servings") == 6, "servings clamp to six");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SERVINGS, "", ItemStack.EMPTY, -4));
        h.assertTrue(PlayerGuide.data(player).getByte("servings") == 1, "and to one");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theNotebookIsGrantedOnce(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Newcomer");
        PlayerGuide.grantNotebookOnce(player);
        PlayerGuide.grantNotebookOnce(player);
        int books = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(ModItems.BREWERS_NOTEBOOK.get())) books += s.getCount();
        }
        h.assertTrue(books == 1, "exactly one Notebook: " + books);
        h.assertTrue(PlayerGuide.data(player).getBoolean("granted"), "the grant is remembered");
        h.succeed();
    }

    private NotebookGameTests() {}
}
