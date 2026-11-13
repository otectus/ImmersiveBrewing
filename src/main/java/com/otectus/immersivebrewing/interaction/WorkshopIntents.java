package com.otectus.immersivebrewing.interaction;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server validation and dispatch of workshop intents (handoff 15). In order: a living, non-spectating sender in the
 * envelope's dimension; a station in an already loaded chunk (a packet never loads or generates one); one coordinated
 * action budget per player; a strictly increasing sequence (duplicates and replays are dropped); a known action and a
 * part id from the server's own table; finite, bounded numbers; an open working view at that station; reach; and a
 * clear line of sight from the eyes to the part. Only then does the station validate the action against its state,
 * leases and owned resources.
 *
 * <p>Violations are counted; a sustained stream of malformed or forged input ends the player's working view.
 */
public final class WorkshopIntents {
    /** One coordinated budget: 40 actions a second on average, with a short burst. */
    public static final double BUDGET = 40;
    public static final double REFILL_PER_TICK = 2.0;
    /** Tool samples have their own lower cap within the same budget (handoff: 10-20 updates a second). */
    public static final double SAMPLE_BUDGET = 25;
    public static final double SAMPLE_REFILL_PER_TICK = 1.25;
    public static final int MAX_VIOLATIONS = 60;
    public static final double MAX_COORDINATE = 1.5;

    private static final class Budget {
        double tokens = BUDGET;
        double samples = SAMPLE_BUDGET;
        long lastTick;
        int lastSequence = Integer.MIN_VALUE;
        int violations;
        long lastNotice = -100;
    }

    private static final Map<UUID, Budget> BUDGETS = new HashMap<>();

    public static WorkshopResult receive(ServerPlayer player, WorkshopIntentC2S msg) {
        long started = System.nanoTime();
        try {
            return receiveTimed(player, msg);
        } finally {
            com.otectus.immersivebrewing.workshop.WorkshopMetrics.intent(System.nanoTime() - started);
        }
    }

    private static WorkshopResult receiveTimed(ServerPlayer player, WorkshopIntentC2S msg) {
        if (!player.isAlive() || player.isSpectator()) return WorkshopResult.IGNORED;
        Budget budget = BUDGETS.computeIfAbsent(player.getUUID(), id -> new Budget());
        long now = player.server.getTickCount();
        long elapsed = Math.max(0, now - budget.lastTick);
        budget.lastTick = now;
        budget.tokens = Math.min(BUDGET, budget.tokens + elapsed * REFILL_PER_TICK);
        budget.samples = Math.min(SAMPLE_BUDGET, budget.samples + elapsed * SAMPLE_REFILL_PER_TICK);
        if (!player.level().dimension().location().equals(msg.dimension())) {
            violation(player, budget, "dimension");
            return WorkshopResult.IGNORED;
        }
        if (msg.sequence() <= budget.lastSequence) return WorkshopResult.IGNORED; // a duplicate or replayed envelope: no effect, no debit
        budget.lastSequence = msg.sequence();
        WorkshopAction action = msg.action() < WorkshopAction.values().length ? WorkshopAction.values()[msg.action()] : null;
        if (action == null || msg.part() > 63 || !Float.isFinite(msg.x()) || !Float.isFinite(msg.z())
                || Math.abs(msg.x()) > MAX_COORDINATE || Math.abs(msg.z()) > MAX_COORDINATE || msg.argument() < -1 || msg.argument() > 4096) {
            violation(player, budget, "malformed");
            return WorkshopResult.IGNORED;
        }
        if (budget.tokens < 1) {
            violation(player, budget, "rate");
            return WorkshopResult.IGNORED;
        }
        budget.tokens -= 1;
        if (action == WorkshopAction.SAMPLE) {
            if (budget.samples < 1) return WorkshopResult.IGNORED; // over the sampling rate: dropped, the next one carries the pose
            budget.samples -= 1;
        }
        int effort = (msg.flags() >> WorkshopIntentC2S.EFFORT_SHIFT) & 3;
        WorkshopIntent intent = new WorkshopIntent(msg.part(), action, msg.argument(), msg.x(), msg.z(),
                (msg.flags() & WorkshopIntentC2S.CONTACT) != 0, Effort.byId(effort),
                (msg.flags() & WorkshopIntentC2S.OFFHAND) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                (msg.flags() & WorkshopIntentC2S.BATCH) != 0, msg.job(), msg.sequence());
        WorkshopResult result = act(player, msg.pos(), intent);
        if (result.kind() == WorkshopResult.Kind.REFUSED && result.reason() != null && now - budget.lastNotice >= 5) {
            budget.lastNotice = now;
            ModNetwork.notice(player, result.reason(), true);
        }
        return result;
    }

