package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.batch.LedgerCodec;
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
        for (int i = 0; i < PlayerGuide.MAX_FAVORITES; i++) {
            PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, keys.get(i), display, 0));
        }
        String first = keys.get(0);
        String overflow = keys.get(PlayerGuide.MAX_FAVORITES);
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, overflow, display, 0));
        ListTag list = favorites(player);
        h.assertTrue(list.size() == PlayerGuide.MAX_FAVORITES, "capped at " + PlayerGuide.MAX_FAVORITES + ": " + list.size());
        h.assertTrue(list.getCompound(0).getString("key").equals(first), "capacity never silently evicts the oldest");
        h.assertTrue(PlayerGuide.data(player).getCompound("pendingFavorite").getString("key").equals(overflow), "overflow waits for explicit replacement");
        CompoundTag shown = list.getCompound(0).getCompound("output").getCompound("tag");
        h.assertTrue(!shown.contains("junk") && !shown.contains("huge") && shown.contains("Potion"), "only the item and potion id are kept: " + shown);

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.REPLACE_FAVORITE, overflow, display, 0));
        h.assertTrue(favorites(player).getCompound(0).getString("key").equals(overflow), "the selected slot is explicitly replaced");
        h.assertTrue(!PlayerGuide.data(player).contains("pendingFavorite"), "replacement clears the pending choice");

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, overflow, display, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES, "a duplicate is ignored");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.REMOVE_FAVORITE, overflow, ItemStack.EMPTY, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES - 1, "removed");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.ADD_FAVORITE, "not a plan key", display, 0));
        h.assertTrue(favorites(player).size() == PlayerGuide.MAX_FAVORITES - 1, "a malformed key is refused");

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SERVINGS, "", ItemStack.EMPTY, 99));
        h.assertTrue(PlayerGuide.data(player).getByte("servings") == 6, "servings clamp to six");
        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SERVINGS, "", ItemStack.EMPTY, -4));
        h.assertTrue(PlayerGuide.data(player).getByte("servings") == 1, "and to one");

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.PIN_RECIPE, first, display, 6));
        CompoundTag pin = PlayerGuide.data(player).getCompound("pinned");
        h.assertTrue(pin.getInt("generation") == index.generation(), "pinned costs are bound to the resolving server generation");
        h.assertTrue(!pin.getList("costs", Tag.TAG_COMPOUND).isEmpty(), "server-resolved plan costs are persisted for the HUD");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void journalHistoryIsCompactBoundedAndClearable(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Journalist");
        PlanIndex index = PlanIndex.get(h.getLevel().getServer().getRecipeManager());
        BrewPlan plan = index.plansFrom(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD)).get(0);
        for (int i = 0; i < PlayerGuide.MAX_HISTORY + 5; i++) {
            CompoundTag result = new CompoundTag();
            result.putString("kind", "brewing");
            result.putString("plan", plan.key().serialize());
            result.put("input", LedgerCodec.saveStack(plan.input()));
            result.put("output", LedgerCodec.saveStack(plan.output()));
            result.putInt("count", 3);
            result.putInt("Q", i);
            result.putInt("E", 50);
            result.putInt("C", i);
            result.putDouble("thermal", 0.75);
            result.putDouble("circulation", 0.80);
            result.putDouble("dosing", 0.90);
            result.putFloat("assistTime", i % 2 == 0 ? 1F : 1.25F);
            result.putFloat("assistBand", 1F);
            result.putString("tip", "tip.immersive_brewing.test");
            result.put("phases", new ListTag()); // large/detail-only fields must not enter summaries
            PlayerGuide.recordResult(player, result);
        }
        ListTag history = PlayerGuide.data(player).getList("history", Tag.TAG_COMPOUND);
        h.assertTrue(history.size() == PlayerGuide.MAX_HISTORY, "history is bounded to " + PlayerGuide.MAX_HISTORY);
        CompoundTag newest = history.getCompound(0);
        h.assertTrue(newest.getInt("Q") == PlayerGuide.MAX_HISTORY + 4, "newest result is first");
        h.assertTrue(newest.getString("plan").equals(plan.key().serialize()), "stable recipe identity is retained");
        h.assertTrue(newest.contains("timestamp", Tag.TAG_LONG) && newest.contains("input") && newest.contains("output"), "timestamp and compact identities retained");
        h.assertTrue(!newest.contains("phases") && !newest.contains("additions"), "input recording and phase detail are omitted");

        PlayerGuide.apply(player, new NotebookPrefsC2S(NotebookPrefsC2S.Action.CLEAR_HISTORY, "", ItemStack.EMPTY, 0));
        h.assertTrue(PlayerGuide.data(player).getList("history", Tag.TAG_COMPOUND).isEmpty(), "visible clear action removes history");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void guideMigrationPreservesUnknownNewerData(GameTestHelper h) {
        CompoundTag old = new CompoundTag();
        old.putBoolean("granted", true);
        PlayerGuide.migrate(old);
        h.assertTrue(old.getInt("dataVersion") == PlayerGuide.DATA_VERSION, "known older data migrates");
        h.assertTrue(old.getByte("servings") == 3 && old.contains("history", Tag.TAG_LIST), "migration adds bounded journal defaults");
        h.assertTrue(old.getBoolean("granted"), "known fields survive migration");

        CompoundTag newer = new CompoundTag();
        newer.putInt("dataVersion", PlayerGuide.DATA_VERSION + 7);
        CompoundTag opaque = new CompoundTag();
        opaque.putString("future", "keep me");
        newer.put("opaque", opaque);
        CompoundTag before = newer.copy();
        PlayerGuide.migrate(newer);
        h.assertTrue(newer.equals(before), "unknown newer payload is byte-for-byte semantically preserved: " + newer);
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
