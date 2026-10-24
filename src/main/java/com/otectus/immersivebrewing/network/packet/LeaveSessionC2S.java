package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** The controller closed or sealed the game screen (Escape or the Seal button): seal, or release during the count-in. */
public record LeaveSessionC2S(UUID sessionId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
    }

    public static LeaveSessionC2S decode(FriendlyByteBuf buf) {
        return new LeaveSessionC2S(buf.readUUID());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) SessionManager.leave(player, sessionId, "session.immersive_brewing.end.closed");
    }
}
