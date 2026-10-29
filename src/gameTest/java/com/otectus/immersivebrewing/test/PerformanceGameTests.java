package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.KettleBot;
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
 * Spec 26.6 performance gate, measured: thirty-two Kettles brewing at once, each with its own controller sending real
 * input packets. Over 200 server ticks the session work per tick is timed, the messages each controller receives are
 * counted (snapshots every ten steps, nothing per tick) and no entity appears. Runs in its own batch so the timing is
 * not shared with other tests; the measurement is written to the log for docs/TESTING.md.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PerformanceGameTests {
    private static final int STATIONS = 32;
    private static final int MEASURE_TICKS = 200;

    @GameTest(template = "empty", timeoutTicks = 600, batch = "performance")
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
                h.assertTrue(TestSupport.session(player).isPresent(), "session " + kettles.size() + " started");
                TestSupport.driveKettle(h, player, KettleBot.competent());
                players.add(player);
                kettles.add(kettle);
            }
        }
        AABB area = new AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(9, 5, 9)));
        Set<UUID> observedEntityIds = new HashSet<>();
        List<String> observedEntities = new ArrayList<>();
        int[] messagesAtStart = new int[STATIONS];
        long[] total = {0};
        long[] worst = {0};
        int[] samples = {0};
        long[] durations = new long[MEASURE_TICKS];
        int start = 70; // after the 60-tick count-in: every session is running
        h.runAfterDelay(start, () -> {
            List<Entity> stale = entities(h, area);
            if (!stale.isEmpty()) {
                ImmersiveBrewing.LOGGER.warn("[performance] clearing late-loaded fixture entities before measurement: {}",
                        stale.stream().map(PerformanceGameTests::describe).toList());
                stale.forEach(Entity::discard);
            }
            for (int i = 0; i < STATIONS; i++) messagesAtStart[i] = TestSupport.messages(players.get(i)).size();
            for (FakePlayer p : players) h.assertTrue(TestSupport.session(p).map(ActiveSession::running).orElse(false), "all running");
        });
        h.onEachTick(() -> {
            long tick = h.getTick();
            if (tick <= start || tick > start + MEASURE_TICKS) return;
            for (Entity entity : entities(h, area)) {
                if (observedEntityIds.add(entity.getUUID())) observedEntities.add(describe(entity));
            }
            long nanos = SessionManager.lastTickNanos();
            total[0] += nanos;
            worst[0] = Math.max(worst[0], nanos);
            if (samples[0] < durations.length) durations[samples[0]] = nanos;
            samples[0]++;
        });
        h.runAfterDelay(start + MEASURE_TICKS + 1, () -> {
            spawnRule.set(previousSpawning, h.getLevel().getServer());
            for (FakePlayer p : players) h.assertTrue(TestSupport.session(p).isPresent(), "still brewing after the measurement");
            int maxMessages = 0;
            for (int i = 0; i < STATIONS; i++) maxMessages = Math.max(maxMessages, TestSupport.messages(players.get(i)).size() - messagesAtStart[i]);
            long meanMicros = total[0] / Math.max(1, samples[0]) / 1000;
            long worstMicros = worst[0] / 1000;
            long[] ordered = java.util.Arrays.copyOf(durations, Math.min(samples[0], durations.length));
            java.util.Arrays.sort(ordered);
            long p95Micros = ordered[Math.max(0, (int) Math.ceil(ordered.length * .95) - 1)] / 1000;
            long p99Micros = ordered[Math.max(0, (int) Math.ceil(ordered.length * .99) - 1)] / 1000;
            ImmersiveBrewing.LOGGER.info("[performance] {} active sessions over {} ticks: session work mean {} us, p95 {} us, p99 {} us, worst {} us per tick; "
                    + "at most {} messages per controller", STATIONS, samples[0], meanMicros, p95Micros, p99Micros, worstMicros, maxMessages);
            h.assertTrue(maxMessages <= MEASURE_TICKS / 10 + 5, "messages per controller stay at one snapshot per ten steps: " + maxMessages);
            h.assertTrue(meanMicros < 5_000, "32 sessions cost well under a tick on average: " + meanMicros + " us");
            List<Entity> entitiesAfter = entities(h, area);
            h.assertTrue(observedEntities.isEmpty() && entitiesAfter.isEmpty(), "no entities created during measurement; observed "
                    + observedEntities + "; present " + entitiesAfter.stream().map(PerformanceGameTests::describe).toList());
            for (FakePlayer p : players) TestSupport.session(p).ifPresent(s -> SessionManager.leave(p, s.id(), "session.immersive_brewing.end.closed"));
            h.succeed();
        });
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
