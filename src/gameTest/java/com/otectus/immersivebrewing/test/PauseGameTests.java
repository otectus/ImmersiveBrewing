package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.network.packet.PauseSessionC2S;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The help overlay pauses a productive session (playtest feedback: the games kept running while players read the
 * help). The controller's pause stops the server's clock, count-in included, and the resume restarts it from the
 * client's own timing, at one step per tick. Only the controller can pause, a repeated toggle or an impossible resume is
 * a violation, a flood of toggles seals, and a session sealed while paused keeps its progress.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PauseGameTests {
    private static final String CLOSED = "session.immersive_brewing.end.closed";

    private static FakePlayer brewing(GameTestHelper h, String name) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, name);
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the session started");
        return player;
    }

    private static long now(GameTestHelper h) {
        return h.getLevel().getServer().getTickCount();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void helpStopsTheCountInAndTheRun(GameTestHelper h) {
        FakePlayer player = brewing(h, "Reader");
        ActiveSession s = TestSupport.session(player).orElseThrow();
        TestSupport.driveKettle(h, player, KettleBot.competent());
        int initial = s.sim().step();
        long countIn = s.runStartTick() - now(h);
        // The tutorial opens with the game screen, before the count-in has moved; the client's whole count-in is left.
        SessionManager.pause(player, s.id(), true, initial, (int) countIn);
        h.runAfterDelay(countIn + 40, () -> {
            h.assertTrue(s.paused() && !s.running() && s.sim().step() == initial, "a paused count-in never ends");
            SessionManager.pause(player, s.id(), false, initial, (int) countIn);
            long left = s.runStartTick() - now(h);
            h.assertTrue(left == countIn + SessionManager.RESUME_SLACK, "the count-in resumes with what the client has left: " + left + " for " + countIn);
            h.runAfterDelay(left + s.bufferTicks() + 30, () -> {
                int run = s.sim().step() - initial;
                h.assertTrue(s.running() && run >= 29 && run <= 31, "then the run starts on time: " + run);
                int at = s.sim().step();
                SessionManager.pause(player, s.id(), true, at, 0);
                h.runAfterDelay(60, () -> {
                    h.assertTrue(s.sim().step() == at, "a paused run does not step: " + s.sim().step() + " vs " + at);
                    h.assertTrue(s.violations() == 0, "frames that kept arriving while paused are no violation");
                    // The driver has sent frames up to a playout buffer ahead, so its next step is the one after those.
                    SessionManager.pause(player, s.id(), false, at + s.bufferTicks() + 1, 0);
                    h.runAfterDelay(20, () -> {
                        int advanced = s.sim().step() - at;
                        h.assertTrue(advanced >= 18 && advanced <= 21, "after the resume, one step per tick with no catch-up burst: " + advanced);
                        h.assertTrue(s.violations() == 0, "an honest resume is no violation");
                        SessionManager.leave(player, s.id(), CLOSED);
                        h.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty")
    public static void onlyTheControllerPausesAndRepeatsAreViolations(GameTestHelper h) {
        FakePlayer owner = brewing(h, "Owner");
        FakePlayer viewer = TestSupport.player(h, "Viewer");
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TestSupport.STATION));
        TestSupport.openKettle(viewer, kettle);
        ActiveSession s = TestSupport.session(owner).orElseThrow();

        int step = s.sim().step();
        SessionManager.pause(viewer, s.id(), true, step, 0);
        h.assertTrue(!s.paused() && s.violations() == 0, "a viewer can not pause another player's session");
        SessionManager.pause(owner, UUID.randomUUID(), true, step, 0);
        h.assertTrue(!s.paused() && s.violations() == 0, "a stale or guessed session id is ignored, not charged");
        SessionManager.pause(owner, s.id(), false, step, 0);
        h.assertTrue(!s.paused() && s.violations() == 1, "resuming a session that is not paused is a violation");
        SessionManager.pause(owner, s.id(), true, step, 0);
        SessionManager.pause(owner, s.id(), true, step, 0);
        h.assertTrue(s.paused() && s.violations() == 2, "a repeated pause is a violation and changes nothing");
        SessionManager.pause(owner, s.id(), false, step + 10_000, Integer.MAX_VALUE);
        long start = s.runStartTick() - now(h);
        h.assertTrue(!s.paused() && s.violations() == 3 && start > 0 && start <= 200 + SessionManager.RESUME_SLACK,
                "an impossible resume is clamped and charged: " + start);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new PauseSessionC2S(s.id(), false, 1234, 56).encode(buf);
        PauseSessionC2S decoded = PauseSessionC2S.decode(buf);
        h.assertTrue(decoded.equals(new PauseSessionC2S(s.id(), false, 1234, 56)) && buf.readableBytes() == 0, "the packet round-trips");

        // Toggles faster than anyone reads still apply, so the clocks agree, but a flood seals like any other.
        for (int i = 0; i < 80; i++) SessionManager.pause(owner, s.id(), i % 2 == 0, step, 0);
        h.assertTrue(!s.paused() && s.violations() > 40, "a toggle flood is a violation per toggle: " + s.violations());
        h.runAfterDelay(1, () -> {
            h.assertTrue(TestSupport.session(owner).isEmpty(), "the flood ended the session");
            h.assertTrue("session.immersive_brewing.end.invalid_input".equals(TestSupport.lastEndReason(owner)), "for invalid input");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void aSessionSealedWhilePausedKeepsItsProgress(GameTestHelper h) {
        FakePlayer player = brewing(h, "Sealer");
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TestSupport.STATION));
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.runAfterDelay(120, () -> {
            ActiveSession s = TestSupport.session(player).orElseThrow();
            h.assertTrue(s.running() && s.sim().step() > 20, "running with progress before the pause");
            int at = s.sim().step();
            SessionManager.pause(player, s.id(), true, at, 0);
            h.runAfterDelay(30, () -> {
                SessionManager.leave(player, s.id(), CLOSED);
                h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED, "Escape over the help seals the batch");
                int sealed = SimHandles.create(SessionKind.BREWING, kettle.ledger().params(), kettle.ledger().simState()).step();
                h.assertTrue(sealed == at, "at the step the pause stopped at: " + sealed + " vs " + at);
                TestSupport.openKettle(player, kettle);
                kettle.resume(player);
                ActiveSession resumed = TestSupport.session(player).orElseThrow();
                h.assertTrue(!resumed.paused() && resumed.sim().step() == at, "the resumed session runs again from there");
                SessionManager.leave(player, resumed.id(), CLOSED);
                h.succeed();
            });
        });
    }

    private PauseGameTests() {}
}
