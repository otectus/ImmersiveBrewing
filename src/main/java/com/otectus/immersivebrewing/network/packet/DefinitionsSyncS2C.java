package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * One chunk of the active brewing dataset (spec 16.6, 21.3: definitions sync in bounded chunks). The client reassembles
 * chunks of one generation and activates the copy when the last arrives.
 */
public record DefinitionsSyncS2C(int generation, int index, int count, byte[] data) {
    public static final int CHUNK = 256 * 1024;
    public static final int MAX_CHUNKS = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(generation);
        buf.writeVarInt(index);
        buf.writeVarInt(count);
        buf.writeByteArray(data);
    }

    public static DefinitionsSyncS2C decode(FriendlyByteBuf buf) {
        return new DefinitionsSyncS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.definitions(this));
    }
}
