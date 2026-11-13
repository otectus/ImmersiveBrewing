package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** Announces the existing local resume count-in; the simulation remains paused until the ordinary resume request. */
public record ResumePresentationC2S(UUID sessionId) {
    public void encode(FriendlyByteBuf b) { b.writeUUID(sessionId); }
    public static ResumePresentationC2S decode(FriendlyByteBuf b) { return new ResumePresentationC2S(b.readUUID()); }
    public void handle(Supplier<NetworkEvent.Context> context) {
        var player = context.get().getSender();
        if (player != null) SessionManager.resumePresentation(player, sessionId);
    }
}
