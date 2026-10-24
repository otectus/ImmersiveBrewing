package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;

/**
 * Milestone 4 (spec 10): the Batch Carrier moves exact stacks (foreign tags and brewing data included) between hands
 * and stations, keeps whatever does not fit, holds no inventory-bearing item, exposes a narrow six-slot handler and can
 * never enter a recipe or a station slot itself.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CarrierGameTests {
    static ItemStack carrier(ItemStack... contents) {
        ItemStack carrier = new ItemStack(ModItems.BATCH_CARRIER.get());
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < CarrierContents.SLOTS; i++) list.add(i < contents.length ? contents[i] : ItemStack.EMPTY);
        CarrierContents.write(carrier, list);
        return carrier;
    }

    static ItemStack gradedSwiftness(String label) {
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        BrewingMetadata.write(stack, new BrewingMetadata(80, 84, 83, 0x7L, 0, null, "Jade", null, label, null));
        stack.getOrCreateTag().putString("other_mod_data", "kept");
        return stack;
    }

    @GameTest(template = "empty")
    public static void loadingAKettleMovesExactStacks(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        ItemStack a = gradedSwiftness("A");
        ItemStack b = gradedSwiftness("B");
        ItemStack held = carrier(a.copy(), b.copy(), new ItemStack(Items.GLASS_BOTTLE, 2), a.copy(), b.copy(), a.copy());
        FakePlayer player = TestSupport.player(h, "Porter");
        kettle.inventory().setStackInSlot(RACK + 4, TestSupport.water());
        kettle.inventory().setStackInSlot(RACK + 5, TestSupport.water());
        ItemStack after = TestSupport.use(player, kettle, held);
        h.assertTrue(after.is(ModItems.BATCH_CARRIER.get()), "the carrier stays in hand");
        h.assertTrue(ItemStack.isSameItemSameTags(kettle.inventory().getStackInSlot(RACK), a)
                && ItemStack.isSameItemSameTags(kettle.inventory().getStackInSlot(RACK + 1), b), "exact stacks, foreign tags and brewing data included");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 6, "four loaded into the four free slots");
        List<ItemStack> left = CarrierContents.read(after);
        h.assertTrue(CarrierContents.count(after) == 3, "what did not fit stays in the carrier: " + left);
        h.assertTrue(left.stream().anyMatch(s -> s.is(Items.GLASS_BOTTLE) && s.getCount() == 2), "empty bottles are not loaded");
        h.assertTrue(left.stream().anyMatch(s -> ItemStack.isSameItemSameTags(s, a)), "the sixth potion is still there, unchanged");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void collectingFinishedBottles(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Collector");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.use(player, kettle, carrier()).is(ModItems.BATCH_CARRIER.get()) && CarrierContents.isEmpty(player.getMainHandItem()),
                "nothing to collect or load during a job");
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            List<ItemStack> expected = new ArrayList<>();
            for (int i = 0; i < 3; i++) expected.add(kettle.inventory().getStackInSlot(RACK + i).copy());
            ItemStack after = TestSupport.use(player, kettle, carrier());
            h.assertTrue(CarrierContents.count(after) == 3, "three finished bottles collected");
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 0, "the rack is empty");
            List<ItemStack> inside = CarrierContents.read(after);
            for (ItemStack e : expected) {
                h.assertTrue(inside.stream().anyMatch(s -> ItemStack.matches(s, e)), "each bottle exactly as brewed");
            }
            h.assertTrue(kettle.comparatorSignal() == 0, "the output comparator falls to zero");
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void nothingInventoryBearingFits(GameTestHelper h) {
        h.assertTrue(!CarrierContents.accepts(new ItemStack(ModItems.BATCH_CARRIER.get())), "no nested carriers");
        h.assertTrue(!CarrierContents.accepts(new ItemStack(ModItems.RECOVERY_BATCH.get())), "no recovery batches");
        h.assertTrue(!CarrierContents.accepts(new ItemStack(Items.SHULKER_BOX)), "no shulker boxes");
        h.assertTrue(!CarrierContents.accepts(new ItemStack(Items.BUNDLE)), "no bundles");
        h.assertTrue(!CarrierContents.accepts(new ItemStack(Items.SUGAR)), "no loose reagents");
        h.assertTrue(CarrierContents.accepts(TestSupport.water()) && CarrierContents.accepts(new ItemStack(Items.TIPPED_ARROW))
                && CarrierContents.accepts(new ItemStack(Items.GLASS_BOTTLE)), "potion containers do");

        ItemStack held = carrier();
        IItemHandler handler = held.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
        h.assertTrue(handler.getSlots() == 6, "six slots");
        h.assertTrue(handler.insertItem(0, new ItemStack(ModItems.BATCH_CARRIER.get()), false).getCount() == 1, "the handler refuses a carrier");
        h.assertTrue(handler.insertItem(0, TestSupport.water(), true).isEmpty() && CarrierContents.isEmpty(held), "simulation writes nothing");
        h.assertTrue(handler.insertItem(0, TestSupport.water(), false).isEmpty() && CarrierContents.count(held) == 1, "execution writes the carrier");
        h.assertTrue(handler.insertItem(0, gradedSwiftness("X"), false).getCount() == 1, "a different stack does not merge into an occupied slot");
        h.assertTrue(handler.extractItem(0, 1, false).is(Items.POTION) && CarrierContents.isEmpty(held), "and it comes back out");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theCarrierNeverEntersARecipeOrStation(GameTestHelper h) {
        ItemStack held = carrier(TestSupport.water());
        h.assertTrue(!net.minecraftforge.common.brewing.BrewingRecipeRegistry.isValidIngredient(held)
                && !net.minecraftforge.common.brewing.BrewingRecipeRegistry.isValidInput(held), "not a brewing ingredient or input");
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        h.assertTrue(!kettle.inventory().isItemValid(TRAY, held) && !kettle.inventory().isItemValid(RACK, held), "not a Kettle tray or rack item");
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, new BlockPos(1, 1, 1));
        h.assertTrue(!mortar.inventory().isItemValid(ApothecaryMortarBlockEntity.INPUT, held), "not a Mortar reagent");
        h.setBlock(new BlockPos(7, 1, 7), com.otectus.immersivebrewing.registry.ModBlocks.ALEMBIC.get());
        AlembicBlockEntity alembic = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(7, 1, 7)));
        h.assertTrue(!alembic.inventory().isItemValid(AlembicBlockEntity.INPUT, held), "not an Alembic input");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theAlembicLoadsAndCollectsToo(GameTestHelper h) {
        AlembicBlockEntity alembic = TestSupport.alembic(h);
        alembic.inventory().setStackInSlot(AlembicBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER));
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        FakePlayer player = TestSupport.player(h, "Hauler");
        ItemStack held = TestSupport.use(player, alembic, carrier(awkward.copy(), awkward.copy(), awkward.copy()));
        h.assertTrue(CarrierContents.isEmpty(held) && TestSupport.count(alembic, AlembicBlockEntity.INPUT, 6, Items.POTION) == 3, "three loaded into the still");
        TestSupport.openAlembic(player, alembic);
        alembic.startReclamation(player);
        h.assertTrue(alembic.ledger() != null, "reclaiming");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(alembic.ledger() == null, "still reclaiming")).thenExecute(() -> {
            ItemStack after = TestSupport.use(player, alembic, carrier());
            h.assertTrue(CarrierContents.count(after) == 3 && CarrierContents.read(after).get(0).is(Items.GLASS_BOTTLE), "the bottles were collected");
            h.assertTrue(TestSupport.count(alembic, AlembicBlockEntity.OUTPUT, 6, ModItems.SPENT_ALCHEMICAL_SALT.get()) == 1,
                    "the salt is not a potion container and stays");
        }).thenSucceed();
    }

    private CarrierGameTests() {}
}
