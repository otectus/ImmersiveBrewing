package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** A session ended without a result: sealed (resumable), released during the count-in, or held for recovery. */
public record SessionEndS2C(UUID sessionId, String reason, boolean sealed) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUtf(reason, 128);
        buf.writeBoolean(sealed);
    }

    public static SessionEndS2C decode(FriendlyByteBuf buf) {
        return new SessionEndS2C(buf.readUUID(), buf.readUtf(128), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.sessionEnd(this));
    }
}
