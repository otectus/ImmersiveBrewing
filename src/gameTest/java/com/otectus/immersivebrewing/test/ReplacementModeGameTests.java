package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.config.BrewingMode;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Milestone 3 (spec 14): the ordinary brewing stand. COEXIST never intercepts; REPLACE_COVERED stops a stand holding only
 * covered transformations before it spends fuel or time (the {@code isBrewable} mixin) with {@code PotionBrewEvent.Pre}
 * as the backstop, lets uncovered modifiers and mixed cycles through whole, and graded potions brewed at a stand keep
 * lineage with quality held at most at the baseline; DISABLED refuses new jobs but lets sealed ones resume.
 * The mode is a server setting, so each mode runs in its own batch and the default is restored afterwards.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReplacementModeGameTests {
    private static final BlockPos STAND = new BlockPos(4, 1, 4);

    @BeforeBatch(batch = "replace_covered")
    public static void replaceCovered(ServerLevel level) {
        ServerConfig.BREWING_MODE.set(BrewingMode.REPLACE_COVERED);
    }

    @AfterBatch(batch = "replace_covered")
    public static void restoreAfterReplace(ServerLevel level) {
        ServerConfig.BREWING_MODE.set(BrewingMode.COEXIST);
    }

    @BeforeBatch(batch = "disabled_mode")
    public static void disable(ServerLevel level) {
        ServerConfig.BREWING_MODE.set(BrewingMode.DISABLED);
    }

    @AfterBatch(batch = "disabled_mode")
    public static void restoreAfterDisable(ServerLevel level) {
        ServerConfig.BREWING_MODE.set(BrewingMode.COEXIST);
    }

    private static ItemStack potion(Item item, Potion potion) {
        return PotionUtils.setPotion(new ItemStack(item), potion);
    }

    private static BrewingStandBlockEntity stand(GameTestHelper h, ItemStack ingredient, ItemStack... bottles) {
        h.setBlock(STAND, Blocks.BREWING_STAND);
        BrewingStandBlockEntity stand = (BrewingStandBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STAND));
        for (int i = 0; i < bottles.length; i++) stand.setItem(i, bottles[i]);
        stand.setItem(3, ingredient);
        stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        return stand;
    }

    private static CompoundTag state(BrewingStandBlockEntity stand) {
        return stand.saveWithoutMetadata();
    }

    // ---- COEXIST (default batch) ----

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void coexistLeavesTheStandAlone(GameTestHelper h) {
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.NETHER_WART), TestSupport.water(), TestSupport.water(), TestSupport.water());
        h.succeedWhen(() -> {
            for (int i = 0; i < 3; i++) h.assertTrue(PotionUtils.getPotion(stand.getItem(i)) == Potions.AWKWARD, "the stand brewed Awkward " + i);
            h.assertTrue(stand.getItem(3).isEmpty(), "and used its wart");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void standModifiersKeepLineageAtTheBaseline(GameTestHelper h) {
        ItemStack graded = potion(Items.POTION, Potions.SWIFTNESS);
        BrewingMetadata in = new BrewingMetadata(90, 95, 94, 0x5157L, 1, UUID.randomUUID(), "Brewer", null, "Label", null);
        BrewingMetadata.write(graded, in);
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.REDSTONE), graded.copy());
        h.succeedWhen(() -> {
            ItemStack out = stand.getItem(0);
            h.assertTrue(PotionUtils.getPotion(out) == Potions.LONG_SWIFTNESS, "the stand made long Swiftness");
            BrewingMetadata meta = BrewingMetadata.read(out).orElseThrow(() -> new IllegalStateException("metadata lost at the stand"));
            h.assertTrue(meta.quality() == 50 && meta.lineage() == in.lineage() && meta.purifications() == 1,
                    "no new score at the stand: baseline quality, same lineage and purification count (" + meta.quality() + ")");
            h.assertTrue(meta.label() == null, "a changed potion drops its label");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void standContainerStepsKeepQuality(GameTestHelper h) {
        ItemStack graded = potion(Items.POTION, Potions.SWIFTNESS);
        BrewingMetadata in = new BrewingMetadata(90, 95, 94, 0x5158L, 0, UUID.randomUUID(), "Brewer", null, "Label", null);
        BrewingMetadata.write(graded, in);
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.GUNPOWDER), graded.copy());
        h.succeedWhen(() -> {
            ItemStack out = stand.getItem(0);
            h.assertTrue(out.is(Items.SPLASH_POTION) && PotionUtils.getPotion(out) == Potions.SWIFTNESS, "a splash Swiftness");
            BrewingMetadata meta = BrewingMetadata.read(out).orElseThrow(() -> new IllegalStateException("metadata lost at the stand"));
            h.assertTrue(meta.quality() == 94 && "Label".equals(meta.label()), "the same potion in a new container keeps its quality and label");
        });
    }

    // ---- REPLACE_COVERED ----

    @GameTest(template = "empty", timeoutTicks = 200, batch = "replace_covered")
    public static void aCoveredStandNeverStarts(GameTestHelper h) {
        h.assertTrue(ServerConfig.brewingMode() == BrewingMode.REPLACE_COVERED, "batch mode");
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.NETHER_WART), TestSupport.water(), TestSupport.water(), TestSupport.water());
        NonNullList<ItemStack> items = NonNullList.withSize(5, ItemStack.EMPTY);
        for (int i = 0; i < 3; i++) items.set(i, TestSupport.water());
        items.set(3, new ItemStack(Items.NETHER_WART));
        h.assertTrue(ForgeEventFactory.onPotionAttemptBrew(items), "the PotionBrewEvent.Pre backstop cancels a covered brew");
        h.runAfterDelay(60, () -> {
            CompoundTag tag = state(stand);
            h.assertTrue(tag.getShort("BrewTime") == 0, "the stand never started its timer");
            h.assertTrue(tag.getByte("Fuel") == 20, "its fuel charge was loaded but never spent: " + tag.getByte("Fuel"));
            h.assertTrue(stand.getItem(3).getCount() == 1, "the wart is untouched");
            for (int i = 0; i < 3; i++) h.assertTrue(PotionUtils.getPotion(stand.getItem(i)) == Potions.WATER, "still water " + i);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "replace_covered")
    public static void uncoveredModifiersStillBrew(GameTestHelper h) {
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.REDSTONE), potion(Items.POTION, Potions.SWIFTNESS));
        h.succeedWhen(() -> h.assertTrue(PotionUtils.getPotion(stand.getItem(0)) == Potions.LONG_SWIFTNESS,
                "modifiers stay at the stand unless replaceModifiers is on"));
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "replace_covered")
    public static void aMixedCyclePassesThroughWhole(GameTestHelper h) {
        // Water + glowstone (Thick, covered) beside Swiftness + glowstone (a modifier, not covered): the stand brews both.
        BrewingStandBlockEntity stand = stand(h, new ItemStack(Items.GLOWSTONE_DUST), TestSupport.water(), potion(Items.POTION, Potions.SWIFTNESS));
        h.succeedWhen(() -> {
            h.assertTrue(PotionUtils.getPotion(stand.getItem(0)) == Potions.THICK, "the covered bottle was brewed with the rest");
            h.assertTrue(PotionUtils.getPotion(stand.getItem(1)) == Potions.STRONG_SWIFTNESS, "and the uncovered one");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "replace_covered")
    public static void theKettleStillBrewsCoveredPotions(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Replacer");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(PotionUtils.getPotion(kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK)) == Potions.SWIFTNESS, "brewed at the Kettle");
        });
    }

    // ---- DISABLED ----

    @GameTest(template = "empty", timeoutTicks = 200, batch = "disabled_mode")
    public static void disabledRefusesNewJobsButResumesSealedOnes(GameTestHelper h) {
        h.assertTrue(ServerConfig.disabled(), "batch mode");
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Late");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(kettle.ledger() == null && TestSupport.session(player).isEmpty(), "no new job while disabled");
        h.assertTrue(TestSupport.count(kettle, BrewersKettleBlockEntity.RACK, 6, Items.POTION) == 3, "nothing reserved");

        // A batch sealed before the switch still resumes.
        ServerConfig.BREWING_MODE.set(BrewingMode.COEXIST);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        ServerConfig.BREWING_MODE.set(BrewingMode.DISABLED);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.runAfterDelay(90, () -> {
            SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED, "sealed");
            TestSupport.openKettle(player, kettle);
            kettle.resume(player);
            h.assertTrue(TestSupport.session(player).isPresent(), "a sealed batch resumes even while disabled");
            SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
            h.succeed();
        });
    }

    private ReplacementModeGameTests() {}
}
