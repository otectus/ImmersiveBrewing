package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.item.RecoveryBatchItem;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
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
 * Milestone 2 (spec 20): what happens to a job around saves, reloads, interruptions, a broken station and a repeated
 * completion. Every test accounts for each bottle, reagent and fuel charge.
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

    /** Starts a three-serving Swiftness brew for a new player (one wart, one sugar, two charges reserved). */
    private static FakePlayer start(GameTestHelper h, BrewersKettleBlockEntity kettle, String name, java.util.function.BooleanSupplier disconnected) {
        FakePlayer player = TestSupport.player(h, name, disconnected);
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the session started");
        return player;
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runningLedgerReloadsSealed(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Saver", () -> false);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        BlockPos pos = h.absolutePos(STATION);
        h.runAfterDelay(220, () -> {
            ActiveSession session = TestSupport.session(player).orElseThrow();
            h.assertTrue(session.running(), "the reaction is running");
            // The world saves mid-session: the live state is flushed into the ledger with it.
            CompoundTag saved = kettle.saveWithFullMetadata();
            int savedStep = session.sim().step();
            h.assertTrue(JobState.RUNNING.name().equals(saved.getCompound("Ledger").getString("state")), "saved while running");
            // The process then dies without sealing and the chunk loads from that save. The old session is closed first
            // so it can not keep driving the replaced block entity.
            SessionManager.leave(player, session.id(), "session.immersive_brewing.end.closed");
            h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, kettle.getBlockState(), saved));
            BrewersKettleBlockEntity loaded = at(h);
            h.assertTrue(loaded != kettle, "a new block entity loaded from the save");
            JobLedger ledger = loaded.ledger();
            h.assertTrue(ledger != null && ledger.state() == JobState.SEALED, "a ledger saved mid-session loads sealed");
            int step = SimHandles.create(SessionKind.BREWING, ledger.params(), ledger.simState()).step();
            h.assertTrue(step == savedStep, "the sealed state is the saved step: " + step + " vs " + savedStep);
            h.assertTrue(ledger.inputs().size() == 3 && ledger.reserved().size() == 2 && ledger.fuelCharges() == 2,
                    "bottles, reagents and fuel are all still in the ledger");
            h.assertTrue(loaded.fuelCharges() == 18, "burner charges kept: " + loaded.fuelCharges());
            h.assertTrue(TestSupport.count(loaded, TRAY, 6, Items.NETHER_WART) == 0 && TestSupport.count(loaded, TRAY, 6, Items.SUGAR) == 0,
                    "no reagent reappeared in the tray");
            TestSupport.openKettle(player, loaded);
            loaded.resume(player);
            h.assertTrue(TestSupport.session(player).isPresent(), "resumed after the reload");
        });
        h.succeedWhen(() -> {
            BrewersKettleBlockEntity loaded = at(h);
            h.assertTrue(loaded != null && loaded != kettle && loaded.ledger() == null, "still brewing");
            h.assertTrue(threeSwiftness(loaded), "exactly three graded Swiftness potions");
        });
    }

    @GameTest(template = "empty")
    public static void reservedLedgerReloadsReleased(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 3));
        FakePlayer player = start(h, kettle, "Reserver", () -> false);
        CompoundTag saved = kettle.saveWithFullMetadata();
        h.assertTrue(JobState.RESERVED.name().equals(saved.getCompound("Ledger").getString("state")), "saved during the count-in");
        SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
        BlockPos pos = h.absolutePos(STATION);
        h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, kettle.getBlockState(), saved));
        BrewersKettleBlockEntity loaded = at(h);
        h.assertTrue(loaded != kettle && loaded.ledger() == null, "a reservation that never started is released when it loads");
        h.assertTrue(TestSupport.count(loaded, RACK, 6, Items.POTION) == 3, "three bottles back in the rack");
        h.assertTrue(TestSupport.count(loaded, TRAY, 6, Items.NETHER_WART) == 2 && TestSupport.count(loaded, TRAY, 6, Items.SUGAR) == 3,
                "every reagent back in the tray, none doubled");
        h.assertTrue(loaded.fuelCharges() == 20 && loaded.inventory().getStackInSlot(BrewersKettleBlockEntity.FUEL).isEmpty(),
                "the burned powder's 20 charges are all in the burner: " + loaded.fuelCharges());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void breakingARunningKettleDropsOneRecoveryBatch(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 2, new ItemStack(Items.NETHER_WART, 3), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Breaker", () -> false);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        BlockPos pos = h.absolutePos(STATION);
        h.runAfterDelay(160, () -> {
            h.assertTrue(TestSupport.session(player).map(ActiveSession::running).orElse(false), "running when broken");
            h.getLevel().destroyBlock(pos, true);
            h.assertTrue(TestSupport.session(player).isEmpty(), "breaking ended the session");
            h.assertTrue("session.immersive_brewing.end.broken".equals(TestSupport.lastEndReason(player)), "the controller was told why");
            List<ItemEntity> drops = TestSupport.drops(h, STATION);
            List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
            h.assertTrue(batches.size() == 1 && batches.get(0).getCount() == 1, "exactly one Recovery Batch: " + batches);
            JobLedger ledger = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
            h.assertTrue(ledger.state() == JobState.SEALED, "the batch holds the sealed job");
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
            TestSupport.openKettle(player, fresh);
            fresh.resume(player);
            h.assertTrue(TestSupport.session(player).isPresent(), "the installed batch resumes");
        });
        h.succeedWhen(() -> {
            BrewersKettleBlockEntity fresh = at(h);
            h.assertTrue(fresh != null && fresh != kettle && fresh.ledger() == null, "still brewing");
            h.assertTrue(threeSwiftness(fresh), "the recovered batch finished as three Swiftness potions");
        });
    }

    @GameTest(template = "empty")
    public static void breakingDuringTheCountInPackagesTheReservation(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, "Hasty", () -> false);
        h.getLevel().destroyBlock(h.absolutePos(STATION), true);
        List<ItemEntity> drops = TestSupport.drops(h, STATION);
        List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
        h.assertTrue(batches.size() == 1, "one Recovery Batch for the reservation");
        JobLedger ledger = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
        h.assertTrue(ledger.state() == JobState.RESERVED && ledger.fuelCharges() == 2, "still a reservation, with its two charges");
        CompoundTag beTag = TestSupport.droppedStacks(drops, ModItems.BREWERS_KETTLE.get()).get(0).getTagElement("BlockEntityTag");
        h.assertTrue(beTag != null && beTag.getInt("FuelCharges") == 18, "the burner's other 18 charges are on the kettle item");
        h.assertTrue(TestSupport.dropped(drops, Items.POTION) == 0 && TestSupport.dropped(drops, Items.SUGAR) == 0, "nothing reserved dropped loose");
        drops.forEach(Entity::discard);

        // A batch that never started releases everything into the station it is installed at.
        h.setBlock(STATION, ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity fresh = at(h);
        h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installing consumed the batch");
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
        SimHandle[] sim = {null};
        h.onEachTick(() -> TestSupport.session(player).ifPresent(s -> sim[0] = s.sim()));
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            h.assertTrue(sim[0] != null && sim[0].finished(), "the session finished");
            // The same finished session completes again (a duplicate tick, a forged packet, a second hook).
            h.assertTrue(kettle.completeJob(player, sim[0]) == null, "a second completion issues nothing");
        }).thenIdle(5).thenExecute(() -> {
            h.assertTrue(threeSwiftness(kettle) && kettle.inventory().getStackInSlot(RACK + 3).isEmpty(), "still exactly three potions");
            h.assertTrue(TestSupport.results(h, STATION) == 1, "the result event fired once: " + TestSupport.results(h, STATION));
            h.assertTrue(kettle.inventory().getStackInSlot(PADDLE).getDamageValue() == 1, "the paddle wore once");
        }).thenSucceed();
    }

    /** Starts a brew, waits for the reaction, applies {@code interrupt} and expects a seal with {@code reason}, progress kept. */
    private static void sealsWhen(GameTestHelper h, String name, boolean[] flag, Consumer<FakePlayer> interrupt, String reason) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = start(h, kettle, name, () -> flag[0]);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        int[] step = {-1};
        h.runAfterDelay(140, () -> {
            ActiveSession session = TestSupport.session(player).orElseThrow();
            h.assertTrue(session.running(), "running before the interruption");
            step[0] = session.sim().step();
            interrupt.accept(player);
        });
        h.runAfterDelay(143, () -> {
            h.assertTrue(TestSupport.session(player).isEmpty(), "the session ended");
            JobLedger ledger = kettle.ledger();
            h.assertTrue(ledger != null && ledger.state() == JobState.SEALED, "the job is sealed, not lost");
            int sealed = SimHandles.create(SessionKind.BREWING, ledger.params(), ledger.simState()).step();
            h.assertTrue(sealed >= step[0] && sealed <= step[0] + 3, "progress kept: " + sealed + " after " + step[0]);
            h.assertTrue(reason.equals(TestSupport.lastEndReason(player)), "reason " + TestSupport.lastEndReason(player));
            h.assertTrue(ledger.inputs().size() == 3 && ledger.reserved().size() == 2, "everything still owned by the job");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void disconnectSeals(GameTestHelper h) {
        boolean[] gone = {false};
        sealsWhen(h, "Leaver", gone, p -> gone[0] = true, "session.immersive_brewing.end.disconnected");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void deathSeals(GameTestHelper h) {
        sealsWhen(h, "Unlucky", new boolean[1], p -> p.setHealth(0F), "session.immersive_brewing.end.died");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void walkingAwaySeals(GameTestHelper h) {
        sealsWhen(h, "Wanderer", new boolean[1], p -> p.setPos(p.getX() + 20, p.getY(), p.getZ()), "session.immersive_brewing.end.range");
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void changingDimensionSeals(GameTestHelper h) {
        sealsWhen(h, "Traveller", new boolean[1], p -> {
            net.minecraft.server.level.ServerLevel other = p.server.getLevel(Level.NETHER);
            if (other == null) other = p.server.getLevel(Level.END);
            h.assertTrue(other != null, "the test server has a second dimension");
            p.setServerLevel(other);
        }, "session.immersive_brewing.end.dimension");
    }

    private LifecycleGameTests() {}
}
