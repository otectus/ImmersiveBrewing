package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The controller opened ({@code paused}) or closed the help overlay: the session's clock stands still while it is open.
 * A resume says when play continues on the client: it simulates {@code step} after {@code countIn} more ticks.
 */
public record PauseSessionC2S(UUID sessionId, boolean paused, int step, int countIn) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeBoolean(paused);
        buf.writeVarInt(step);
        buf.writeVarInt(countIn);
    }

    public static PauseSessionC2S decode(FriendlyByteBuf buf) {
        return new PauseSessionC2S(buf.readUUID(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) SessionManager.pause(player, sessionId, paused, step, countIn);
    }
}
