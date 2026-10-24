package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A button on a station's menu. Accepted only from a player who has that station's menu open (a normal accepted
 * interaction, spec 21.1), so a guessed block position does nothing.
 */
public record StationActionC2S(BlockPos pos, Action action, int argument) {
    public enum Action {
        REQUEST_PLANS, START_PREPARATION, RESUME, TOGGLE_COMPARATOR, START_PURIFICATION, START_RECLAMATION, CANCEL_PASSIVE
    }

    /**
     * Packs a small action argument (bits 0-7, e.g. a kit count) with an accessibility request: slowing in bits 8-15 and
     * band widening in bits 16-23, each as hundredths above 1.0. The server caps both.
     */
    public static int withAssist(int base, double time, double band) {
        int t = (int) Math.round(Math.max(0, Math.min(2.55, time - 1.0)) * 100);
        int b = (int) Math.round(Math.max(0, Math.min(2.55, band - 1.0)) * 100);
        return (base & 0xFF) | t << 8 | b << 16;
    }

    public static int base(int argument) {
        return argument & 0xFF;
    }

    public static double assistTime(int argument) {
        return 1.0 + ((argument >> 8) & 0xFF) / 100.0;
    }

    public static double assistBand(int argument) {
        return 1.0 + ((argument >> 16) & 0xFF) / 100.0;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeVarInt(argument);
    }

    public static StationActionC2S decode(FriendlyByteBuf buf) {
        return new StationActionC2S(buf.readBlockPos(), buf.readEnum(Action.class), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) return;
        StationBlockEntity station = StationMenu.openStation(player, pos);
        if (station != null) station.handleAction(player, action, argument);
    }
}