    /** Violations counted for a player (operator report and protocol tests). */
    public static int violations(java.util.UUID player) {
        Budget b = BUDGETS.get(player);
        return b == null ? 0 : b.violations;
    }

    /**
     * Validates and applies one decoded intent (also the entry point for server code and tests, after envelope and
     * budget checks).
     */
    public static WorkshopResult act(ServerPlayer player, BlockPos pos, WorkshopIntent intent) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) return WorkshopResult.IGNORED;
        BlockEntity be = level.getBlockEntity(pos);
        WorkshopKind kind;
        Direction facing;
        if (be instanceof StationBlockEntity station) {
            kind = station.workshopKind();
            facing = station.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        } else if (be instanceof ApothecaryRackBlockEntity rack) {
            kind = WorkshopKind.RACK;
            facing = rack.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        } else {
            return WorkshopResult.IGNORED;
        }
        if (intent.action() == WorkshopAction.LEAVE) {
            if (be instanceof StationBlockEntity station) station.releaseLeases(player.getUUID());
            if (WorkshopPresence.at(player, pos)) WorkshopPresence.leave(player, "");
            return WorkshopResult.ACCEPTED;
        }
        if (!WorkshopPresence.at(player, pos)) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.no_view");
        if (intent.action() == WorkshopAction.FOCUS) {
            WorkshopPresence.enter(player, pos, kind);
            return WorkshopResult.ACCEPTED;
        }
        double reach = ServerConfig.get(ServerConfig.MAX_STATION_DISTANCE) + 1.0;
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > reach) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.reach");
        if (!level.mayInteract(player, pos)) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.protected");
        WorkshopPart part = WorkshopParts.get(kind, intent.part());
        boolean partless = intent.action() == WorkshopAction.DRAWER || intent.action() == WorkshopAction.BOOK
                || intent.action() == WorkshopAction.STOP || intent.action() == WorkshopAction.LEGACY_RESUME
                || intent.action() == WorkshopAction.SEAL || intent.action() == WorkshopAction.BEGIN;
        if (part == null && !partless) return WorkshopResult.IGNORED;
        // Samples and releases continue a grab that already passed this check; everything else must be in plain sight.
        boolean continuing = intent.action() == WorkshopAction.SAMPLE || intent.action() == WorkshopAction.RELEASE
                || intent.action() == WorkshopAction.STOP;
        if (part != null && !continuing && !WorkshopGeometry.visible(level, player, pos, facing, part)) {
            return WorkshopResult.refuse("workshop.immersive_brewing.refuse.obstructed");
        }
        WorkshopResult result;
        try {
            if (be instanceof StationBlockEntity station) result = station.workshopAct(player, intent);
            else result = RackWorkshop.act((ApothecaryRackBlockEntity) be, player, intent);
        } catch (RuntimeException e) {
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: workshop action {} at {} failed; nothing was changed by the failing step", intent.action(), pos, e);
            return WorkshopResult.refuse("workshop.immersive_brewing.refuse.error");
        }
        return result;
    }

    private static void violation(ServerPlayer player, Budget budget, String what) {
        budget.violations++;
        if (ServerConfig.debug()) ImmersiveBrewing.LOGGER.info("[workshop] {} intent violation: {} ({})", player.getGameProfile().getName(), what, budget.violations);
        if (budget.violations > MAX_VIOLATIONS) {
            budget.violations = 0;
            WorkshopPresence.leave(player, "workshop.immersive_brewing.focus.end.invalid_input");
        }
    }

    /** Lets a cooled-down player's count recover: one forgiven violation every few seconds. */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (server.getTickCount() % 60 != 0) return;
        BUDGETS.values().forEach(b -> b.violations = Math.max(0, b.violations - 1));
    }

    public static void forget(UUID player) {
        BUDGETS.remove(player);
    }

    public static void clear() {
        BUDGETS.clear();
    }

    private WorkshopIntents() {}
}
