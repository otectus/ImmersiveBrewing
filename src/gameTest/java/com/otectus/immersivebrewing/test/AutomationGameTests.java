package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.BYPRODUCT;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.FUEL;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.PADDLE;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * Milestone 2 (spec 22): hoppers, pipes and fluid handlers see the same ownership rules as players. Inputs only while
 * idle, by face; output only when completed and unreserved, from below; water only in whole 250 mB bottles, with
 * simulation and execution always agreeing.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AutomationGameTests {
    private static IItemHandler items(BrewersKettleBlockEntity station, Direction side) {
        return station.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElseThrow(IllegalStateException::new);
    }

    @GameTest(template = "empty")
    public static void kettleFacesFollowTheRules(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        IItemHandler up = items(kettle, Direction.UP);
        IItemHandler side = items(kettle, Direction.NORTH);
        IItemHandler down = items(kettle, Direction.DOWN);

        h.assertTrue(up.insertItem(RACK, TestSupport.water(), true).isEmpty(), "the top accepts a bottle into the rack (simulated)");
        h.assertTrue(kettle.inventory().getStackInSlot(RACK).isEmpty(), "simulation changed nothing");
        h.assertTrue(up.insertItem(RACK, TestSupport.water(), false).isEmpty() && !kettle.inventory().getStackInSlot(RACK).isEmpty(), "and inserts it");
        h.assertTrue(up.insertItem(RACK, TestSupport.water(), false).getCount() == 1, "one bottle per rack slot");
        h.assertTrue(up.insertItem(TRAY, new ItemStack(Items.SUGAR, 5), false).isEmpty(), "the top fills the tray");
        h.assertTrue(up.insertItem(TRAY + 1, TestSupport.water(), false).getCount() == 1, "potions never go in the tray");
        h.assertTrue(side.insertItem(RACK + 1, TestSupport.water(), false).getCount() == 1, "the sides do not reach the rack");
        h.assertTrue(side.insertItem(FUEL, new ItemStack(Items.BLAZE_POWDER, 4), false).isEmpty(), "the sides feed the burner");
        h.assertTrue(side.insertItem(FUEL, new ItemStack(Items.SUGAR), false).getCount() == 1, "only burner fuel");
        h.assertTrue(down.insertItem(TRAY + 1, new ItemStack(Items.SUGAR), false).getCount() == 1, "nothing enters from below");
        h.assertTrue(up.insertItem(PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()), false).getCount() == 1, "automation does not fit paddles");
        h.assertTrue(up.insertItem(TRAY + 1, new ItemStack(ModItems.RECOVERY_BATCH.get()), false).getCount() == 1, "recovery batches are refused");

        h.assertTrue(down.extractItem(RACK, 1, false).isEmpty(), "an unbrewed bottle can not be pulled out");
        h.assertTrue(down.extractItem(TRAY, 1, false).isEmpty(), "nor a reagent");
        h.assertTrue(down.extractItem(FUEL, 1, false).isEmpty(), "nor fuel");
        kettle.inventory().setStackInSlot(BYPRODUCT, new ItemStack(Items.GLASS_BOTTLE, 2));
        h.assertTrue(up.extractItem(BYPRODUCT, 1, false).isEmpty() && side.extractItem(BYPRODUCT, 1, false).isEmpty(), "only the bottom extracts");
        h.assertTrue(down.extractItem(BYPRODUCT, 2, true).getCount() == 2 && kettle.inventory().getStackInSlot(BYPRODUCT).getCount() == 2,
                "simulated extraction takes nothing");
        h.assertTrue(down.extractItem(BYPRODUCT, 2, false).getCount() == 2 && kettle.inventory().getStackInSlot(BYPRODUCT).isEmpty(),
                "the by-product shelf empties from below");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void automationNeverTouchesAJob(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 2));
        FakePlayer player = TestSupport.player(h, "Automator");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        IItemHandler up = items(kettle, Direction.UP);
        IItemHandler side = items(kettle, Direction.EAST);
        IItemHandler down = items(kettle, Direction.DOWN);
        h.assertTrue(up.insertItem(RACK + 3, TestSupport.water(), false).getCount() == 1, "the rack is locked while the job is open");
        h.assertTrue(up.insertItem(TRAY + 2, new ItemStack(Items.SUGAR), false).getCount() == 1, "so is the tray");
        h.assertTrue(side.insertItem(FUEL, new ItemStack(Items.BLAZE_POWDER), false).isEmpty(), "the burner can still be fed");
        for (int i = 0; i < kettle.inventory().getSlots(); i++) {
            h.assertTrue(down.extractItem(i, 64, false).isEmpty(), "nothing can be pulled during the job (slot " + i + ")");
        }
        IFluidHandler water = kettle.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).orElseThrow(IllegalStateException::new);
        h.assertTrue(water.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 0, "no water during the job");
        h.assertTrue(kettle.statusComparator() || kettle.comparatorSignal() == 0, "output comparator is empty while brewing");
        kettle.toggleComparatorMode();
        h.assertTrue(kettle.comparatorSignal() == 8, "status comparator: 8 while reserved or running");
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            h.assertTrue(kettle.comparatorSignal() == 15, "status comparator: 15 with finished output");
            ItemStack simulated = down.extractItem(RACK, 1, true);
            h.assertTrue(PotionUtils.getPotion(simulated) == Potions.SWIFTNESS && !kettle.inventory().getStackInSlot(RACK).isEmpty(),
                    "finished output is offered from below; simulation takes nothing");
            h.assertTrue(PotionUtils.getPotion(down.extractItem(RACK, 1, false)) == Potions.SWIFTNESS, "and comes out");
            h.assertTrue(side.extractItem(RACK + 1, 1, false).isEmpty(), "but not from the side");
            kettle.toggleComparatorMode();
            h.assertTrue(kettle.comparatorSignal() == 5, "output comparator: two of six rack slots finished -> 5, was " + kettle.comparatorSignal());
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void waterFillsWholeBottlesOnly(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        for (int i = 0; i < 6; i++) kettle.inventory().setStackInSlot(RACK + i, new ItemStack(Items.GLASS_BOTTLE));
        IFluidHandler water = kettle.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).orElseThrow(IllegalStateException::new);
        h.assertTrue(!kettle.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.DOWN).isPresent(), "no fluid access from below");
        h.assertTrue(water.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.SIMULATE) == 1000, "a bucket would fill four bottles");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.GLASS_BOTTLE) == 6, "simulation filled nothing");
        h.assertTrue(water.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 1000, "execution agrees");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 4 && TestSupport.count(kettle, RACK, 6, Items.GLASS_BOTTLE) == 2, "4 water, 2 empty");
        h.assertTrue(water.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.SIMULATE) == 500
                && water.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 500, "only as much as there are bottles");
        kettle.inventory().setStackInSlot(RACK, new ItemStack(Items.GLASS_BOTTLE));
        h.assertTrue(water.fill(new FluidStack(Fluids.WATER, 249), IFluidHandler.FluidAction.EXECUTE) == 0, "less than a bottle is refused");
        h.assertTrue(water.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE) == 0, "only water");
        h.assertTrue(water.drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty(), "nothing drains out");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 5, "five water bottles, no more");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aBucketFillsAtMostFour(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        for (int i = 0; i < 6; i++) kettle.inventory().setStackInSlot(RACK + i, new ItemStack(Items.GLASS_BOTTLE));
        FakePlayer player = TestSupport.player(h, "Waterer");
        ItemStack left = TestSupport.use(player, kettle, new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(left.is(Items.BUCKET), "the bucket was emptied");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 4 && TestSupport.count(kettle, RACK, 6, Items.GLASS_BOTTLE) == 2,
                "one bucket is four bottles, never six");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void realHoppersRespectTheRules(GameTestHelper h) {
        BlockPos kettlePos = new BlockPos(4, 2, 4);
        h.setBlock(kettlePos, ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(kettlePos));
        for (int i = 0; i < 2; i++) kettle.inventory().setStackInSlot(RACK + i, TestSupport.water());
        kettle.inventory().setStackInSlot(BYPRODUCT, new ItemStack(Items.GLASS_BOTTLE, 2));
        // A hopper below pulls from the kettle; a hopper above (pointing down) feeds it.
        h.setBlock(new BlockPos(4, 1, 4), Blocks.HOPPER);
        h.setBlock(new BlockPos(4, 3, 4), Blocks.HOPPER);
        HopperBlockEntity above = (HopperBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 3, 4)));
        above.setItem(0, TestSupport.water());
        above.setItem(1, new ItemStack(Items.NETHER_WART, 2));
        h.succeedWhen(() -> {
            HopperBlockEntity below = (HopperBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(4, 1, 4)));
            int bottles = 0;
            int potions = 0;
            for (int i = 0; i < below.getContainerSize(); i++) {
                if (below.getItem(i).is(Items.GLASS_BOTTLE)) bottles += below.getItem(i).getCount();
                if (below.getItem(i).is(Items.POTION)) potions += below.getItem(i).getCount();
            }
            h.assertTrue(bottles == 2, "the hopper below took the by-products: " + bottles);
            h.assertTrue(potions == 0, "but never the unbrewed bottles");
            h.assertTrue(above.isEmpty(), "the hopper above emptied into the kettle");
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 3, "a third bottle entered the rack");
            h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 2, "the wart went into the tray");
        });
    }

    @GameTest(template = "empty")
    public static void mortarAndAlembicFacesFollowTheRules(GameTestHelper h) {
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, new BlockPos(2, 1, 4));
        IItemHandler mortarTop = mortar.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElseThrow(IllegalStateException::new);
        IItemHandler mortarBottom = mortar.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElseThrow(IllegalStateException::new);
        h.assertTrue(mortarTop.insertItem(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.SUGAR, 8), false).isEmpty(), "the bowl takes reagents");
        h.assertTrue(mortarTop.insertItem(ApothecaryMortarBlockEntity.INPUT, TestSupport.water(), false).getCount() == 1, "but not potions");
        h.assertTrue(mortarBottom.extractItem(ApothecaryMortarBlockEntity.INPUT, 1, false).isEmpty(), "the bowl is never emptied by automation");

        h.setBlock(new BlockPos(6, 1, 4), ModBlocks.ALEMBIC.get());
        AlembicBlockEntity alembic = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(6, 1, 4)));
        IItemHandler still = alembic.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElseThrow(IllegalStateException::new);
        IItemHandler sideA = alembic.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.WEST).orElseThrow(IllegalStateException::new);
        IItemHandler underA = alembic.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElseThrow(IllegalStateException::new);
        h.assertTrue(still.insertItem(AlembicBlockEntity.INPUT, TestSupport.water(), false).isEmpty(), "the still takes a potion");
        h.assertTrue(still.insertItem(AlembicBlockEntity.INPUT + 1, new ItemStack(Items.SUGAR), false).getCount() == 1, "but not reagents");
        h.assertTrue(sideA.insertItem(AlembicBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER), false).isEmpty(), "fuel from the side");
        h.assertTrue(underA.extractItem(AlembicBlockEntity.INPUT, 1, false).isEmpty(), "inputs never come out the bottom");
        alembic.inventory().setStackInSlot(AlembicBlockEntity.OUTPUT, new ItemStack(Items.GLASS_BOTTLE));
        h.assertTrue(underA.extractItem(AlembicBlockEntity.OUTPUT, 1, false).is(Items.GLASS_BOTTLE), "outputs do");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void automationSwitchesAreHonoured(GameTestHelper h) {
        // The server switches are read on every call; this checks the handler asks them, using their current values.
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        IItemHandler up = items(kettle, Direction.UP);
        boolean inputs = com.otectus.immersivebrewing.config.ServerConfig.get(com.otectus.immersivebrewing.config.ServerConfig.ALLOW_AUTOMATION_INPUTS);
        h.assertTrue(up.isItemValid(RACK, TestSupport.water()) == inputs, "isItemValid follows allowAutomationInputs");
        h.assertTrue(SessionManager.at(h.getLevel(), kettle.getBlockPos()) == null && kettle.ledger() == null, "idle kettle");
        h.succeed();
    }

    private AutomationGameTests() {}
}
