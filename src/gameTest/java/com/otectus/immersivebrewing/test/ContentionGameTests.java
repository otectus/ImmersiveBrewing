package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * Cooperative work (handoff 16): shared observation, one hand per tool, one transaction per dose and per bottle, and no
 * steering of someone else's work. Two players can help at one kettle without multiplying anything; stale or competing
 * requests fail harmlessly.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContentionGameTests {
    private static final int PADDLE_PART = com.otectus.immersivebrewing.workshop.WorkshopParts.K_PADDLE;

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void twoPlayersOneKettle(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer alice = TestSupport.player(h, "Alice");
        FakePlayer bob = TestSupport.player(h, "Bob");
        var key = TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key();
        TestSupport.openKettle(alice, kettle);
        kettle.startBrew(alice, key, 1F, 1F);
        java.util.UUID job = kettle.ledger().jobId();
        TestSupport.openKettle(bob, kettle);
        kettle.startBrew(bob, key, 1F, 1F);
        h.assertTrue(kettle.ledger().jobId().equals(job), "a second set-out is refused and changes nothing");
        var a = TestSupport.hand(alice, kettle);
        var b = TestSupport.hand(bob, kettle);
        TestSupport.openView(bob, kettle);
        h.assertTrue(a.batch(com.otectus.immersivebrewing.workshop.WorkshopParts.K_RACK, com.otectus.immersivebrewing.workshop.WorkshopAction.POUR, -1).accepted(),
                "Alice pours");
        h.assertTrue(a.act(PADDLE_PART, com.otectus.immersivebrewing.workshop.WorkshopAction.GRAB, 0).accepted(), "Alice takes the paddle");
        var refused = b.act(PADDLE_PART, com.otectus.immersivebrewing.workshop.WorkshopAction.GRAB, 0);
        h.assertTrue(!refused.accepted() && refused.reason() != null, "Bob is told who holds the paddle and can not take it");
        b.sample(PADDLE_PART, 0.5, 0, true, com.otectus.immersivebrewing.workshop.Effort.FIRM);
        // The batch pours at the visible pace (a short lead, then one bottle every POUR_PACE ticks): three are in by 45.
        h.runAfterDelay(11 + 3 * BrewersKettleBlockEntity.POUR_PACE, () -> {
            var p = kettle.liveProcess();
            h.assertTrue(p.allCharged(), "the bottles went in once");
            // Bob helps at another control while Alice holds the paddle: one live drag lease, discrete changes serialise.
            h.assertTrue(b.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BURNER, com.otectus.immersivebrewing.workshop.WorkshopAction.SET_CONTROL, 5).accepted(),
                    "Bob turns the burner while Alice stirs");
            h.assertTrue(a.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BURNER, com.otectus.immersivebrewing.workshop.WorkshopAction.GRAB, 0).accepted(),
                    "Alice takes the burner control for a live drag");
            h.assertTrue(!b.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BURNER, com.otectus.immersivebrewing.workshop.WorkshopAction.SET_CONTROL, 8).accepted()
                    && p.burner() == 5, "a control under another's live drag refuses a competing change");
            a.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BURNER, com.otectus.immersivebrewing.workshop.WorkshopAction.RELEASE, 0);
        });
        h.runAfterDelay(120, () -> {
            var p = kettle.liveProcess();
            // Both reach for the same dose in the same instant: exactly one transfer.
            var first = a.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_CUP, com.otectus.immersivebrewing.workshop.WorkshopAction.DOSE, 0);
            var second = b.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_CUP, com.otectus.immersivebrewing.workshop.WorkshopAction.DOSE, 0);
            h.assertTrue(first.accepted() && !second.accepted(), "exactly one of the two doses was accepted");
            h.assertTrue(p.doses() == 1, "one dose, once");
            h.assertTrue(kettle.ledger().assistants().contains("Bob") && "Alice".equals(kettle.ledger().brewerName()),
                    "Bob's productive help is recorded; Alice stays the brewer");
        });
        // Alice tends the rest of the batch once the race is over.
        TestSupport.driveKettle(h, alice, com.otectus.immersivebrewing.testing.KettleTender.competent(), () -> h.getTick() > 125);
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing: " + TestSupport.describe(kettle));
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 3, "exactly three potions, nothing multiplied");
            h.assertTrue(TestSupport.results(h, TestSupport.STATION) == 1, "one completion");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void twoPlayersCanNotBothCollectOneBottle(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 1, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer alice = TestSupport.player(h, "Filler");
        FakePlayer bob = TestSupport.player(h, "Grabber");
        TestSupport.openKettle(alice, kettle);
        kettle.startBrew(alice, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        TestSupport.openView(bob, kettle);
        TestSupport.driveKettle(h, alice, com.otectus.immersivebrewing.testing.KettleTender.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 1, "the bottle waits in the rack: " + TestSupport.describe(kettle)
                    + "; Filler holds " + alice.getInventory().countItem(Items.POTION) + ", Grabber holds " + bob.getInventory().countItem(Items.POTION));
            var a = TestSupport.hand(alice, kettle);
            var b = TestSupport.hand(bob, kettle);
            var first = a.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_RACK, com.otectus.immersivebrewing.workshop.WorkshopAction.TAKE, 0);
            var second = b.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_RACK, com.otectus.immersivebrewing.workshop.WorkshopAction.TAKE, 0);
            h.assertTrue(first.accepted() && !second.accepted(), "the bottle is taken once; the stale take fails harmlessly: "
                    + first.kind() + " " + (first.reason() == null ? "" : first.reason().getString()) + " / " + second.kind()
                    + "; " + TestSupport.describe(kettle) + ", view " + TestSupport.workingAt(alice));
            int total = alice.getInventory().countItem(Items.POTION) + bob.getInventory().countItem(Items.POTION)
                    + TestSupport.count(kettle, RACK, 6, Items.POTION);
            h.assertTrue(total == 1, "one potion in the world: " + total);
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void onePlayerCanTendTwoStations(GameTestHelper h) {
        BrewersKettleBlockEntity first = TestSupport.kettle(h);
        TestSupport.load(first, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        BlockPos secondPos = new BlockPos(5, 1, 4);
        h.setBlock(secondPos, ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity second = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(secondPos));
        TestSupport.load(second, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Juggler");
        TestSupport.openKettle(player, first);
        first.startBrew(player, TestSupport.plan(h, first, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        TestSupport.openKettle(player, second);
        second.startBrew(player, TestSupport.plan(h, second, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(first.ledger() != null && second.ledger() != null && !first.ledger().jobId().equals(second.ledger().jobId()),
                "two stations hold two independent batches");
        h.assertTrue(TestSupport.workingAt(player) != null && TestSupport.workingAt(player).equals(second.getBlockPos()),
                "the working view follows the player to the second kettle");
        var stale = new com.otectus.immersivebrewing.testing.WorkshopHand(player, first.getBlockPos())
                .act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.SEAL, 0);
        h.assertTrue(!stale.accepted() && first.ledger().state() == com.otectus.immersivebrewing.batch.JobState.RUNNING,
                "an intent for a station whose view is not open does nothing");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aGuessedPositionDoesNothing(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer stranger = TestSupport.player(h, "Stranger");
        // No menu open: station packets are accepted only from a player working at that station.
        h.assertTrue(com.otectus.immersivebrewing.menu.StationMenu.openStation(stranger, kettle.getBlockPos()) == null,
                "a player without the menu open is not working at the station");
        TestSupport.openKettle(stranger, kettle);
        h.assertTrue(com.otectus.immersivebrewing.menu.StationMenu.openStation(stranger, kettle.getBlockPos().above()) == null,
                "nor at a position other than the open menu's");
        stranger.setPos(stranger.getX() + 30, stranger.getY(), stranger.getZ());
        h.assertTrue(com.otectus.immersivebrewing.menu.StationMenu.openStation(stranger, kettle.getBlockPos()) == null,
                "nor from out of reach with the menu still open");
        h.succeed();
    }

    private ContentionGameTests() {}
}
