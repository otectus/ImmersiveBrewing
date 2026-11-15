package com.otectus.immersivebrewing.interaction;

import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.WorkshopFocusS2C;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who has a working view open at which station (handoff 4, 15, 16). A presence is created only by an ordinary block use
 * that reached the station on the server, so claim and protection hooks that cancel block use also keep a player out of
 * the workshop. It grants no reach: intents are still checked for distance and line of sight, and a presence ends when
 * its player walks out of range, changes dimension, dies or disconnects.
 *
 * <p>Focus is not ownership: leaving a view releases that player's tool leases but never cancels or seals a batch.
 * Server thread only.
 */
public final class WorkshopPresence {
    /** @param epoch numbers this view for its player; intents stamped with an earlier view's epoch are stale */
    public record Presence(ResourceKey<Level> dimension, BlockPos pos, WorkshopKind kind, long since, int epoch) {}

    private static final Map<UUID, Presence> BY_PLAYER = new HashMap<>();
    /** The last epoch handed to each player (never reused while the server runs, so a late intent can not match). */
    private static final Map<UUID, Integer> EPOCHS = new HashMap<>();
    /** The live player object of each presence: fake players (automation, tests) are not in the player list. */
    private static final Map<UUID, ServerPlayer> PLAYERS = new HashMap<>();

    /** Opens (or moves) a player's working view to this station and tells their client. */
    public static void enter(ServerPlayer player, BlockPos pos, WorkshopKind kind) {
        Presence previous = BY_PLAYER.get(player.getUUID());
        if (previous != null && (!previous.pos().equals(pos) || previous.dimension() != player.level().dimension())) releaseAt(player, previous);
        int epoch = EPOCHS.merge(player.getUUID(), 1, (a, b) -> a >= Integer.MAX_VALUE - 1 ? 1 : a + 1);
        BY_PLAYER.put(player.getUUID(), new Presence(player.level().dimension(), pos.immutable(), kind, player.server.getTickCount(), epoch));
        PLAYERS.put(player.getUUID(), player);
        ModNetwork.sendTo(player, new WorkshopFocusS2C(pos, kind.ordinal(), true, "", epoch));
    }

    /** The epoch of the player's open view, or {@link WorkshopIntent#ANY_EPOCH} when none is open. */
    public static int epoch(UUID player) {
        Presence p = BY_PLAYER.get(player);
        return p == null ? WorkshopIntent.ANY_EPOCH : p.epoch();
    }

    /** Ends a player's working view (their leases are released; any batch carries on under the normal policy). */
    public static void leave(ServerPlayer player, String reason) {
        Presence p = BY_PLAYER.remove(player.getUUID());
        PLAYERS.remove(player.getUUID());
        if (p == null) return;
        releaseAt(player, p);
        ModNetwork.sendTo(player, new WorkshopFocusS2C(p.pos(), p.kind().ordinal(), false, reason, p.epoch()));
    }

    /** Drops a player without a message (logout: there is no connection left to tell). */
    public static void drop(ServerPlayer player) {
        PLAYERS.remove(player.getUUID());
        Presence p = BY_PLAYER.remove(player.getUUID());
        if (p != null) releaseAt(player, p);
    }

    @Nullable
    public static Presence of(UUID player) {
        return BY_PLAYER.get(player);
    }

    /** Whether this player's working view is open at exactly this station in their current dimension. */
    public static boolean at(ServerPlayer player, BlockPos pos) {
        Presence p = BY_PLAYER.get(player.getUUID());
        return p != null && p.pos().equals(pos) && p.dimension() == player.level().dimension();
    }

    /** Releases every lease the player holds at the station of a presence. */
    private static void releaseAt(ServerPlayer player, Presence p) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = server.getLevel(p.dimension());
        if (level == null || !level.isLoaded(p.pos())) return;
        if (level.getBlockEntity(p.pos()) instanceof StationBlockEntity station) station.releaseLeases(player.getUUID());
    }

    /** Releases the leases of the player's current presence without closing the view (damage, sealing). */
    public static void releaseLeases(ServerPlayer player) {
        Presence p = BY_PLAYER.get(player.getUUID());
        if (p != null) releaseAt(player, p);
    }

    /** Ends presences that are no longer valid. Called every few server ticks. */
    public static void tick(MinecraftServer server) {
        if (BY_PLAYER.isEmpty() || server.getTickCount() % 5 != 0) return;
        double reach = ServerConfig.get(ServerConfig.MAX_STATION_DISTANCE) + 1.5;
        for (Map.Entry<UUID, Presence> e : new ArrayList<>(BY_PLAYER.entrySet())) {
            ServerPlayer player = player(server, e.getKey());
            Presence p = e.getValue();
            if (player == null || player.isRemoved() && !player.isAlive() || player.hasDisconnected()) {
                BY_PLAYER.remove(e.getKey());
                PLAYERS.remove(e.getKey());
                continue;
            }
            String reason = null;
            if (!player.isAlive()) reason = "workshop.immersive_brewing.focus.end.died";
            else if (player.level().dimension() != p.dimension()) reason = "workshop.immersive_brewing.focus.end.dimension";
            else if (!player.level().isLoaded(p.pos())) reason = "workshop.immersive_brewing.focus.end.unloaded";
            else if (player.getEyePosition().distanceTo(Vec3.atCenterOf(p.pos())) > reach) reason = "workshop.immersive_brewing.focus.end.range";
            else if (!(player.level().getBlockEntity(p.pos()) instanceof StationBlockEntity)
                    && !(player.level().getBlockEntity(p.pos()) instanceof com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity)) {
                reason = "workshop.immersive_brewing.focus.end.gone";
            }
            if (reason != null) leave(player, reason);
        }
    }

    /** Ends every working view at a removed station. */
    public static void stationRemoved(ResourceKey<Level> dimension, BlockPos pos, MinecraftServer server) {
        for (Map.Entry<UUID, Presence> e : new ArrayList<>(BY_PLAYER.entrySet())) {
            if (e.getValue().dimension() != dimension || !e.getValue().pos().equals(pos)) continue;
            BY_PLAYER.remove(e.getKey());
            ServerPlayer player = PLAYERS.remove(e.getKey());
            if (player != null) ModNetwork.sendTo(player, new WorkshopFocusS2C(pos, e.getValue().kind().ordinal(), false,
                    "workshop.immersive_brewing.focus.end.gone", e.getValue().epoch()));
        }
    }

    /**
     * The live player for a UUID: the player list first, else a present fake player. Null when gone, so an absent
     * participant never keeps a batch running.
     */
    @Nullable
    public static ServerPlayer player(MinecraftServer server, UUID id) {
        ServerPlayer listed = server.getPlayerList().getPlayer(id);
        if (listed != null) return listed;
        ServerPlayer present = PLAYERS.get(id);
        return present != null && !present.hasDisconnected() && !present.isRemoved() ? present : null;
    }

    public static int count() {
        return BY_PLAYER.size();
    }

    public static void clear() {
        BY_PLAYER.clear();
        PLAYERS.clear();
        EPOCHS.clear();
    }

    private WorkshopPresence() {}
}
