package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.KettleSetupService;
import com.otectus.immersivebrewing.interaction.StationOffsets;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopIntents;
import com.otectus.immersivebrewing.interaction.WorkshopPresence;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.network.packet.WorkshopAckS2C;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.testing.WorkshopHand;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * The close-up workshop's server contracts (1.2.0): the kettle's recipe page is a pure preview that a tipped bottle
 * accepts exactly as shown; several routes are offered as pages, never chosen silently; a changed page refuses and
 * consumes nothing; lifting a bottle or cup is an exclusive presentation lease that moves nothing; commits are
 * acknowledged by sequence; an intent from an earlier working view is stale; an ordinary use loads one unit; the working
 * view moves to a neighbour only through the station's own checks; batch transfers run at the visible pace and leave a
 * held bottle to its holder; the Alembic's fitting chooses the operation the charge commits.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CloseUpWorkshopGameTests {
    private static final ItemStack SWIFTNESS = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);

    private static BrewersKettleBlockEntity staged(GameTestHelper h, ItemStack... tray) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, tray);
        return kettle;
    }

    private static KettleSetupService.Preview page(BrewersKettleBlockEntity kettle) {
        kettle.setup().refresh();
        return kettle.setup().preview();
    }

    private static int count(BrewersKettleBlockEntity kettle, int from, int size) {
        int n = 0;
        for (int i = from; i < from + size; i++) n += kettle.inventory().getStackInSlot(i).getCount();
        return n;
    }

    @GameTest(template = "empty")
    public static void theRecipePageIsAPurePreviewOfTheStagedBatch(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        int fuel = kettle.fuelCharges();
        KettleSetupService.Preview page = page(kettle);
        h.assertTrue(page.status() == KettleSetupService.Status.READY, "one route uses everything staged: " + page.status());
        h.assertTrue(page.routes().size() == 1 && ItemStack.isSameItemSameTags(page.shown().plan().output(), SWIFTNESS),
                "the page shows Swiftness");
        h.assertTrue(page.servings() == 3 && page.shown().plan().steps().size() == 2, "three servings in two steps");
        long revision = page.revision();
        h.assertTrue(page(kettle).revision() == revision, "reading the page again changes nothing");
        h.assertTrue(kettle.ledger() == null && count(kettle, RACK, 6) == 3 && count(kettle, TRAY, 6) == 2 && kettle.fuelCharges() == fuel,
                "previewing consumed nothing");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tippingAStagedBottleStartsExactlyTheShownBatch(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Brewer");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        long revision = page(kettle).revision();
        h.assertTrue(hand.act(WorkshopParts.K_RACK, WorkshopAction.GRAB, 0).accepted(), "the bottle is lifted (nothing moves yet)");
        h.assertTrue(kettle.ledger() == null && count(kettle, RACK, 6) == 3, "lifting reserved nothing");
        WorkshopResult tipped = hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, false, true, revision);
        h.assertTrue(tipped.accepted(), "tipping it in sets out the batch: " + tipped.reason());
        h.assertTrue(kettle.ledger() != null && kettle.ledger().operation() == Operation.BREW
                && ItemStack.isSameItemSameTags(kettle.ledger().outputSnapshot().copyWithCount(1), SWIFTNESS), "the batch is the page's");
        KettleProcess p = kettle.liveProcess();
        h.assertTrue(p.count(KettleProcess.Portion.CHARGED) == 1 && p.portion(p.portionAtSlot(0)) == KettleProcess.Portion.CHARGED,
                "the tipped bottle is the one poured");
        h.assertTrue(kettle.buildWorkshopSnapshot().getList("held", 10).isEmpty(), "the carry ended with the pour");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aChangedPageRefusesAndConsumesNothing(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Reader");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        long read = page(kettle).revision();
        // Someone takes the sugar after the page was read: the page now shows Awkward, a different batch.
        kettle.inventory().setStackInSlot(TRAY + 1, ItemStack.EMPTY);
        WorkshopResult stale = hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, false, true, read);
        h.assertTrue(!stale.accepted() && stale.reason() != null, "a stale page is refused with a reason");
        h.assertTrue(kettle.ledger() == null && count(kettle, RACK, 6) == 3 && count(kettle, TRAY, 6) == 1, "and nothing was consumed");
        h.assertTrue(page(kettle).revision() != read, "the page shows the change");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void equalRoutesAreOfferedAsPagesNeverChosenByRegistryOrder(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR), new ItemStack(Items.SPIDER_EYE));
        FakePlayer player = TestSupport.player(h, "Chooser");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        KettleSetupService.Preview page = page(kettle);
        h.assertTrue(page.status() == KettleSetupService.Status.CHOOSE && page.routes().size() == 2, "two equal routes: " + page.status());
        int swiftness = -1;
        for (int i = 0; i < page.routes().size(); i++) if (ItemStack.isSameItemSameTags(page.routes().get(i).plan().output(), SWIFTNESS)) swiftness = i;
        h.assertTrue(swiftness >= 0, "Swiftness is one of the pages");
        if (page.page() != swiftness) {
            long before = page.revision();
            h.assertTrue(hand.act(WorkshopParts.K_BOOK, WorkshopAction.SETUP_PAGE, swiftness).accepted(), "the page turns");
            h.assertTrue(page(kettle).revision() != before && page(kettle).page() == swiftness, "a turned page is a new revision");
        }
        long revision = page(kettle).revision();
        h.assertTrue(hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, false, true, revision).accepted(), "the shown page is set out");
        h.assertTrue(ItemStack.isSameItemSameTags(kettle.ledger().outputSnapshot().copyWithCount(1), SWIFTNESS), "exactly the page shown");
        h.assertTrue(kettle.inventory().getStackInSlot(TRAY + 2).is(Items.SPIDER_EYE), "the other route's ingredient stays on the tray");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aMissingSupplyShowsWhereItBelongs(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 0, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        KettleSetupService.Preview page = page(kettle);
        h.assertTrue(page.status() == KettleSetupService.Status.MISSING, "no fuel, no batch: " + page.status());
        h.assertTrue(page.ghosts().stream().anyMatch(g -> g.part() == WorkshopParts.K_FUEL && g.item().is(Items.BLAZE_POWDER)),
                "the missing fuel is shown at the burner");
        FakePlayer player = TestSupport.player(h, "Early");
        TestSupport.openView(player, kettle);
        WorkshopResult lift = TestSupport.hand(player, kettle).act(WorkshopParts.K_RACK, WorkshopAction.GRAB, 0);
        h.assertTrue(!lift.accepted() && lift.reason() != null, "a bottle can not be lifted to begin a batch the page can not start");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aCarryIsOneHandsAndMovesNothingUntilTipped(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer alice = TestSupport.player(h, "Alice");
        FakePlayer bob = TestSupport.player(h, "Bob");
        TestSupport.openKettle(alice, kettle);
        kettle.startBrew(alice, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        TestSupport.openView(bob, kettle);
        WorkshopHand a = TestSupport.hand(alice, kettle), b = TestSupport.hand(bob, kettle);
        h.assertTrue(b.act(WorkshopParts.K_RACK + 2, WorkshopAction.GRAB, 0).accepted(), "Bob lifts the third bottle");
        WorkshopResult grab = a.act(WorkshopParts.K_RACK + 2, WorkshopAction.GRAB, 0);
        h.assertTrue(!grab.accepted() && grab.reason() != null, "Alice is told Bob holds it");
        h.assertTrue(!a.act(WorkshopParts.K_RACK + 2, WorkshopAction.POUR, 0).accepted(), "and can not pour it from under him");
        // The batch pours every bottle nobody holds, at the visible pace; Bob's waits for him.
        h.assertTrue(a.batch(WorkshopParts.K_RACK, WorkshopAction.POUR, -1).accepted(), "Alice pours the rest as a batch");
        // Bob keeps holding it (his client sends the carried bottle's position, which keeps the carry alive).
        h.runAfterDelay(30, () -> b.sample(WorkshopParts.K_RACK + 2, 0.1, -1.1, false, Effort.NORMAL));
        h.runAfterDelay(BrewersKettleBlockEntity.POUR_LEAD + 3 * BrewersKettleBlockEntity.POUR_PACE + 4, () -> {
            KettleProcess p = kettle.liveProcess();
            h.assertTrue(p.portion(p.portionAtSlot(0)) == KettleProcess.Portion.CHARGED && p.portion(p.portionAtSlot(1)) == KettleProcess.Portion.CHARGED,
                    "the free bottles went in");
            h.assertTrue(p.portion(p.portionAtSlot(2)) == KettleProcess.Portion.STAGED, "the held one is still Bob's to tip");
            h.assertTrue(b.act(WorkshopParts.K_RACK + 2, WorkshopAction.POUR, 0).accepted() && p.allCharged(), "Bob tips it in, once");
            h.assertTrue(!b.act(WorkshopParts.K_RACK + 2, WorkshopAction.POUR, 0).accepted(), "and can not tip it twice");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void disconnectingWhileCarryingLeavesTheBottleWhereItWas(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer alice = TestSupport.player(h, "Alice");
        TestSupport.openKettle(alice, kettle);
        kettle.startBrew(alice, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        WorkshopHand a = TestSupport.hand(alice, kettle);
        h.assertTrue(a.act(WorkshopParts.K_RACK + 1, WorkshopAction.GRAB, 0).accepted(), "lifted");
        WorkshopPresence.drop(alice);
        KettleProcess p = kettle.liveProcess();
        h.assertTrue(p.portion(p.portionAtSlot(1)) == KettleProcess.Portion.STAGED, "before a commit, the serving stays at its source");
        h.assertTrue(kettle.buildWorkshopSnapshot().getList("held", 10).isEmpty(), "and the carry is gone with the player");
        h.assertTrue(kettle.ledger().inputs().size() == 3, "no serving was lost or duplicated");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void commitsAreAcknowledgedByTheirSequence(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Acker");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        int sequence = hand.nextSequence();
        long revision = page(kettle).revision();
        h.assertTrue(hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, false, true, revision).accepted(), "accepted");
        int refusedSequence = hand.nextSequence();
        h.assertTrue(!hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, false, false, WorkshopIntent.NO_REVISION).accepted(), "the same serving again");
        var acks = TestSupport.messages(player).stream().filter(m -> m instanceof WorkshopAckS2C).map(m -> (WorkshopAckS2C) m).toList();
        h.assertTrue(acks.stream().anyMatch(a -> a.sequence() == sequence && a.accepted()), "the accepted commit is acknowledged as accepted");
        h.assertTrue(acks.stream().anyMatch(a -> a.sequence() == refusedSequence && !a.accepted()), "the repeated one is answered too, not accepted");
        // A replayed envelope (an old sequence) is answered as ignored and changes nothing.
        WorkshopIntentC2S replay = hand.withFlags(hand.envelope(sequence, WorkshopParts.K_RACK + 1, WorkshopAction.POUR, 0, 0, 0, false, Effort.NORMAL, false),
                WorkshopIntentC2S.ACK, WorkshopIntent.NO_REVISION);
        h.assertTrue(WorkshopIntents.receive(player, replay).kind() == WorkshopResult.Kind.IGNORED, "a replay is ignored");
        h.assertTrue(kettle.liveProcess().count(KettleProcess.Portion.CHARGED) == 1, "and pours nothing");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anIntentFromAnEarlierViewIsStale(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Reopener");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, SWIFTNESS).key(), 1F, 1F);
        int first = WorkshopPresence.epoch(player.getUUID());
        TestSupport.openView(player, kettle);
        int second = WorkshopPresence.epoch(player.getUUID());
        h.assertTrue(second != first && second != WorkshopIntent.ANY_EPOCH, "a reopened view has a new epoch");
        WorkshopHand hand = TestSupport.hand(player, kettle);
        int seq = hand.nextSequence();
        hand.skipTo(seq);
        WorkshopIntentC2S old = hand.withEpoch(hand.envelope(seq, WorkshopParts.K_LID, WorkshopAction.SEAL, 0, 0, 0, false, Effort.NORMAL, false), first);
        h.assertTrue(WorkshopIntents.receive(player, old).kind() == WorkshopResult.Kind.IGNORED && kettle.ledger().state() == JobState.RUNNING,
                "a seal sent from the earlier view does nothing");
        WorkshopIntentC2S oldLeave = hand.withEpoch(hand.envelope(seq + 1, 0, WorkshopAction.LEAVE, 0, 0, 0, false, Effort.NORMAL, false), first);
        hand.skipTo(seq + 1);
        WorkshopIntents.receive(player, oldLeave);
        h.assertTrue(WorkshopPresence.at(player, kettle.getBlockPos()), "nor does a late leave close the view that replaced it");
        h.assertTrue(hand.act(WorkshopParts.K_LID, WorkshopAction.SEAL, 0).accepted(), "the current view still acts");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anOrdinaryUseLoadsOneUnitAndTheBatchActionTheRest(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "Loader");
        ItemStack left = TestSupport.use(player, kettle, new ItemStack(Items.SUGAR, 5));
        h.assertTrue(left.getCount() == 4 && count(kettle, TRAY, 6) == 1, "one use, one sugar: " + left.getCount() + " left");
        TestSupport.openView(player, kettle);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SUGAR, 4));
        WorkshopHand hand = TestSupport.hand(player, kettle);
        h.assertTrue(hand.act(WorkshopParts.K_TRAY + 1, WorkshopAction.INSERT, 0).accepted() && count(kettle, TRAY, 6) == 2
                && player.getMainHandItem().getCount() == 3, "a click in the view places one");
        h.assertTrue(hand.batch(WorkshopParts.K_TRAY + 1, WorkshopAction.INSERT, 0).accepted() && count(kettle, TRAY, 6) == 5
                && player.getMainHandItem().isEmpty(), "the batch action places the rest it names");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theViewMovesToANeighbourOnlyThroughItsOwnChecks(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, STATION.east());
        FakePlayer player = TestSupport.player(h, "Walker");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        h.assertTrue(hand.act(WorkshopParts.K_RACK, WorkshopAction.GRAB, 0).accepted(), "a bottle is lifted at the kettle");
        int packed = StationOffsets.pack(kettle.getBlockPos(), mortar.getBlockPos());
        h.assertTrue(hand.act(0, WorkshopAction.SWITCH_STATION, packed).accepted(), "the view goes over to the mortar");
        h.assertTrue(WorkshopPresence.at(player, mortar.getBlockPos()) && !WorkshopPresence.at(player, kettle.getBlockPos()), "now at the mortar");
        h.assertTrue(kettle.buildWorkshopSnapshot().getList("held", 10).isEmpty(), "nothing held at the kettle travelled with the player");
        WorkshopHand atMortar = TestSupport.hand(player, mortar);
        int nowhere = StationOffsets.pack(mortar.getBlockPos(), mortar.getBlockPos().above(2));
        WorkshopResult empty = atMortar.act(0, WorkshopAction.SWITCH_STATION, nowhere);
        h.assertTrue(!empty.accepted() && WorkshopPresence.at(player, mortar.getBlockPos()), "there is no station there: the view stays");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theBatchShortcutSetsOutAndPoursAtTheVisiblePace(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = staged(h, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Hurried");
        TestSupport.openView(player, kettle);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        long revision = page(kettle).revision();
        h.assertTrue(hand.commit(WorkshopParts.K_RACK, WorkshopAction.POUR, 0, true, true, revision).accepted(), "set out and pour all");
        h.assertTrue(kettle.ledger() != null && kettle.liveProcess().count(KettleProcess.Portion.STAGED) == 3, "nothing poured before the lead");
        h.runAfterDelay(BrewersKettleBlockEntity.POUR_LEAD + 2, () -> h.assertTrue(kettle.liveProcess().count(KettleProcess.Portion.CHARGED) == 1,
                "the first bottle after the lead"));
        h.runAfterDelay(BrewersKettleBlockEntity.POUR_LEAD + 3 * BrewersKettleBlockEntity.POUR_PACE + 4, () -> {
            h.assertTrue(kettle.liveProcess().allCharged(), "all three in, once each");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void theAlembicFittingChoosesWhatTheChargeCommits(GameTestHelper h) {
        AlembicBlockEntity alembic = AlembicGameTests.load(h, 2, AlembicGameTests.copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH), 3));
        FakePlayer player = TestSupport.player(h, "Distiller");
        TestSupport.openView(player, alembic);
        WorkshopHand hand = TestSupport.hand(player, alembic);
        var snap = alembic.buildWorkshopSnapshot();
        h.assertTrue(!snap.getBoolean("reclaimMode") && snap.contains("previewOut") && snap.contains("reclaimOut"),
                "both operations' outcomes are previewed before anything is used");
        h.assertTrue(hand.act(WorkshopParts.A_MODE, WorkshopAction.SET_CONTROL, 1).accepted(), "the fitting turns to reclaim");
        h.assertTrue(alembic.buildWorkshopSnapshot().getBoolean("reclaimMode") && alembic.ledger() == null, "turning it charges nothing");
        h.assertTrue(hand.act(WorkshopParts.A_CHARGE, WorkshopAction.FINISH, 0).accepted(), "the charge commits the fitting's operation");
        h.assertTrue(alembic.ledger() != null && alembic.ledger().operation() == Operation.RECLAIM, "a reclamation, never a purification");
        h.assertTrue(!hand.act(WorkshopParts.A_MODE, WorkshopAction.SET_CONTROL, 0).accepted() || alembic.ledger().operation() == Operation.RECLAIM,
                "a running job's operation can not be switched");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void leavingTheStillNeverReclaimsABatch(GameTestHelper h) {
        AlembicBlockEntity alembic = AlembicGameTests.load(h, 2, AlembicGameTests.copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH), 3));
        FakePlayer player = TestSupport.player(h, "Leaver");
        TestSupport.openView(player, alembic);
        WorkshopHand hand = TestSupport.hand(player, alembic);
        hand.act(WorkshopParts.A_MODE, WorkshopAction.SET_CONTROL, 1);
        hand.act(0, WorkshopAction.LEAVE, 0);
        h.assertTrue(alembic.ledger() == null, "leaving with the fitting on reclaim started nothing");
        h.assertTrue(alembic.inventory().getStackInSlot(AlembicBlockEntity.INPUT).is(Items.POTION), "the bottles are where they were");
        h.succeed();
    }
}
