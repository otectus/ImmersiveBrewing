package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.BYPRODUCT;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.PADDLE;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;

/**
 * Milestone 3 (spec 5.2): one to six servings cost one reagent per three servings per step and one fuel charge per
 * application, are made in one session, and leave exact remainders; prepared kits replace their reagent one for one and
 * set the batch's extraction; the Mortar prepares up to four kits at once.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServingEconomyGameTests {
    private static int kits(ApothecaryMortarBlockEntity mortar, Item source) {
        int n = 0;
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            ItemStack s = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
            if (s.is(ModItems.PREPARED_REAGENT_KIT.get()) && KitData.read(s).map(k -> k.source().is(source)).orElse(false)) n += s.getCount();
        }
        return n;
    }

    private static ItemStack sugarKit(int extraction, int count) {
        return new KitData(new ItemStack(Items.SUGAR), 1, ImmersiveBrewing.id("sugar"), extraction).toStack(count);
    }

    private static void servings(GameTestHelper h, int n) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, n, 1, new ItemStack(Items.NETHER_WART, 4), new ItemStack(Items.SUGAR, 4));
        FakePlayer player = TestSupport.player(h, "Batch" + n);
        TestSupport.openKettle(player, kettle);
        BrewPlan plan = TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS);
        int units = (n + 2) / 3;
        h.assertTrue(plan.costs(n).stream().allMatch(c -> c.count() == units), n + " servings cost " + units + " of each reagent: " + plan.costs(n));
        h.assertTrue(plan.fuelCharges(n) == 2 * units, "and " + 2 * units + " charges");
        kettle.startBrew(player, plan.key(), 1F, 1F);
        h.assertTrue(kettle.ledger() != null && kettle.ledger().inputs().size() == n, "one session holds all " + n + " bottles");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 4 - units && TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 4 - units,
                "exactly " + units + " of each reagent reserved");
        h.assertTrue(kettle.fuelCharges() == 20 - 2 * units && kettle.ledger().fuelCharges() == 2 * units, "fuel reserved: " + kettle.fuelCharges());
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            for (int i = 0; i < 6; i++) {
                ItemStack s = kettle.inventory().getStackInSlot(RACK + i);
                h.assertTrue(i < n ? PotionUtils.getPotion(s) == Potions.SWIFTNESS : s.isEmpty(), "rack slot " + i);
            }
            h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 4 - units && TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 4 - units,
                    "nothing else spent");
            h.assertTrue(kettle.fuelCharges() == 20 - 2 * units, "no further fuel");
            h.assertTrue(TestSupport.count(kettle, BYPRODUCT, 3, Items.GLASS_BOTTLE) == 0, "no remainders for wart and sugar");
            h.assertTrue(kettle.inventory().getStackInSlot(PADDLE).getDamageValue() == 1, "one batch, one paddle use");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void oneServing(GameTestHelper h) {
        servings(h, 1);
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void twoServings(GameTestHelper h) {
        servings(h, 2);
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void threeServings(GameTestHelper h) {
        servings(h, 3);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void fourServings(GameTestHelper h) {
        servings(h, 4);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void fiveServings(GameTestHelper h) {
        servings(h, 5);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void sixServings(GameTestHelper h) {
        servings(h, 6);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void kitsReplaceTheirReagentExactly(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 6, 1, new ItemStack(Items.NETHER_WART, 2), sugarKit(90, 2), new ItemStack(Items.SUGAR, 3));
        FakePlayer player = TestSupport.player(h, "Kitted");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(kettle.ledger() != null, "started");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, ModItems.PREPARED_REAGENT_KIT.get()) == 0, "both kits reserved first");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 3, "so no raw sugar was needed");
        h.assertTrue(kettle.ledger().extraction() == 90, "the batch's extraction is the kits': " + kettle.ledger().extraction());
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 6, "six Swiftness");
            h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 3 && TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 0,
                    "two kits replaced exactly two sugar");
        });
    }

    @GameTest(template = "empty")
    public static void aKitAndRawSugarAverage(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        ItemStack kit = sugarKit(90, 1);
        TestSupport.load(kettle, 6, 1, new ItemStack(Items.NETHER_WART, 2), kit.copy(), new ItemStack(Items.SUGAR, 3));
        FakePlayer player = TestSupport.player(h, "Mixed");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(kettle.ledger() != null, "started");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, ModItems.PREPARED_REAGENT_KIT.get()) == 0 && TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 2,
                "one kit and one raw sugar reserved");
        h.assertTrue(kettle.ledger().extraction() == 70, "E is the mean over the sugar units: (90 + 50) / 2 = " + kettle.ledger().extraction());
        SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 3, "cancelled: raw sugar back");
        boolean kitBack = false;
        for (int i = 0; i < 6; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(TRAY + i);
            if (ItemStack.isSameItemSameTags(s, kit) && s.getCount() == 1) kitBack = true;
        }
        h.assertTrue(kitBack, "and the kit back unchanged");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void fourKitPreparation(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 1, 1);
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, at);
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.SUGAR, 5));
        FakePlayer player = TestSupport.player(h, "Grinder");
        player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        TestSupport.openMortar(player, mortar);
        mortar.start(player, StationActionC2S.withAssist(4, 1, 1));
        h.assertTrue(mortar.ledger() != null && mortar.ledger().inputs().get(0).getCount() == 4, "four sugar reserved for four kits");
        TestSupport.driveMortar(h, player, MortarBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(mortar.ledger() == null, "still preparing");
            h.assertTrue(kits(mortar, Items.SUGAR) == 4, "four sugar kits from one preparation: " + kits(mortar, Items.SUGAR));
            h.assertTrue(mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT).getCount() == 1, "the fifth sugar stayed in the bowl");
        });
    }

    private ServingEconomyGameTests() {}
}
