package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.recipe.ModConditions;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/**
 * Milestone 5 (spec 17): each sister integration matches what is installed. These run in every compatibility
 * configuration ({@code -PnoSmithing}, {@code -PnoTanning}, both present) and assert whichever side applies: the iron
 * paddle has exactly one recipe (smithed or crafted), Smithing never treats a carrier or recovery batch as metal and its
 * quality changes paddle wear through its own hook, Tanning's leather unlocks only a presentation strap, and paddles
 * take Unbreaking and Mending only.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IntegrationGameTests {
    private static Optional<? extends Recipe<?>> recipe(GameTestHelper h, String path) {
        RecipeManager recipes = h.getLevel().getServer().getRecipeManager();
        return recipes.byKey(ImmersiveBrewing.id(path));
    }

    @GameTest(template = "empty")
    public static void theIronPaddleHasExactlyOneRecipe(GameTestHelper h) {
        boolean smithing = ModConditions.smithingActive();
        h.assertTrue(recipe(h, "iron_brewing_paddle").isPresent() == !smithing, "the crafting fallback exists only without Smithing");
        h.assertTrue(recipe(h, "smithing/iron_brewing_paddle").isPresent() == smithing, "the smithing recipe exists only with it");
        ImmersiveBrewing.LOGGER.info("[integration] smithing present={} active={}", ModList.get().isLoaded("immersive_smithing"), smithing);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void smithingNeverMeltsContainersAndGradesPaddleWear(GameTestHelper h) throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("immersive_smithing")) {
            h.succeed();
            return;
        }
        Class<?> api = Class.forName("com.otectus.immersivesmithing.api.ImmersiveSmithingAPI");
        var excluded = api.getMethod("isExcluded", ItemStack.class);
        h.assertTrue((Boolean) excluded.invoke(null, new ItemStack(ModItems.BATCH_CARRIER.get())), "a carrier is never smithing material");
        h.assertTrue((Boolean) excluded.invoke(null, new ItemStack(ModItems.RECOVERY_BATCH.get())), "nor a recovery batch");
        h.assertTrue(!(Boolean) excluded.invoke(null, new ItemStack(ModItems.IRON_BREWING_PADDLE.get())), "the iron paddle is ordinary smithed equipment");

        // Smithing's ItemStack.hurt hook scales wear by its durability quality; Brewing wears paddles through hurt().
        Class<?> quality = Class.forName("com.otectus.immersivesmithing.quality.QualityData");
        Object masterwork = quality.getConstructor(int.class, int.class, boolean.class).newInstance(100, 100, false);
        ItemStack smithed = new ItemStack(ModItems.IRON_BREWING_PADDLE.get());
        quality.getMethod("apply", ItemStack.class).invoke(masterwork, smithed);
        ItemStack plain = new ItemStack(ModItems.IRON_BREWING_PADDLE.get());
        RandomSource random = RandomSource.create(1);
        for (int i = 0; i < 24; i++) {
            plain.hurt(1, random, null);
            smithed.hurt(1, random, null);
        }
        h.assertTrue(plain.getDamageValue() == 24, "an unsmithed paddle wears one per use");
        h.assertTrue(smithed.getDamageValue() < plain.getDamageValue(), "a masterwork paddle wears less: " + smithed.getDamageValue());
        h.assertTrue(((Optional<?>) quality.getMethod("get", ItemStack.class).invoke(null, smithed)).isPresent(), "and keeps its Smithing metadata");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tanningLeatherOnlyChangesTheLook(GameTestHelper h) {
        boolean tanning = ModConditions.tanningActive();
        Optional<? extends Recipe<?>> strap = recipe(h, "tanning/strapped_batch_carrier");
        h.assertTrue(strap.isPresent() == tanning, "the strap recipe exists only with Tanning");
        if (ModList.get().isLoaded("immersive_tanning")) {
            Item binding = ForgeRegistries.ITEMS.getValue(new ResourceLocation("immersive_tanning", "binding_leather"));
            h.assertTrue(binding != null && new ItemStack(binding).is(TagKey.create(Registries.ITEM, new ResourceLocation("forge", "leather"))),
                    "Binding Leather is in the verified forge:leather tag");
        }
        if (strap.isPresent()) {
            ItemStack result = strap.get().getResultItem(h.getLevel().registryAccess());
            h.assertTrue(result.is(ModItems.BATCH_CARRIER.get()) && BatchCarrierItem.strapped(result), "a strapped Batch Carrier");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void paddlesTakeUnbreakingAndMendingOnly(GameTestHelper h) {
        ItemStack paddle = new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get());
        h.assertTrue(paddle.canApplyAtEnchantingTable(Enchantments.UNBREAKING) && paddle.canApplyAtEnchantingTable(Enchantments.MENDING),
                "Unbreaking and Mending apply");
        h.assertTrue(!paddle.canApplyAtEnchantingTable(Enchantments.SHARPNESS) && !paddle.canApplyAtEnchantingTable(Enchantments.BLOCK_EFFICIENCY),
                "nothing else does");
        ItemStack unbreaking = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.UNBREAKING, 2));
        ItemStack sharpness = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.SHARPNESS, 1));
        h.assertTrue(paddle.isBookEnchantable(unbreaking) && !paddle.isBookEnchantable(sharpness), "books follow the same rule");
        h.succeed();
    }

    private IntegrationGameTests() {}
}
