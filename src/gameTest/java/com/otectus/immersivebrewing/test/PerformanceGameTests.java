package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.workshop.WorkshopMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The hands-on stress scene (handoff 19): thirty-two Kettles brewing at once, each tended by its own player through real
 * intent envelopes (pouring, heating, dosing, twenty paddle samples a second while stirring), plus eight observers with
 * working views open. Over 200 server ticks the workshop work per tick (station steps, leases, snapshot encoding and
 * intent validation) is timed, the snapshots sent to tracking players are counted and sized, and no entity may appear.
 * Runs in its own batch so the timing is not shared with other tests; the measurement is written to the log for
 * docs/TESTING.md and the implementation report. A budget, not a promise about any particular machine.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PerformanceGameTests {
    private static final int STATIONS = 32;
    private static final int OBSERVERS = 8;
    private static final int MEASURE_TICKS = 200;

    @GameTest(template = "empty", timeoutTicks = 700, batch = "performance")
    public static void thirtyTwoActiveKettles(GameTestHelper h) {
        var spawnRule = h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
        boolean previousSpawning = spawnRule.get();
        spawnRule.set(false, h.getLevel().getServer());
        List<FakePlayer> players = new ArrayList<>();
        List<BrewersKettleBlockEntity> kettles = new ArrayList<>();
        for (int x = 0; x < 8 && kettles.size() < STATIONS; x++) {
            for (int z = 0; z < 8 && kettles.size() < STATIONS; z += 2) {
                BlockPos rel = new BlockPos(x, 1, z);
                h.setBlock(rel, ModBlocks.BREWERS_KETTLE.get());
                BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(rel));
                TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
                FakePlayer player = TestSupport.player(h, "Load" + kettles.size());
                BlockPos at = h.absolutePos(rel);
                player.setPos(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
                TestSupport.openKettle(player, kettle);
                kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
                h.assertTrue(TestSupport.working(player), "batch " + kettles.size() + " set out");
                TestSupport.driveKettle(h, player, com.otectus.immersivebrewing.testing.KettleTender.competent());
                players.add(player);
                kettles.add(kettle);
            }
        }
        for (int i = 0; i < OBSERVERS; i++) {
            BrewersKettleBlockEntity watched = kettles.get(i * 4);
            FakePlayer observer = TestSupport.player(h, "Watcher" + i);
            BlockPos at = watched.getBlockPos();
            observer.setPos(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
            TestSupport.openView(observer, watched);
        }
        AABB area = new AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(9, 5, 9)));
        Set<UUID> observedEntityIds = new HashSet<>();
        List<String> observedEntities = new ArrayList<>();
        int[] messagesAtStart = new int[STATIONS];
        long[] snapshotsAtStart = {0};
        long[] total = {0};
        long[] worst = {0};
        int[] samples = {0};
        int[] reacting = {0};
        long[] durations = new long[MEASURE_TICKS];
        int start = 90; // every batch is charged and warm; the first doses are going in and paddles are moving
        h.runAfterDelay(start, () -> {
            List<Entity> stale = entities(h, area);
            if (!stale.isEmpty()) {
                ImmersiveBrewing.LOGGER.warn("[performance] clearing late-loaded fixture entities before measurement: {}",
                        stale.stream().map(PerformanceGameTests::describe).toList());
                stale.forEach(Entity::discard);
            }
            for (int i = 0; i < STATIONS; i++) messagesAtStart[i] = TestSupport.messages(players.get(i)).size();
            snapshotsAtStart[0] = WorkshopMetrics.totalSnapshots();
            for (FakePlayer p : players) h.assertTrue(TestSupport.working(p), "all running");
        });
        h.onEachTick(() -> {
            long tick = h.getTick();
            if (tick <= start || tick > start + MEASURE_TICKS) return;
            for (Entity entity : entities(h, area)) {
                if (observedEntityIds.add(entity.getUUID())) observedEntities.add(describe(entity));
            }
            long nanos = WorkshopMetrics.lastTickNanos();
            total[0] += nanos;
            worst[0] = Math.max(worst[0], nanos);
            if (samples[0] < durations.length) durations[samples[0]] = nanos;
            samples[0]++;
            int now = 0;
            for (BrewersKettleBlockEntity k : kettles) {
                var p = k.liveProcess();
                if (p != null && p.stage() == com.otectus.immersivebrewing.workshop.KettleProcess.Stage.REACTING) now++;
            }
            reacting[0] = Math.max(reacting[0], now);
        });
        h.runAfterDelay(start + MEASURE_TICKS + 1, () -> {
            spawnRule.set(previousSpawning, h.getLevel().getServer());
            for (FakePlayer p : players) h.assertTrue(TestSupport.working(p), "still brewing after the measurement");
            int maxMessages = 0;
            for (int i = 0; i < STATIONS; i++) maxMessages = Math.max(maxMessages, TestSupport.messages(players.get(i)).size() - messagesAtStart[i]);
            long snapshots = WorkshopMetrics.totalSnapshots() - snapshotsAtStart[0];
            int snapshotBytes = com.otectus.immersivebrewing.test.PerformanceGameTests.encodedSize(kettles.get(0).buildWorkshopSnapshot());
            long meanMicros = total[0] / Math.max(1, samples[0]) / 1000;
            long worstMicros = worst[0] / 1000;
            long[] ordered = java.util.Arrays.copyOf(durations, Math.min(samples[0], durations.length));
            java.util.Arrays.sort(ordered);
            long medianMicros = ordered[ordered.length / 2] / 1000;
            long p95Micros = ordered[Math.max(0, (int) Math.ceil(ordered.length * .95) - 1)] / 1000;
            long p99Micros = ordered[Math.max(0, (int) Math.ceil(ordered.length * .99) - 1)] / 1000;
            double perStationPerSecond = snapshots / (double) STATIONS / (MEASURE_TICKS / 20.0);
            ImmersiveBrewing.LOGGER.info("[performance] {} tended kettles + {} observers over {} ticks (peak {} reacting at once): workshop work "
                    + "median {} us, mean {} us, p95 {} us, p99 {} us, worst {} us per tick; {} snapshots ({} per station per second, about {} bytes "
                    + "each before compression); at most {} notices per player", STATIONS, OBSERVERS, samples[0], reacting[0], medianMicros, meanMicros,
                    p95Micros, p99Micros, worstMicros, snapshots, String.format(java.util.Locale.ROOT, "%.1f", perStationPerSecond), snapshotBytes, maxMessages);
            h.assertTrue(reacting[0] > 0, "the measurement covered reactions with moving paddles");
            h.assertTrue(perStationPerSecond <= 10.0, "snapshots stay well below the 20 samples a second each player sends: " + perStationPerSecond + "/s");
            h.assertTrue(snapshotBytes < 2048, "a snapshot stays compact: " + snapshotBytes + " bytes");
            h.assertTrue(maxMessages <= 10, "a competent player is not flooded with notices: " + maxMessages);
            h.assertTrue(meanMicros < 5_000, "32 tended kettles cost well under a tick on average: " + meanMicros + " us");
            List<Entity> entitiesAfter = entities(h, area);
            h.assertTrue(observedEntities.isEmpty() && entitiesAfter.isEmpty(), "no entities created during measurement; observed "
                    + observedEntities + "; present " + entitiesAfter.stream().map(PerformanceGameTests::describe).toList());
            for (FakePlayer p : players) TestSupport.seal(p);
            h.succeed();
        });
    }

    static int encodedSize(net.minecraft.nbt.CompoundTag tag) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(tag, new java.io.DataOutputStream(bytes));
            return bytes.size();
        } catch (java.io.IOException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Entity> entities(GameTestHelper h, AABB area) {
        return h.getLevel().getEntitiesOfClass(Entity.class, area, entity -> !(entity instanceof FakePlayer));
    }

    private static String describe(Entity entity) {
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String item = entity instanceof ItemEntity dropped
                ? "=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(dropped.getItem().getItem()) + "x" + dropped.getItem().getCount()
                : "";
        return type + item + "@" + entity.blockPosition().toShortString();
    }

    private PerformanceGameTests() {}
}
