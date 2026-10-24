package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/** Regressions from the independent workshop audit: clock offsets, output recovery, batch identity and tool locks. */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkshopRegressionGameTests {
    private static final String CLOSED = "session.immersive_brewing.end.closed";

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void kettleResumeAdvancesAfterCountIn(GameTestHelper h) {
        var kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        var player = TestSupport.player(h, "KettleClock");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1, 1);
        h.runAfterDelay(140, () -> checkResumeClock(h, player, () -> {
            TestSupport.openKettle(player, kettle);
            kettle.resume(player);
        }, 2));
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void mortarResumeAdvancesAfterCountIn(GameTestHelper h) {
        var mortar = TestSupport.mortar(h, TestSupport.STATION);
        mortar.inventory().setStackInSlot(0, new ItemStack(Items.SUGAR));
        var player = TestSupport.player(h, "MortarClock");
        TestSupport.openMortar(player, mortar);
        mortar.start(player, 1);
        h.runAfterDelay(140, () -> checkResumeClock(h, player, () -> {
            TestSupport.openMortar(player, mortar);
            mortar.resume(player);
        }, 2));
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void alembicResumeAdvancesAfterCountIn(GameTestHelper h) {
        var still = AlembicGameTests.load(h, 1, AlembicGameTests.copies(
                PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH), 3));
        var player = TestSupport.player(h, "AlembicClock");
        TestSupport.openAlembic(player, still);
        still.startPurification(player, 0);
        h.runAfterDelay(140, () -> checkResumeClock(h, player, () -> {
            TestSupport.openAlembic(player, still);
            still.resume(player);
        }, 2));
    }

    private static void checkResumeClock(GameTestHelper h, FakePlayer player, Runnable resume, int repeats) {
        var before = TestSupport.session(player).orElseThrow();
        int saved = before.sim().step();
        h.assertTrue(saved > 20, "the original run has meaningful progress");
        long[] params = before.sim().params();
        SessionManager.leave(player, before.id(), CLOSED);
        resume.run();
        var resumed = TestSupport.session(player).orElseThrow();
        h.assertTrue(resumed.sim().step() == saved && Arrays.equals(params, resumed.sim().params()), "resume keeps step and parameters");
        long countIn = resumed.runStartTick() - h.getLevel().getServer().getTickCount();
        h.runAfterDelay(countIn - 1, () -> h.assertTrue(resumed.sim().step() == saved, "count-in does not advance saved progress"));
        h.runAfterDelay(countIn + resumed.bufferTicks() + 10, () -> {
            int advanced = resumed.sim().step() - saved;
            h.assertTrue(advanced >= 9 && advanced <= 11, "resume advances at one step per tick, without a stall or catch-up burst: " + advanced);
            if (repeats > 1) checkResumeClock(h, player, resume, repeats - 1);
            else {
                SessionManager.leave(player, resumed.id(), CLOSED);
                h.succeed();
            }
        });
    }

    private static void fillShelf(ApothecaryMortarBlockEntity mortar) {
        var profile = mortar.profileFor(new ItemStack(Items.SUGAR));
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i,
                    new KitData(new ItemStack(Items.SUGAR), 1, profile.id(), 50).toStack(1));
        }
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void fullMortarKeepsRecoveryItemUntilOutputSpaceExists(GameTestHelper h) {
        var mortar = TestSupport.mortar(h, TestSupport.STATION);
        mortar.inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 2));
        var player = TestSupport.player(h, "RecoverPrep");
        TestSupport.openMortar(player, mortar);
        mortar.start(player, 2);
        TestSupport.driveMortar(h, player, MortarBot.competent());
        ApothecaryMortarBlockEntity[] installed = {null};
        h.runAfterDelay(100, () -> {
            h.getLevel().destroyBlock(mortar.getBlockPos(), true);
            var drops = TestSupport.drops(h, TestSupport.STATION);
            var batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
            h.assertTrue(batches.size() == 1 && TestSupport.dropped(drops, Items.SUGAR) == 0, "one recovery item owns both reagents");
            ItemStack batch = batches.get(0).copy();
            drops.forEach(Entity::discard);
            var fresh = TestSupport.mortar(h, TestSupport.STATION);
            fillShelf(fresh);
            ItemStack refused = TestSupport.use(player, fresh, batch);
            h.assertTrue(ItemStack.matches(refused, batch) && fresh.ledger() == null, "full shelf refuses recovery without consuming or changing it");
            for (int i = 1; i <= 4; i++) fresh.inventory().extractItem(i, 64, false);
            h.assertTrue(TestSupport.use(player, fresh, refused).isEmpty(), "space allows the recovery item to transfer once");
            h.assertTrue(fresh.ledger().inputs().get(0).getCount() == 2, "both reagents transferred intact");
            installed[0] = fresh;
            // A saved/recovered sealed job must also recheck capacity when the player actually resumes it.
            fillShelf(fresh);
            TestSupport.openMortar(player, fresh);
            fresh.resume(player);
            h.assertTrue(TestSupport.session(player).isEmpty() && fresh.ledger().state() == JobState.SEALED,
                    "resume refuses a full shelf without starting or losing the sealed job");
            for (int i = 1; i <= 4; i++) fresh.inventory().extractItem(i, 64, false);
            TestSupport.openMortar(player, fresh);
            fresh.resume(player);
        });
        h.succeedWhen(() -> {
            var fresh = installed[0];
            h.assertTrue(fresh != null && fresh.ledger() == null, "recovered preparation still running");
            h.assertTrue(TestSupport.count(fresh, 1, 4, ModItems.PREPARED_REAGENT_KIT.get()) == 2, "exactly two kits issued");
            h.assertTrue(KitData.read(fresh.inventory().getStackInSlot(1)).orElseThrow().source().is(Items.SUGAR), "source reagent preserved");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void oldNoRoomPreparationReloadsAndCompletesOnce(GameTestHelper h) {
        var mortar = TestSupport.mortar(h, TestSupport.STATION);
        mortar.inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 4));
        var player = TestSupport.player(h, "OldPrepSave");
        TestSupport.openMortar(player, mortar);
        mortar.start(player, 4);
        h.runAfterDelay(100, () -> {
            SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), CLOSED);
            var finished = (SimHandles.Preparation) SimHandles.create(SessionKind.PREPARATION, mortar.ledger().params(), mortar.ledger().simState());
            var bot = MortarBot.competent();
            while (!finished.finished()) finished.step(bot.decide(finished.sim()).pack());
            int extraction = finished.sim().score().extraction();
            // Reproduce a pre-fix save: a completed job whose only obstruction was the occupied output shelf.
            mortar.ledger().setSimState(finished.saveState());
            fillShelf(mortar);
            mortar.ledger().hold("recovery.immersive_brewing.no_room");
            CompoundTag saved = mortar.saveWithFullMetadata();
            var pos = mortar.getBlockPos();
            h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, mortar.getBlockState(), saved));
            var loaded = (ApothecaryMortarBlockEntity) h.getLevel().getBlockEntity(pos);
            loaded.revalidateRecovery();
            h.assertTrue(loaded.ledger().state() == JobState.RECOVERY, "full shelf still holds the saved job");
            TestSupport.openMortar(player, loaded);
            player.containerMenu.quickMoveStack(player, ApothecaryMortarBlockEntity.OUTPUT);
            h.assertTrue(loaded.ledger().state() == JobState.SEALED && loaded.ledger().recoveryReason() == null,
                    "taking output releases the old no-room hold without an operator command");
            h.assertTrue(Arrays.equals(loaded.ledger().simState(), finished.saveState()), "the earned result is unchanged");
            loaded.resume(player);
            var session = TestSupport.session(player).orElseThrow();
            long delay = session.runStartTick() - h.getLevel().getServer().getTickCount() + 5;
            h.runAfterDelay(delay, () -> {
                h.assertTrue(loaded.ledger() == null, "the completed saved job commits after count-in");
                var output = loaded.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT);
                h.assertTrue(output.getCount() == 4 && KitData.read(output).orElseThrow().extraction() == extraction, "four kits retain their earned extraction");
                h.assertTrue(loaded.completeJob(player, finished) == null, "repeated completion issues nothing");
                h.assertTrue(TestSupport.count(loaded, 1, 4, ModItems.PREPARED_REAGENT_KIT.get()) == 7, "four new kits plus three old shelf kits, no duplicates");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void sixOrdinaryPotionsProduceOneUsableBatchLineage(GameTestHelper h) {
        var still = AlembicGameTests.load(h, 1, AlembicGameTests.copies(
                PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH), 6));
        AlembicGameTests.purify(h, still, "BatchRefiner");
        BlockPos kettlePos = new BlockPos(2, 1, 4);
        h.setBlock(kettlePos, ModBlocks.BREWERS_KETTLE.get());
        var kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(kettlePos));
        h.succeedWhen(() -> {
            h.assertTrue(still.ledger() == null, "still purifying");
            var outputs = AlembicGameTests.outputs(still, Items.POTION);
            h.assertTrue(outputs.size() == 4, "six bottles yield four potions");
            var metadata = BrewingMetadata.read(outputs.get(0)).orElseThrow();
            h.assertTrue(metadata.lineage() != 0 && metadata.purifications() == 1, "one recorded purification with a lineage");
            for (int i = 0; i < outputs.size(); i++) {
                h.assertTrue(BrewingMetadata.read(outputs.get(i)).orElseThrow().equals(metadata), "all outputs share batch metadata");
                kettle.inventory().setStackInSlot(i, outputs.get(i).copy());
            }
            h.assertTrue(kettle.rack().problem() == null && kettle.rack().servings() == 4, "all four outputs can be brewed together");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void paddleLockedThroughCountInAndBrewingButReplaceableWhileSealed(GameTestHelper h) {
        var kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        var brewer = TestSupport.player(h, "ToolBrewer");
        var viewer = TestSupport.player(h, "ToolViewer");
        TestSupport.openKettle(brewer, kettle);
        kettle.startBrew(brewer, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1, 1);
        TestSupport.openKettle(viewer, kettle);
        checkPaddleLocked(h, kettle, viewer);
        TestSupport.driveKettle(h, brewer, KettleBot.competent());
        boolean[] resumed = {false};
        h.runAfterDelay(140, () -> {
            checkPaddleLocked(h, kettle, viewer);
            SessionManager.leave(brewer, TestSupport.session(brewer).orElseThrow().id(), CLOSED);
            var menu = viewer.containerMenu;
            menu.clicked(BrewersKettleBlockEntity.PADDLE, 0, ClickType.PICKUP, viewer);
            h.assertTrue(menu.getCarried().is(ModItems.WOODEN_BREWING_PADDLE.get()), "a sealed job allows paddle maintenance");
            menu.clicked(BrewersKettleBlockEntity.PADDLE, 0, ClickType.PICKUP, viewer);
            h.assertTrue(menu.getCarried().isEmpty(), "the paddle can be returned before resuming");
            TestSupport.openKettle(brewer, kettle);
            kettle.resume(brewer);
            checkPaddleLocked(h, kettle, viewer); // SEALED ledger, but a resumed count-in owns the tool.
            resumed[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(resumed[0] && kettle.ledger() == null, "still brewing");
            ItemStack paddle = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE);
            h.assertTrue(paddle.is(ModItems.WOODEN_BREWING_PADDLE.get()) && paddle.getDamageValue() == 1, "completed batch charges paddle wear exactly once");
            h.assertTrue(TestSupport.count(kettle, 0, 6, Items.POTION) == 3, "three outputs issued");
            h.assertTrue(viewer.getInventory().countItem(ModItems.WOODEN_BREWING_PADDLE.get()) == 0, "viewer acquired no paddle copy");
        });
    }

    private static void checkPaddleLocked(GameTestHelper h, BrewersKettleBlockEntity kettle, FakePlayer viewer) {
        int slot = BrewersKettleBlockEntity.PADDLE;
        var menu = viewer.containerMenu;
        h.assertTrue(kettle.inventory().extractItem(slot, 1, true).isEmpty(), "simulated handler extraction respects the lock");
        h.assertTrue(kettle.inventory().extractItem(slot, 1, false).isEmpty(), "handler extraction respects the lock");
        h.assertTrue(menu.quickMoveStack(viewer, slot).isEmpty(), "direct shift-click respects the lock");
        viewer.getInventory().setItem(0, new ItemStack(Items.STONE));
        for (ClickType type : new ClickType[] {ClickType.PICKUP, ClickType.QUICK_MOVE, ClickType.SWAP, ClickType.THROW}) {
            menu.clicked(slot, 0, type, viewer);
            h.assertTrue(kettle.inventory().getStackInSlot(slot).is(ModItems.WOODEN_BREWING_PADDLE.get())
                    && menu.getCarried().isEmpty(), "paddle stays locked for " + type);
        }
        h.assertTrue(viewer.getInventory().getItem(0).is(Items.STONE), "hotbar swap did not exchange the paddle");
    }

    private WorkshopRegressionGameTests() {}
}
