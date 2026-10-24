package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** The committed result: scores, the terms behind them, the output and the improvement tip (presentation only). */
public record SessionResultS2C(UUID sessionId, SessionKind kind, CompoundTag result) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeEnum(kind);
        buf.writeNbt(result);
    }

    public static SessionResultS2C decode(FriendlyByteBuf buf) {
        return new SessionResultS2C(buf.readUUID(), buf.readEnum(SessionKind.class), SessionStartS2C.orEmpty(buf.readNbt()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.result(this));
    }
}
