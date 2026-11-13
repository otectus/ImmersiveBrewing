package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.RecoveryBatchItem;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.testing.KettleTender;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Consumer;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.PADDLE;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * Milestone 2 (spec 20) for the hands-on workshop (handoff 16, 17): what happens to a batch around saves, reloads,
 * interruptions, a broken station and a repeated completion. Leaving, dying, disconnecting or changing dimension never
 * cancels a batch: with no participant in range it seals itself after a short grace and waits exactly as it was. Every
 * test accounts for each bottle, reagent and fuel charge.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private static BrewersKettleBlockEntity at(GameTestHelper h) {
        return (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
    }

    private static boolean threeSwiftness(BrewersKettleBlockEntity kettle) {
        if (TestSupport.count(kettle, RACK, 6, Items.POTION) != 3) return false;
        for (int i = 0; i < 3; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(RACK + i);
            if (PotionUtils.getPotion(s) != Potions.SWIFTNESS || BrewingMetadata.read(s).isEmpty()) return false;
        }
        return true;
    }

    /** Sets out a three-serving Swiftness batch for a new player (one wart, one sugar, two charges reserved). */
    private static FakePlayer start(GameTestHelper h, BrewersKettleBlockEntity kettle, String name, java.util.function.BooleanSupplier disconnected) {
        FakePlayer player = TestSupport.player(h, name, disconnected);
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.working(player), "the batch was set out");
        return player;
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runningLedgerReloadsSealed(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Saver", () -> false);
        TestSupport.driveKettle(h, player, KettleTender.competent());
        BlockPos pos = h.absolutePos(STATION);
        h.runAfterDelay(220, () -> {
            h.assertTrue(TestSupport.working(player), "the batch is being tended");
            // The world saves mid-reaction: the live process is flushed into the ledger with it.
            CompoundTag saved = kettle.saveWithFullMetadata();
            h.assertTrue(JobState.RUNNING.name().equals(saved.getCompound("Ledger").getString("state")), "saved while running");
            h.assertTrue(saved.getCompound("Ledger").getInt("schema") == 3 && saved.getCompound("Ledger").contains("process"),
                    "a hands-on job saves as schema 3 with its versioned process payload");
            CompoundTag process = saved.getCompound("Ledger").getCompound("process").copy();
            // The server dies without sealing and the chunk loads from that save.
            h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, kettle.getBlockState(), saved));
            BrewersKettleBlockEntity loaded = at(h);
            h.assertTrue(loaded != kettle, "a new block entity loaded from the save");
            JobLedger ledger = loaded.ledger();
            h.assertTrue(ledger != null && ledger.state() == JobState.SEALED, "a batch saved mid-work loads sealed: no offline progression");
            h.assertTrue(ledger.process().equals(process), "the sealed process is exactly the saved one");
            h.assertTrue(ledger.inputs().size() == 3 && ledger.reserved().size() == 2 && ledger.fuelCharges() == 2,
                    "bottles, reagents and fuel are all still in the ledger");
            h.assertTrue(loaded.fuelCharges() == 18, "burner charges kept: " + loaded.fuelCharges());
            h.assertTrue(TestSupport.count(loaded, TRAY, 6, Items.NETHER_WART) == 0 && TestSupport.count(loaded, TRAY, 6, Items.SUGAR) == 0,
                    "no reagent reappeared in the tray");
            TestSupport.unseal(player, loaded);
            h.assertTrue(loaded.ledger().state() == JobState.RUNNING, "reopened after the reload");
        });
        h.succeedWhen(() -> {
            BrewersKettleBlockEntity loaded = at(h);
            h.assertTrue(loaded != null && loaded != kettle && loaded.ledger() == null, "still brewing");
            h.assertTrue(threeSwiftness(loaded), "exactly three graded Swiftness potions");
        });
    }

    @GameTest(template = "empty")
    public static void reservedLedgerReloadsReleased(GameTestHelper h) {
        // A 1.1 save taken during a count-in (a reservation that never started) is released when it loads.
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 3));
        FakePlayer player = TestSupport.player(h, "Reserver");
        BrewersKettleBlockEntity legacy = LegacyFixtures.sealedBrew(h, kettle, player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS), 0);
        CompoundTag saved = legacy.saveWithFullMetadata();
        saved.getCompound("Ledger").putString("state", JobState.RESERVED.name());
        BlockPos pos = h.absolutePos(STATION);
        h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, legacy.getBlockState(), saved));
        BrewersKettleBlockEntity loaded = at(h);
        loaded.onLoad();
        h.assertTrue(loaded.ledger() == null, "a reservation that never started is released when it loads");
        h.assertTrue(TestSupport.count(loaded, RACK, 6, Items.POTION) == 3, "three bottles back in the rack");
        h.assertTrue(TestSupport.count(loaded, TRAY, 6, Items.NETHER_WART) == 2 && TestSupport.count(loaded, TRAY, 6, Items.SUGAR) == 3,
                "every reagent back in the tray, none doubled");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anUntouchedHandsOnBatchReloadsSealedAndCanBeWithdrawn(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 3));
        FakePlayer player = start(h, kettle, "Planner", () -> false);
        CompoundTag saved = kettle.saveWithFullMetadata();
        BlockPos pos = h.absolutePos(STATION);
        h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, kettle.getBlockState(), saved));
        BrewersKettleBlockEntity loaded = at(h);
        loaded.onLoad();
        h.assertTrue(loaded.ledger() != null && loaded.ledger().state() == JobState.SEALED, "a set-out batch is kept, sealed");
        TestSupport.unseal(player, loaded);
        h.assertTrue(TestSupport.hand(player, loaded).act(WorkshopParts.K_LID, WorkshopAction.CANCEL, 0).accepted(), "and can be withdrawn untouched");
        h.assertTrue(loaded.ledger() == null && TestSupport.count(loaded, RACK, 6, Items.POTION) == 3
                && TestSupport.count(loaded, TRAY, 6, Items.NETHER_WART) == 2 && TestSupport.count(loaded, TRAY, 6, Items.SUGAR) == 3,
                "every bottle and reagent back in place");
        h.assertTrue(loaded.fuelCharges() == 20, "the reserved charges back in the burner: " + loaded.fuelCharges());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void breakingARunningKettleDropsOneRecoveryBatch(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 2, new ItemStack(Items.NETHER_WART, 3), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Breaker", () -> false);
        TestSupport.driveKettle(h, player, KettleTender.competent());
        BlockPos pos = h.absolutePos(STATION);
        h.runAfterDelay(160, () -> {
            h.assertTrue(TestSupport.working(player), "running when broken");
            h.getLevel().destroyBlock(pos, true);
            h.assertTrue(TestSupport.workingAt(player) == null, "breaking ended the working view");
            h.assertTrue("workshop.immersive_brewing.focus.end.gone".equals(TestSupport.lastFocusEnd(player)), "the player was told why");
            List<ItemEntity> drops = TestSupport.drops(h, STATION);
            List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
            h.assertTrue(batches.size() == 1 && batches.get(0).getCount() == 1, "exactly one Recovery Batch: " + batches);
            JobLedger ledger = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
            h.assertTrue(ledger.state() == JobState.SEALED && ledger.physical(), "the batch holds the sealed hands-on job");
            h.assertTrue(ledger.inputs().size() == 3 && ledger.reserved().size() == 2 && ledger.fuelCharges() == 2, "with its bottles, reagents and fuel");
            h.assertTrue(TestSupport.dropped(drops, Items.POTION) == 0, "no bottle dropped loose");
            h.assertTrue(TestSupport.dropped(drops, Items.NETHER_WART) == 2 && TestSupport.dropped(drops, Items.SUGAR) == 0, "the unreserved wart dropped once");
            h.assertTrue(TestSupport.dropped(drops, Items.BLAZE_POWDER) == 1, "the unburned powder dropped once");
            h.assertTrue(TestSupport.dropped(drops, ModItems.WOODEN_BREWING_PADDLE.get()) == 1, "the paddle dropped once");
            List<ItemStack> kettles = TestSupport.droppedStacks(drops, ModItems.BREWERS_KETTLE.get());
            h.assertTrue(kettles.size() == 1, "the kettle itself dropped");
            CompoundTag beTag = kettles.get(0).getTagElement("BlockEntityTag");
            h.assertTrue(beTag != null && beTag.getInt("FuelCharges") == 18, "the burner's 18 charges travel with the kettle");
            drops.forEach(Entity::discard);

            // Install it once at a new kettle and finish the brew there.
            h.setBlock(STATION, ModBlocks.BREWERS_KETTLE.get());
            BrewersKettleBlockEntity fresh = at(h);
            fresh.inventory().setStackInSlot(PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
            h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installing consumed the batch");
            h.assertTrue(fresh.ledger() != null && fresh.ledger().state() == JobState.SEALED, "the sealed job is in the new kettle");
            ItemStack copy = TestSupport.use(player, fresh, batches.get(0).copy());
            h.assertTrue(copy.getCount() == 1, "a station that already holds a job refuses another batch");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            TestSupport.unseal(player, fresh);
            h.assertTrue(TestSupport.working(player), "the installed batch carries on");
        });
        h.succeedWhen(() -> {
            BrewersKettleBlockEntity fresh = at(h);
            h.assertTrue(fresh != null && fresh != kettle && fresh.ledger() == null, "still brewing");
            h.assertTrue(threeSwiftness(fresh), "the recovered batch finished as three Swiftness potions");
        });
    }

    @GameTest(template = "empty")
    public static void breakingAnUntouchedBatchPackagesTheReservation(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Hasty", () -> false);
        h.getLevel().destroyBlock(h.absolutePos(STATION), true);
        List<ItemEntity> drops = TestSupport.drops(h, STATION);
        List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
        h.assertTrue(batches.size() == 1, "one Recovery Batch for the set-out batch");
        JobLedger ledger = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
        h.assertTrue(ledger.physical() && ledger.fuelCharges() == 2, "the batch, with its two charges");
        CompoundTag beTag = TestSupport.droppedStacks(drops, ModItems.BREWERS_KETTLE.get()).get(0).getTagElement("BlockEntityTag");
        h.assertTrue(beTag != null && beTag.getInt("FuelCharges") == 18, "the burner's other 18 charges are on the kettle item");
        h.assertTrue(TestSupport.dropped(drops, Items.POTION) == 0 && TestSupport.dropped(drops, Items.SUGAR) == 0, "nothing reserved dropped loose");
        drops.forEach(Entity::discard);

        // Installed at a new kettle it is still the same untouched batch: withdrawing it releases everything there.
        h.setBlock(STATION, ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity fresh = at(h);
        h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installing consumed the batch");
        TestSupport.unseal(player, fresh);
        h.assertTrue(TestSupport.hand(player, fresh).act(WorkshopParts.K_LID, WorkshopAction.CANCEL, 0).accepted(), "withdrawn untouched");
        h.assertTrue(fresh.ledger() == null, "the reservation was released");
        h.assertTrue(TestSupport.count(fresh, RACK, 6, Items.POTION) == 3, "three bottles in the rack");
        h.assertTrue(TestSupport.count(fresh, TRAY, 6, Items.NETHER_WART) == 1 && TestSupport.count(fresh, TRAY, 6, Items.SUGAR) == 1, "both reagents back");
        h.assertTrue(fresh.fuelCharges() == 2, "and the two reserved charges: 18 + 2 = the one powder's 20");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aCompletionIssuesOnce(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Twice", () -> false);
        TestSupport.driveKettle(h, player, KettleTender.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            var hand = TestSupport.hand(player, kettle);
            // Repeated transfers after the batch closed (a duplicate tick, a forged packet, a second hook) do nothing.
            h.assertTrue(!hand.batch(WorkshopParts.K_SPOUT, WorkshopAction.FILL, -1).accepted(), "a second fill issues nothing");
            h.assertTrue(!hand.act(WorkshopParts.K_CUP, WorkshopAction.DOSE, 0).accepted(), "a late dose issues nothing");
        }).thenIdle(5).thenExecute(() -> {
            h.assertTrue(threeSwiftness(kettle) && kettle.inventory().getStackInSlot(RACK + 3).isEmpty(), "still exactly three potions");
            h.assertTrue(TestSupport.results(h, STATION) == 1, "the result event fired once: " + TestSupport.results(h, STATION));
            h.assertTrue(kettle.inventory().getStackInSlot(PADDLE).getDamageValue() == 1, "the paddle wore once");
        }).thenSucceed();
    }

    /**
     * Sets out and tends a brew, applies {@code interrupt}, and expects the batch to seal itself after the grace with
     * every cumulative value and owned item kept, and the working view (if the player can still be told) closed with
     * {@code reason}.
     */
    private static void sealsWhen(GameTestHelper h, String name, boolean[] flag, Consumer<FakePlayer> interrupt, String reason) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, name, () -> flag[0]);
        TestSupport.driveKettle(h, player, KettleTender.competent());
        long[] before = {-1};
        h.runAfterDelay(140, () -> {
            h.assertTrue(TestSupport.working(player), "running before the interruption");
            before[0] = kettle.liveProcess().ticks();
            interrupt.accept(player);
        });
        h.runAfterDelay(140 + com.otectus.immersivebrewing.blockentity.StationBlockEntity.GRACE_TICKS + 8, () -> {
            JobLedger ledger = kettle.ledger();
            h.assertTrue(ledger != null && ledger.state() == JobState.SEALED, "the batch sealed itself, not lost");
            long sealed = KettleProcess.load(ledger.process()).ticks();
            h.assertTrue(sealed >= before[0] && sealed <= before[0] + com.otectus.immersivebrewing.blockentity.StationBlockEntity.GRACE_TICKS + 2,
                    "progress kept through the grace, then frozen: " + sealed + " after " + before[0]);
            if (reason != null) h.assertTrue(reason.equals(TestSupport.lastFocusEnd(player)), "reason " + TestSupport.lastFocusEnd(player));
            h.assertTrue(ledger.inputs().size() == 3 && ledger.reserved().size() == 2, "everything still owned by the job");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void disconnectSeals(GameTestHelper h) {
        boolean[] gone = {false};
        sealsWhen(h, "Leaver", gone, p -> gone[0] = true, null);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSeals(GameTestHelper h) {
        sealsWhen(h, "Unlucky", new boolean[1], p -> p.setHealth(0F), "workshop.immersive_brewing.focus.end.died");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void walkingAwaySeals(GameTestHelper h) {
        sealsWhen(h, "Wanderer", new boolean[1], p -> p.setPos(p.getX() + 20, p.getY(), p.getZ()), "workshop.immersive_brewing.focus.end.range");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void changingDimensionSeals(GameTestHelper h) {
        sealsWhen(h, "Traveller", new boolean[1], p -> {
            net.minecraft.server.level.ServerLevel other = p.server.getLevel(Level.NETHER);
            if (other == null) other = p.server.getLevel(Level.END);
            h.assertTrue(other != null, "the test server has a second dimension");
            p.setServerLevel(other);
        }, "workshop.immersive_brewing.focus.end.dimension");
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aHelperNearbyKeepsTheBatchWorking(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer brewer = start(h, kettle, "Brewer", () -> false);
        FakePlayer helper = TestSupport.player(h, "Helper");
        TestSupport.openView(helper, kettle);
        var helperHand = TestSupport.hand(helper, kettle);
        h.assertTrue(helperHand.batch(WorkshopParts.K_RACK, WorkshopAction.POUR, -1).accepted(), "the helper pours: productive work");
        h.runAfterDelay(20, () -> brewer.setPos(brewer.getX() + 30, brewer.getY(), brewer.getZ()));
        h.runAfterDelay(20 + com.otectus.immersivebrewing.blockentity.StationBlockEntity.GRACE_TICKS + 20, () -> {
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.RUNNING,
                    "a participant who stayed keeps the batch working after the brewer left");
            h.assertTrue(kettle.ledger().assistants().contains("Helper"), "and is recorded as an assistant");
            h.assertTrue("Brewer".equals(kettle.ledger().brewerName()), "the original brewer stays credited");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aPasserbyDoesNotKeepSomeoneElsesBrewRunning(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer brewer = start(h, kettle, "Absent", () -> false);
        FakePlayer passerby = TestSupport.player(h, "Passerby");
        TestSupport.openView(passerby, kettle); // only looking: no productive action
        brewer.setPos(brewer.getX() + 30, brewer.getY(), brewer.getZ());
        h.runAfterDelay(com.otectus.immersivebrewing.blockentity.StationBlockEntity.GRACE_TICKS + 10, () -> {
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED, "a viewer is not a participant: it sealed");
            h.succeed();
        });
    }

    private LifecycleGameTests() {}

    /**
     * Handoff 14: six inputs, two bottled, then the station broken. Two finished bottles drop loose and one Recovery
     * Batch owns the four remaining ready portions; installed in a new kettle they bottle there without a second
     * completion, experience, journal entry or paddle wear.
     */
    @GameTest(template = "empty", timeoutTicks = 3600)
    public static void bottlingTwoThenBreakingKeepsTheRestOwnedOnce(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 6, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 2));
        FakePlayer player = start(h, kettle, "Partial", () -> false);
        // Tend the chemistry only: bottling is done by hand below.
        TestSupport.driveKettle(h, player, KettleTender.competent(), () -> kettle.liveProcess() == null || !kettle.liveProcess().finalized());
        BlockPos pos = h.absolutePos(STATION);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(kettle.liveProcess() != null && kettle.liveProcess().finalized(), "still reacting"))
                .thenExecute(() -> {
                    h.assertTrue(TestSupport.results(h, STATION) == 1, "the result fired once, at chemical completion");
                    var hand = TestSupport.hand(player, kettle);
                    h.assertTrue(hand.act(WorkshopParts.K_RACK, WorkshopAction.FILL, -1).accepted(), "the first bottle filled");
                    h.assertTrue(hand.act(WorkshopParts.K_RACK + 1, WorkshopAction.FILL, -1).accepted(), "the second bottle filled");
                    h.assertTrue(swiftnessIn(kettle) == 2 && kettle.ledger().inputs().size() == 4 && kettle.liveProcess().portions() == 4,
                            "two bottled, four portions still owned by the batch");
                    int journal = com.otectus.immersivebrewing.guide.PlayerGuide.data(player).getList("history", 10).size();
                    h.getLevel().destroyBlock(pos, true);
                    List<ItemEntity> drops = TestSupport.drops(h, STATION);
                    List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
                    h.assertTrue(batches.size() == 1 && batches.get(0).getCount() == 1, "exactly one Recovery Batch: " + batches);
                    JobLedger owned = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
                    h.assertTrue(owned.inputs().size() == 4 && owned.physical(), "it owns the four ready portions: " + owned.inputs().size());
                    long loose = drops.stream().map(ItemEntity::getItem).filter(s -> PotionUtils.getPotion(s) == Potions.SWIFTNESS).mapToInt(ItemStack::getCount).sum();
                    h.assertTrue(loose == 2, "the two bottled potions dropped loose, no more: " + loose);
                    drops.forEach(Entity::discard);
                    h.setBlock(STATION, ModBlocks.BREWERS_KETTLE.get());
                    BrewersKettleBlockEntity fresh = at(h);
                    fresh.inventory().setStackInSlot(PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
                    h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installing consumed the batch");
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    TestSupport.unseal(player, fresh);
                    h.assertTrue(TestSupport.hand(player, fresh).batch(WorkshopParts.K_SPOUT, WorkshopAction.FILL, -1).accepted(), "the rest fill in turn");
                    JOURNAL.put(player.getUUID(), journal);
                })
                .thenWaitUntil(() -> {
                    BrewersKettleBlockEntity fresh = at(h);
                    h.assertTrue(fresh != null && fresh != kettle && fresh.ledger() == null, "still bottling");
                })
                .thenExecute(() -> {
                    BrewersKettleBlockEntity fresh = at(h);
                    h.assertTrue(swiftnessIn(fresh) == 4, "four more Swiftness potions: " + swiftnessIn(fresh));
                    h.assertTrue(TestSupport.results(h, STATION) == 1, "no second completion: " + TestSupport.results(h, STATION));
                    h.assertTrue(fresh.inventory().getStackInSlot(PADDLE).getDamageValue() == 0, "the paddle did not wear again");
                    int journal = com.otectus.immersivebrewing.guide.PlayerGuide.data(player).getList("history", 10).size();
                    h.assertTrue(journal == JOURNAL.get(player.getUUID()), "no second journal entry");
                })
                .thenSucceed();
    }

    /**
     * Handoff 14: output space that disappears before a transfer costs nothing. The ready portion stays owned by the
     * batch, the player is told why, and it bottles once the position is clear.
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void anOccupiedPositionPausesBottlingWithoutADebit(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Blocked", () -> false);
        TestSupport.driveKettle(h, player, KettleTender.competent(), () -> kettle.liveProcess() == null || !kettle.liveProcess().finalized());
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(kettle.liveProcess() != null && kettle.liveProcess().finalized(), "still reacting"))
                .thenExecute(() -> {
                    // Something else lands in the first bottle's position before it is filled.
                    kettle.inventory().setStackInSlot(RACK, new ItemStack(Items.GLASS_BOTTLE));
                    var hand = TestSupport.hand(player, kettle);
                    var refused = hand.act(WorkshopParts.K_RACK, WorkshopAction.FILL, -1);
                    h.assertTrue(!refused.accepted() && refused.reason() != null
                            && refused.reason().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                            && "workshop.immersive_brewing.kettle.slot_occupied".equals(t.getKey()), "refused with its reason: " + refused);
                    h.assertTrue(kettle.ledger().inputs().size() == 3 && kettle.liveProcess().portions() == 3, "nothing was debited");
                    h.assertTrue(kettle.inventory().getStackInSlot(RACK).is(Items.GLASS_BOTTLE), "the foreign stack is untouched");
                    kettle.inventory().setStackInSlot(RACK, ItemStack.EMPTY);
                    h.assertTrue(hand.act(WorkshopParts.K_RACK, WorkshopAction.FILL, -1).accepted(), "once clear, it fills");
                    h.assertTrue(swiftnessIn(kettle) == 1 && kettle.liveProcess().portions() == 2, "exactly one portion moved");
                    h.assertTrue(TestSupport.results(h, STATION) == 1, "the completion stayed single");
                })
                .thenSucceed();
    }

    private static final java.util.Map<java.util.UUID, Integer> JOURNAL = new java.util.concurrent.ConcurrentHashMap<>();

    private static int swiftnessIn(BrewersKettleBlockEntity kettle) {
        int n = 0;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(RACK + i);
            if (PotionUtils.getPotion(s) == Potions.SWIFTNESS && BrewingMetadata.read(s).isPresent()) n += s.getCount();
        }
        return n;
    }
}
