package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;

import com.otectus.immersivebrewing.batch.JobState;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.*;
import com.otectus.immersivebrewing.blockentity.*;
import com.otectus.immersivebrewing.minigame.distillation.*;
import com.otectus.immersivebrewing.network.packet.*;
import com.otectus.immersivebrewing.session.*;
import com.otectus.immersivebrewing.testing.KettleBot;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.*;
import net.minecraftforge.gametest.*;
import java.util.UUID;

@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Presentation111GameTests {
    @GameTest(template = "empty")
    public static void typedSnapshotRoundtripIsBoundedAndDoesNotChangeTheSimulation(GameTestHelper h) {
        var params = DistillationParams.create(17, 1, 1);
        var sim = new DistillationSimulation(params);
        for (int i = 0; i < 100; i++) sim.step(new DistillationInput(2, 2, i > 70));
        long[] before = sim.saveState();
        var base = new SimHandles.Distillation(sim, params.encode()).visual();
        var context = new StationVisual.Context(UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE, Long.MAX_VALUE,
                Integer.MAX_VALUE, Operation.PURIFY, StationVisual.Activity.SEALED, UUID.randomUUID(), 64);
        var view = base.withContext(context);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            view.write(buffer);
            h.assertTrue(buffer.readableBytes() <= 192, "compact purification snapshot fits the observer budget");
            var read = StationVisual.read(buffer);
            h.assertTrue(!read.active() && read.activity() == StationVisual.Activity.SEALED, "sealed is frozen");
            h.assertTrue(Math.abs(read.alembic().collectedFraction() - sim.collectedFraction()) < .00002, "collection retained at wire precision");
            h.assertTrue(read.progress() > read.alembic().collectedFraction(), "run and collection stay distinct");
            h.assertTrue(java.util.Arrays.equals(before, sim.saveState()), "presentation cannot mutate simulation/score state");
            h.assertTrue(StationVisual.load(view.save()).incarnation().equals(view.incarnation()), "baseline identity survives NBT");
            for (Operation operation : Operation.values()) {
                buffer.clear();
                view.details(new StationVisual.Mortar(4, 1, 1, 1, 4, 1, -1, true, 1, Integer.MAX_VALUE),
                        new StationVisual.Kettle(2, 1, 64, 6, 0xFFFFFF, 0, 1), base.alembic(), Integer.MAX_VALUE, operation)
                        .withContext(new StationVisual.Context(context.incarnation(), context.jobId(), Long.MAX_VALUE, Long.MAX_VALUE,
                                Integer.MAX_VALUE, operation, StationVisual.Activity.WORKING, context.controller(), 64)).write(buffer);
                h.assertTrue(buffer.readableBytes() <= 192, operation + " snapshot fits observer budget");
                h.assertTrue(StationVisual.read(buffer).operation() == operation, "operation survives wire");
            }
        } finally { buffer.release(); }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void pauseCountInAndSealPublishTruthfulFrozenMaterial(GameTestHelper h) {
        BrewersKettleBlockEntity station = TestSupport.kettle(h);
        TestSupport.load(station, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        var player = TestSupport.player(h, "Visual Brewer");
        // The legacy presentation still serves batches saved by 1.1 while they finish.
        var legacy = LegacyFixtures.sealedBrew(h, station, player, TestSupport.plan(h, station, KettleGameTests.SWIFTNESS), 0);
        station = legacy;
        TestSupport.openKettle(player, legacy);
        legacy.resume(player);
        var session = TestSupport.session(player).orElseThrow();
        h.assertTrue(station.visual().activity() == StationVisual.Activity.RESUME_COUNT_IN && !station.visual().active(), "count-in does not claim work");
        TestSupport.driveLegacyKettle(h, player, com.otectus.immersivebrewing.testing.KettleBot.competent());
        long delay = session.runStartTick() - h.getLevel().getServer().getTickCount() + session.bufferTicks() + 25;
        final BrewersKettleBlockEntity watched = station;
        h.runAfterDelay(delay, () -> {
            var station0 = watched;
            SessionManager.pause(player, session.id(), true, session.sim().step(), 0);
            h.assertTrue(station0.visual().activity() == StationVisual.Activity.HELP_PAUSED && !station0.visual().active(), "Help stops observer effects immediately");
            float heat = station0.visual().heat();
            int step = session.sim().step();
            SessionManager.resumePresentation(player, session.id());
            h.assertTrue(session.paused() && session.sim().step() == step, "resume display cannot unpause gameplay");
            h.assertTrue(station0.visual().activity() == StationVisual.Activity.RESUME_COUNT_IN, "observer sees resume count-in");
            SessionManager.leave(player, session.id(), "session.immersive_brewing.end.closed");
            h.assertTrue(station0.visual().activity() == StationVisual.Activity.SEALED && !station0.visual().active(), "seal freezes world activity");
            h.assertTrue(station0.visual().heat() == heat, "sealing retains material state");
            var restored = StationPresentations.frozen(station0.ledger());
            h.assertTrue(restored.heat() == heat, "saved simulation reconstructs same material without renderer persistence");
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aSealedHandsOnBatchShowsItsFrozenState(GameTestHelper h) {
        BrewersKettleBlockEntity station = TestSupport.kettle(h);
        TestSupport.load(station, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        var player = TestSupport.player(h, "Watched Brewer");
        TestSupport.openKettle(player, station);
        station.startBrew(player, TestSupport.plan(h, station, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        TestSupport.driveKettle(h, player, com.otectus.immersivebrewing.testing.KettleTender.competent());
        h.runAfterDelay(120, () -> {
            var before = station.buildWorkshopSnapshot().getCompound("kettle");
            h.assertTrue(before.getByte("temp") > 0 && before.getByteArray("portions").length == 3, "observers see the warm, charged batch");
            TestSupport.seal(player);
            var sealed = station.buildWorkshopSnapshot();
            h.assertTrue(sealed.getByte("state") == JobState.SEALED.ordinal(), "observers see the seal");
            h.assertTrue(sealed.getCompound("kettle").getByte("burner") == 0 && sealed.getList("held", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
                    "the flame is out and every tool rests");
            h.assertTrue(station.visual().activity() == StationVisual.Activity.SEALED, "the ambient presentation stops with it");
            h.assertTrue(!sealed.contains("Ledger") && !sealed.contains("inputs"), "the snapshot never carries the ledger");
            h.succeed();
        });
    }
}
