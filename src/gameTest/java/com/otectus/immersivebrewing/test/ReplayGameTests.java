package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.minigame.brewing.AdditionSpec;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleSimulation;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
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
 * Milestone 2 (spec 21.1): the server accepts inputs only. Duplicated, reordered, oversized, invalid, far-future, flooded
 * and forged packets change nothing they should not, count as violations where they are the sender's fault, and a
 * flood seals the batch with its progress kept. A late one-shot action is carried into the next step (ADR-2).
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReplayGameTests {
    private static FakePlayer brewing(GameTestHelper h, String name) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, name);
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the session started");
        return player;
    }

    private static int frame(int draft) {
        return new KettleInput(draft, 0, false).pack();
    }

    @GameTest(template = "empty")
    public static void badPacketsChangeNothing(GameTestHelper h) {
        FakePlayer player = brewing(h, "Replayer");
        ActiveSession s = TestSupport.session(player).orElseThrow();
        UUID id = s.id();
        SessionManager.receive(player, new InputFramesC2S(id, 5, 10, new int[] {frame(2)}));
        h.assertTrue(Integer.valueOf(frame(2)).equals(s.bufferedFrame(10)) && s.violations() == 0, "a good frame is buffered");

        SessionManager.receive(player, new InputFramesC2S(id, 5, 10, new int[] {frame(0)}));
        h.assertTrue(s.violations() == 1 && Integer.valueOf(frame(2)).equals(s.bufferedFrame(10)), "a duplicate is a violation and rewrites nothing");
        SessionManager.receive(player, new InputFramesC2S(id, 3, 11, new int[] {frame(0)}));
        h.assertTrue(s.violations() == 2 && s.bufferedFrame(11) == null, "a reordered (older) packet is a violation and is dropped");
        SessionManager.receive(player, new InputFramesC2S(id, 6, 10, new int[] {frame(0)}));
        h.assertTrue(s.violations() == 2 && Integer.valueOf(frame(2)).equals(s.bufferedFrame(10)), "a newer packet can not rewrite a received step");

        SessionManager.receive(player, new InputFramesC2S(id, 7, 11, new int[9]));
        h.assertTrue(s.violations() == 6 && s.bufferedFrame(11) == null, "an oversized packet is malformed (4 violations), nothing taken");
        SessionManager.receive(player, new InputFramesC2S(id, 8, 11, new int[] {0x7FFF_FFFF}));
        h.assertTrue(s.violations() == 7 && s.bufferedFrame(11) == null, "an invalid frame is a violation");
        SessionManager.receive(player, new InputFramesC2S(id, 9, 500, new int[] {frame(1)}));
        h.assertTrue(s.violations() == 8 && s.bufferedFrame(500) == null, "a frame far in the future is refused");
        SessionManager.receive(player, new InputFramesC2S(id, 10, 11, new int[] {frame(1)}));
        h.assertTrue(Integer.valueOf(frame(1)).equals(s.bufferedFrame(11)), "and does not hide the good frames after it");

        SessionManager.receive(player, new InputFramesC2S(UUID.randomUUID(), 11, 12, new int[] {frame(1)}));
        h.assertTrue(s.violations() == 8 && s.bufferedFrame(12) == null, "a stale or guessed session id is ignored, not charged");

        // The wire format: an oversized count decodes as malformed without allocating or reading past the packet.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(id);
        buf.writeVarInt(12);
        buf.writeVarInt(12);
        buf.writeByte(200);
        for (int i = 0; i < 16; i++) buf.writeVarInt(1);
        InputFramesC2S decoded = InputFramesC2S.decode(buf);
        h.assertTrue(decoded.malformed() && decoded.frames().length == 0 && buf.readableBytes() == 0, "an oversized count decodes as malformed");
        FriendlyByteBuf good = new FriendlyByteBuf(Unpooled.buffer());
        new InputFramesC2S(id, 13, 13, new int[] {frame(1), frame(2)}).encode(good);
        InputFramesC2S roundTrip = InputFramesC2S.decode(good);
        h.assertTrue(!roundTrip.malformed() && roundTrip.frames().length == 2 && roundTrip.firstStep() == 13, "a good packet round-trips");

        SessionManager.leave(player, id, "session.immersive_brewing.end.closed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aViewerCanNotEndOrSteerAnotherSession(GameTestHelper h) {
        FakePlayer owner = brewing(h, "Owner");
        FakePlayer viewer = TestSupport.player(h, "Viewer");
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TestSupport.STATION));
        TestSupport.openKettle(viewer, kettle);
        ActiveSession s = TestSupport.session(owner).orElseThrow();
        SessionManager.receive(viewer, new InputFramesC2S(s.id(), 0, 4, new int[] {frame(2), frame(2), frame(2), frame(2)}));
        SessionManager.leave(viewer, s.id(), "session.immersive_brewing.end.closed");
        h.assertTrue(TestSupport.session(owner).isPresent() && s.bufferedFrames() == 0 && s.violations() == 0, "the owner's session is untouched");
        h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.RESERVED, "and so is the job");
        SessionManager.leave(owner, s.id(), "session.immersive_brewing.end.closed");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aFloodSealsWithProgressKept(GameTestHelper h) {
        FakePlayer player = brewing(h, "Flooder");
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TestSupport.STATION));
        int[] step = {-1};
        h.runAfterDelay(100, () -> {
            ActiveSession s = TestSupport.session(player).orElseThrow();
            h.assertTrue(s.running(), "running before the flood");
            step[0] = s.sim().step();
            int base = s.sim().step() + s.bufferTicks();
            for (int i = 0; i < 80; i++) SessionManager.receive(player, new InputFramesC2S(s.id(), i, base + i % 4, new int[] {frame(1)}));
            h.assertTrue(s.violations() > 40, "packets beyond the rate limit are violations: " + s.violations());
        });
        h.runAfterDelay(102, () -> {
            h.assertTrue(TestSupport.session(player).isEmpty(), "the flood ended the session");
            h.assertTrue("session.immersive_brewing.end.invalid_input".equals(TestSupport.lastEndReason(player)), "for invalid input");
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED, "sealed, not cancelled or completed");
            h.assertTrue(kettle.ledger().inputs().size() == 3, "everything still owned by the job");
            int sealed = SimHandles.create(com.otectus.immersivebrewing.session.SessionKind.BREWING, kettle.ledger().params(), kettle.ledger().simState()).step();
            h.assertTrue(sealed >= step[0], "progress kept: " + sealed + " after " + step[0]);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aLateAdditionIsCarriedForward(GameTestHelper h) {
        FakePlayer player = brewing(h, "Laggard");
        int[] sequence = {0};
        int[] sentAt = {-1};
        h.onEachTick(() -> TestSupport.session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Brewing brewing) || !s.running()) return;
            KettleSimulation sim = brewing.sim();
            int current = sim.step();
            if (current < 3 || sim.finished()) return;
            boolean add = false;
            if (sim.nextAddition() == 0 && sentAt[0] < 0) {
                AdditionSpec first = sim.params().additions().get(0);
                if (current >= first.openStep() + 2) {
                    add = true;
                    sentAt[0] = current;
                }
            }
            // This client runs behind: every frame arrives after the server simulated its step with a neutral frame.
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, current - 2, new int[] {new KettleInput(1, 0, add).pack()}));
        }));
        h.succeedWhen(() -> {
            h.assertTrue(sentAt[0] >= 0, "waiting for the first addition window");
            ActiveSession s = TestSupport.session(player).orElseThrow();
            KettleSimulation sim = ((SimHandles.Brewing) s.sim()).sim();
            h.assertTrue(sim.outcome(0) == KettleSimulation.Outcome.ON_TIME, "the late add landed in its window: " + sim.outcome(0));
            h.assertTrue(sim.madeAt(0) >= sentAt[0] && sim.madeAt(0) <= sentAt[0] + 1, "at the next simulated step: " + sim.madeAt(0) + " vs " + sentAt[0]);
            h.assertTrue(s.violations() == 0, "late frames are not violations");
            SessionManager.leave(player, s.id(), "session.immersive_brewing.end.closed");
        });
    }

    private ReplayGameTests() {}
}
