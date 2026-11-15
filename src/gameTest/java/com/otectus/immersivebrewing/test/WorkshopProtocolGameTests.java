package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.interaction.WorkshopIntents;
import com.otectus.immersivebrewing.interaction.WorkshopPresence;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.testing.WorkshopHand;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Authority of the hands-on workshop (handoff 15, 21): the client sends intent only, and the server refuses or ignores
 * every envelope it should. Stale job ids, duplicated or replayed sequences, a repeated transfer, non-finite or
 * out-of-range numbers, a wrong dimension, a station without an open working view, a remote position, an obstructed
 * part and a flood all change nothing they should not; a stream of samples can not earn more than steady motion.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkshopProtocolGameTests {
    private static BrewersKettleBlockEntity setOut(GameTestHelper h, FakePlayer player) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        h.assertTrue(TestSupport.working(player), "the batch was set out");
        return kettle;
    }

    @GameTest(template = "empty")
    public static void envelopesThatMustNotActChangeNothing(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Forger");
        BrewersKettleBlockEntity kettle = setOut(h, player);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        BlockPos pos = kettle.getBlockPos();
        int seq = hand.nextSequence();

        // A duplicated or replayed sequence: the first pour applies, its replay does nothing.
        WorkshopIntentC2S pour = hand.envelope(seq, WorkshopParts.K_RACK, WorkshopAction.POUR, 0, 0, 0, false, Effort.NORMAL, false);
        h.assertTrue(WorkshopIntents.receive(player, pour).accepted(), "the pour applies once");
        hand.skipTo(seq);
        h.assertTrue(kettle.liveProcess().count(KettleProcess.Portion.CHARGED) == 1, "one serving in the vessel");
        h.assertTrue(WorkshopIntents.receive(player, pour).kind() == WorkshopResult.Kind.IGNORED, "a replayed envelope is ignored");
        h.assertTrue(kettle.liveProcess().count(KettleProcess.Portion.CHARGED) == 1, "and pours nothing twice");

        // A newer envelope repeating an already committed transfer fails harmlessly.
        h.assertTrue(!hand.act(WorkshopParts.K_RACK, WorkshopAction.POUR, 0).accepted(), "the same serving can not be poured twice");

        // A stale job id: aimed at a batch that is gone.
        int stale = hand.nextSequence();
        hand.skipTo(stale);
        WorkshopIntentC2S old = new WorkshopIntentC2S(player.level().dimension().location(), pos, UUID.randomUUID(), stale, WorkshopParts.K_LID,
                WorkshopAction.SEAL.ordinal(), 0, 0, 0, 0);
        h.assertTrue(WorkshopIntents.receive(player, old).kind() == WorkshopResult.Kind.IGNORED && kettle.ledger().state() == JobState.RUNNING,
                "an intent for another job is ignored");

        // Non-finite and out-of-range numbers are malformed and dropped.
        int before = WorkshopIntents.violations(player.getUUID());
        int n = hand.nextSequence();
        hand.skipTo(n + 3);
        WorkshopIntents.receive(player, hand.envelope(n, WorkshopParts.K_PADDLE, WorkshopAction.SAMPLE, 0, Double.NaN, 0, true, Effort.NORMAL, false));
        WorkshopIntents.receive(player, hand.envelope(n + 1, WorkshopParts.K_PADDLE, WorkshopAction.SAMPLE, 0, 0, 1e9, true, Effort.NORMAL, false));
        WorkshopIntents.receive(player, hand.envelope(n + 2, WorkshopParts.K_BURNER, WorkshopAction.SET_CONTROL, 1_000_000, 0, 0, false, Effort.NORMAL, false));
        WorkshopIntents.receive(player, new WorkshopIntentC2S(player.level().dimension().location(), pos, UUID.randomUUID(), n + 3, 200, 250, 0, 0, 0, 0));
        h.assertTrue(WorkshopIntents.violations(player.getUUID()) == before + 4, "four malformed envelopes, four violations");
        h.assertTrue(kettle.liveProcess().burner() == 0, "no burner setting came through");

        // A wrong dimension is refused before anything else.
        int d = hand.nextSequence();
        hand.skipTo(d);
        WorkshopIntents.receive(player, new WorkshopIntentC2S(new ResourceLocation("minecraft", "the_nether"), pos, UUID.randomUUID(), d,
                WorkshopParts.K_BURNER, WorkshopAction.SET_CONTROL.ordinal(), 5, 0, 0, 0));
        h.assertTrue(kettle.liveProcess().burner() == 0, "an envelope for another dimension does nothing");

        // The wire format round-trips, and an unparseable dimension decodes into one that matches nothing.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        pour.encode(buf);
        h.assertTrue(WorkshopIntentC2S.decode(buf).equals(pour) && buf.readableBytes() == 0, "the envelope round-trips");
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        // Protocol 6 layout: dimension, position, job, view epoch, sequence, part, action, argument, x, z, flags, expected revision.
        bad.writeUtf("Not A Dimension!", 256);
        bad.writeBlockPos(pos);
        bad.writeUUID(UUID.randomUUID());
        bad.writeVarInt(0);
        bad.writeVarInt(1);
        bad.writeByte(0);
        bad.writeByte(0);
        bad.writeVarInt(0);
        bad.writeFloat(0);
        bad.writeFloat(0);
        bad.writeByte(0);
        bad.writeVarLong(-1);
        h.assertTrue(WorkshopIntentC2S.decode(bad).dimension().equals(WorkshopIntentC2S.INVALID), "a malformed dimension id is not a real one");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void noViewRemotePositionAndObstructionAreRefused(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Brewer");
        BrewersKettleBlockEntity kettle = setOut(h, player);
        FakePlayer stranger = TestSupport.player(h, "Stranger");
        WorkshopHand strangerHand = TestSupport.hand(stranger, kettle);
        h.assertTrue(!strangerHand.act(WorkshopParts.K_LID, WorkshopAction.SEAL, 0).accepted() && kettle.ledger().state() == JobState.RUNNING,
                "a player whose working view is not open at the station can not act on it");
        WorkshopHand far = new WorkshopHand(player, kettle.getBlockPos().offset(9, 0, 0));
        h.assertTrue(far.act(WorkshopParts.K_LID, WorkshopAction.SEAL, 0).kind() != WorkshopResult.Kind.ACCEPTED
                && kettle.ledger().state() == JobState.RUNNING, "a guessed position does nothing");
        // A wall between the eyes and the apparatus refuses every direct action, whatever the client pointed at.
        player.setPos(player.getX(), player.getY(), player.getZ() + 1.5);
        for (int y = 0; y < 3; y++) for (int x = -2; x <= 2; x++) h.setBlock(new BlockPos(TestSupport.STATION.getX() + x, 1 + y, TestSupport.STATION.getZ() + 2), Blocks.STONE);
        WorkshopHand hand = TestSupport.hand(player, kettle);
        WorkshopResult blocked = hand.act(WorkshopParts.K_BURNER, WorkshopAction.SET_CONTROL, 6);
        h.assertTrue(!blocked.accepted() && kettle.liveProcess().burner() == 0, "an obstructed part refuses the action");
        // Walking out of reach ends the view.
        player.setPos(player.getX() + 20, player.getY(), player.getZ());
        h.runAfterDelay(10, () -> {
            h.assertTrue(WorkshopPresence.of(player.getUUID()) == null, "out of reach, the working view closed");
            h.assertTrue(!hand.act(WorkshopParts.K_LID, WorkshopAction.SEAL, 0).accepted(), "and nothing can be done from there");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSampleFloodCanNotBuyWork(GameTestHelper h) {
        FakePlayer steady = TestSupport.player(h, "Steady");
        BrewersKettleBlockEntity kettle = setOut(h, steady);
        WorkshopHand hand = TestSupport.hand(steady, kettle);
        hand.batch(WorkshopParts.K_RACK, WorkshopAction.POUR, -1);
        h.runAfterDelay(30, () -> {
            h.assertTrue(hand.act(WorkshopParts.K_PADDLE, WorkshopAction.GRAB, 0).accepted(), "the paddle is in hand");
            KettleProcess p = kettle.liveProcess();
            double circulationBefore = p.circulation();
            // Two hundred believable samples in one tick: the coordinated budget drops most, the step cap bounds the rest.
            for (int i = 0; i < 200; i++) hand.sample(WorkshopParts.K_PADDLE, (i % 2 == 0 ? 0.6 : -0.6), 0, true, Effort.FIRM);
            h.assertTrue(WorkshopIntents.violations(steady.getUUID()) > 0, "a flood beyond the budget is counted");
            h.runAfterDelay(1, () -> {
                double gained = kettle.liveProcess().circulation() - circulationBefore;
                double cap = Math.min(1, ToolTrack.MAX_STEP_PX / KettleProcess.DT / KettleProcess.REF_SPEED_PX) * Math.min(1, 4 * KettleProcess.DT) + 1e-6;
                h.assertTrue(gained <= cap, "one tick of a flood earns at most one step of capped motion: " + gained + " > " + cap);
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void aRefusedActionDoesNotEatTheHeldStack(GameTestHelper h) {
        FakePlayer player = TestSupport.player(h, "Careful");
        BrewersKettleBlockEntity kettle = setOut(h, player);
        // The tray is reserved while the batch owns it: using it with sugar in hand explains why and keeps the sugar.
        ItemStack left = TestSupport.use(player, kettle, new ItemStack(Items.SUGAR, 5));
        h.assertTrue(left.getCount() == 5 && left.is(Items.SUGAR), "the held stack is untouched: " + left);
        // With nothing applicable in hand, use opens the working view (no second action in the same click).
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        ItemStack stick = TestSupport.use(player, kettle, new ItemStack(Items.STICK));
        h.assertTrue(stick.is(Items.STICK) && stick.getCount() == 1 && WorkshopPresence.at(player, kettle.getBlockPos()), "an unrelated item opens the view");
        h.succeed();
    }
}
