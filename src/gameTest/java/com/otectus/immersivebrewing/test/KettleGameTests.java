package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * Milestone 1 (spec 27): water to Awkward to Swiftness, three bottles, through the real Kettle and session path: exact
 * reagent and fuel accounting, outputs identical to the Forge registry chain with graded metadata, prepared kits, and a
 * seal and resume that keeps the state.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KettleGameTests {
    static final ItemStack SWIFTNESS = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void threeServingsOfSwiftness(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 2));
        FakePlayer player = TestSupport.player(h, "Brewer");
        TestSupport.openKettle(player, kettle);
        BrewPlan plan = TestSupport.plan(h, kettle, SWIFTNESS);
        h.assertTrue(plan.steps().size() == 2, "water -> awkward -> swiftness has two steps");
        h.assertTrue(plan.fuelCharges(3) == 2 && plan.costs(3).stream().allMatch(c -> c.count() == 1), "3 servings: 1 wart, 1 sugar, 2 charges");
        kettle.startBrew(player, plan.key(), 1F, 1F);
        h.assertTrue(TestSupport.working(player), "the session started");
        h.assertTrue(kettle.ledger() != null && kettle.ledger().inputs().size() == 3, "the ledger owns the three bottles");
        h.assertTrue(kettle.fuelCharges() == 18, "one blaze powder became 20 charges, 2 reserved: " + kettle.fuelCharges());
        h.assertTrue(TestSupport.count(kettle, BrewersKettleBlockEntity.TRAY, 6, Items.NETHER_WART) == 1
                && TestSupport.count(kettle, BrewersKettleBlockEntity.TRAY, 6, Items.SUGAR) == 1, "exactly one wart and one sugar reserved");
        TestSupport.driveKettle(h, player, com.otectus.immersivebrewing.testing.KettleTender.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            ItemStack expected = BrewingRecipeRegistry.getOutput(BrewingRecipeRegistry.getOutput(TestSupport.water(), new ItemStack(Items.NETHER_WART)),
                    new ItemStack(Items.SUGAR));
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
                h.assertTrue(PotionContentsService.sameCanonical(out, expected), "output " + i + " is exactly the registry's Swiftness");
                BrewingMetadata meta = BrewingMetadata.read(out).orElseThrow();
                h.assertTrue(meta.quality() >= 70, "a competent brew is at least Fine: " + meta.quality());
                h.assertTrue("Brewer".equals(meta.brewerName()), "brewer recorded");
            }
            h.assertTrue(kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + 3).isEmpty(), "no extra bottle appeared");
            ItemStack paddle = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
            h.assertTrue(paddle.getDamageValue() == 1, "the paddle wore by one");
            h.assertTrue(kettle.lastResult() != null && kettle.lastResult().count() == 3, "last result recorded");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void preparedKitsReplaceTheirReagents(GameTestHelper h) {
        BlockPos mortarPos = new BlockPos(1, 1, 1);
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, mortarPos);
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.SUGAR, 3));
        FakePlayer player = TestSupport.player(h, "Apothecary");
        player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        TestSupport.openMortar(player, mortar);
        mortar.start(player, StationActionC2S.withAssist(2, 1, 1));
        h.assertTrue(mortar.ledger() != null && mortar.ledger().inputs().get(0).getCount() == 2, "two sugar reserved for two kits");
        h.assertTrue(mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT).getCount() == 1, "one sugar left in the bowl");
        TestSupport.driveMortar(h, player, com.otectus.immersivebrewing.testing.MortarTender.competent());
        h.succeedWhen(() -> {
            h.assertTrue(mortar.ledger() == null, "still preparing");
            ItemStack kits = ItemStack.EMPTY;
            for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
                ItemStack s = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
                if (!s.isEmpty()) kits = s;
            }
            h.assertTrue(kits.is(ModItems.PREPARED_REAGENT_KIT.get()) && kits.getCount() == 2, "two identical kits");
            KitData data = KitData.read(kits).orElseThrow();
            h.assertTrue(data.source().is(Items.SUGAR) && data.extraction() >= 80, "kit of sugar with earned extraction " + data.extraction());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void sealAndResumeKeepsTheState(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Sealer");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        TestSupport.driveKettle(h, player, com.otectus.immersivebrewing.testing.KettleTender.competent());
        net.minecraft.nbt.CompoundTag[] sealed = {null};
        h.runAfterDelay(300, () -> {
            h.assertTrue(TestSupport.working(player), "the batch is being tended");
            TestSupport.seal(player);
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED, "closing the lid seals the running batch");
            sealed[0] = kettle.ledger().process().copy();
            h.assertTrue(com.otectus.immersivebrewing.workshop.KettleProcess.load(sealed[0]).ticks() > 0, "the sealed process kept its progress");
        });
        h.runAfterDelay(340, () -> {
            h.assertTrue(kettle.ledger().state() == JobState.SEALED && kettle.liveProcess().save().equals(sealed[0]),
                    "nothing changed while sealed: no cost, no penalty, no progress");
            TestSupport.unseal(player, kettle);
            h.assertTrue(kettle.ledger().state() == JobState.RUNNING, "opening the lid carries on");
            var resumed = kettle.liveProcess();
            var before = com.otectus.immersivebrewing.workshop.KettleProcess.load(sealed[0]);
            h.assertTrue(resumed.doses() == before.doses() && resumed.computeControl() == before.computeControl()
                    && resumed.ticks() == before.ticks(), "resumed with every dose flag and score contribution, no reroll");
        });
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            ItemStack out = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK);
            h.assertTrue(PotionUtils.getPotion(out) == Potions.SWIFTNESS && BrewingMetadata.read(out).isPresent(), "finished after resuming");
        });
    }

    @GameTest(template = "empty")
    public static void withdrawingAnUntouchedBatchReturnsEverything(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 2, 1, new ItemStack(Items.NETHER_WART, 3), new ItemStack(Items.SUGAR, 5));
        FakePlayer player = TestSupport.player(h, "Changed Mind");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        var hand = TestSupport.hand(player, kettle);
        // Pouring and heating are reversible; only a dose is not.
        hand.batch(com.otectus.immersivebrewing.workshop.WorkshopParts.K_RACK, com.otectus.immersivebrewing.workshop.WorkshopAction.POUR, -1);
        hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BURNER, com.otectus.immersivebrewing.workshop.WorkshopAction.SET_CONTROL, 5);
        h.runAfterDelay(30, () -> {
            h.assertTrue(kettle.liveProcess() != null && kettle.liveProcess().allCharged(), "both servings are in the vessel");
            var result = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.CANCEL, 0);
            h.assertTrue(result.accepted(), "an untouched batch can be withdrawn");
            h.assertTrue(kettle.ledger() == null, "the reservation is released");
            h.assertTrue(TestSupport.count(kettle, BrewersKettleBlockEntity.RACK, 6, Items.POTION) == 2, "the exact bottles are back in the rack");
            h.assertTrue(TestSupport.count(kettle, BrewersKettleBlockEntity.TRAY, 6, Items.NETHER_WART) == 3
                    && TestSupport.count(kettle, BrewersKettleBlockEntity.TRAY, 6, Items.SUGAR) == 5, "reagents back on the tray");
            h.assertTrue(kettle.fuelCharges() == 20, "fuel charges refunded to the burner: " + kettle.fuelCharges());
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aDoseMakesTheBatchIrreversible(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 1, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Committed");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        var hand = TestSupport.hand(player, kettle);
        hand.batch(com.otectus.immersivebrewing.workshop.WorkshopParts.K_RACK, com.otectus.immersivebrewing.workshop.WorkshopAction.POUR, -1);
        h.runAfterDelay(15, () -> {
            var dose = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_CUP, com.otectus.immersivebrewing.workshop.WorkshopAction.DOSE, 0);
            h.assertTrue(dose.accepted(), "the first dose goes in");
            var again = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_CUP, com.otectus.immersivebrewing.workshop.WorkshopAction.DOSE, 0);
            h.assertTrue(!again.accepted() && kettle.liveProcess().doses() == 1, "a repeated dose is refused and debits nothing");
            var cancel = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.CANCEL, 0);
            h.assertTrue(!cancel.accepted() && kettle.ledger() != null, "after a dose nothing refunds fresh reagents");
            h.succeed();
        });
    }

    private KettleGameTests() {}
}
