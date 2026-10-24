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
import com.otectus.immersivebrewing.session.ActiveSession;
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
 * Milestone 2 (spec 20.1 invariant 1, 21.1): one controller per station and one session per player; a second player at
 * the same station can watch and later resume, never steer, start, insert into the job or end someone else's session.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContentionGameTests {
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void twoPlayersOneKettle(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART, 2), new ItemStack(Items.SUGAR, 2));
        FakePlayer alice = TestSupport.player(h, "Alice");
        FakePlayer bob = TestSupport.player(h, "Bob");
        TestSupport.openKettle(alice, kettle);
        TestSupport.openKettle(bob, kettle);
        PlanKey key = TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key();
        kettle.startBrew(alice, key, 1F, 1F);
        ActiveSession aliceSession = TestSupport.session(alice).orElseThrow();
        UUID job = kettle.ledger().jobId();

        kettle.startBrew(bob, key, 1F, 1F);
        h.assertTrue(TestSupport.session(bob).isEmpty() && kettle.ledger().jobId().equals(job), "a second start is refused and changes nothing");
        TestSupport.openKettle(bob, kettle);
        kettle.handleAction(bob, StationActionC2S.Action.RESUME, 0);
        h.assertTrue(TestSupport.session(bob).isEmpty(), "a viewer can not take over a running station");

        // The job's slots refuse a viewer's items through every path.
        h.assertTrue(!kettle.inventory().isItemValid(RACK + 3, TestSupport.water()), "the rack refuses bottles while the job is open");
        h.assertTrue(!kettle.inventory().isItemValid(TRAY + 2, new ItemStack(Items.SUGAR)), "the tray refuses reagents while the job is open");
        h.assertTrue(!bob.containerMenu.getSlot(RACK + 3).mayPlace(TestSupport.water()), "the menu slot agrees");
        h.assertTrue(kettle.inventory().insertItem(TRAY + 2, new ItemStack(Items.SUGAR), false).getCount() == 1, "insertion is refused, not swallowed");

        // A viewer's packets naming the controller's session do nothing.
        int frame = new KettleInput(2, 8, true).pack();
        SessionManager.receive(bob, new InputFramesC2S(aliceSession.id(), 0, 2, new int[] {frame, frame, frame, frame}));
        h.assertTrue(aliceSession.bufferedFrames() == 0 && aliceSession.violations() == 0, "the viewer's frames were not taken");
        SessionManager.leave(bob, aliceSession.id(), "session.immersive_brewing.end.closed");
        h.assertTrue(TestSupport.session(alice).isPresent(), "a viewer can not close the controller's session");

        TestSupport.driveKettle(h, alice, KettleBot.competent());
        TestSupport.driveKettle(h, bob, KettleBot.competent());
        h.runAfterDelay(200, () -> {
            // Alice walks off; Bob resumes the sealed batch where it stopped and finishes it as an assistant.
            SessionManager.leave(alice, TestSupport.session(alice).orElseThrow().id(), "session.immersive_brewing.end.closed");
            h.assertTrue(kettle.ledger().state() == JobState.SEALED, "sealed when Alice left");
            TestSupport.openKettle(bob, kettle);
            kettle.resume(bob);
            h.assertTrue(TestSupport.session(bob).isPresent(), "Bob resumed the sealed batch");
            TestSupport.openKettle(alice, kettle);
            kettle.resume(alice);
            h.assertTrue(TestSupport.session(alice).isEmpty(), "and Alice can not take it back while he works");
        });
        h.runAfterDelay(300, () -> h.assertTrue(kettle.ledger() != null && kettle.ledger().assistants().contains("Bob"),
                "the ledger records Bob as an assistant"));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(RACK + i);
                h.assertTrue(PotionUtils.getPotion(out) == Potions.SWIFTNESS, "output " + i);
                h.assertTrue("Alice".equals(BrewingMetadata.read(out).orElseThrow().brewerName()), "the original brewer is credited");
            }
            h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 1 && TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 1,
                    "one batch's reagents spent, not two");
        });
    }

    @GameTest(template = "empty")
    public static void onePlayerOneSession(GameTestHelper h) {
        BrewersKettleBlockEntity first = TestSupport.kettle(h);
        TestSupport.load(first, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        BlockPos secondPos = new BlockPos(5, 1, 4);
        h.setBlock(secondPos, ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity second = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(secondPos));
        TestSupport.load(second, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Juggler");
        TestSupport.openKettle(player, first);
        first.startBrew(player, TestSupport.plan(h, first, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the first brew started");
        TestSupport.openKettle(player, second);
        second.startBrew(player, TestSupport.plan(h, second, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(second.ledger() == null, "a player already working can not start a second station");
        h.assertTrue(TestSupport.count(second, RACK, 6, Items.POTION) == 3 && second.fuelCharges() == 0
                && second.inventory().getStackInSlot(BrewersKettleBlockEntity.FUEL).getCount() == 1, "and nothing was reserved there");
        h.assertTrue(SessionManager.at(h.getLevel(), first.getBlockPos()) != null && SessionManager.at(h.getLevel(), second.getBlockPos()) == null,
                "one session, at the first kettle");
        SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
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
