package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.minigame.SimData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** The server's authoritative simulation state at {@code step}, for client reconciliation (every half second). */
public record StateSnapshotS2C(UUID sessionId, int step, long[] state) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeVarInt(step);
        buf.writeLongArray(state);
    }

    public static StateSnapshotS2C decode(FriendlyByteBuf buf) {
        return new StateSnapshotS2C(buf.readUUID(), buf.readVarInt(), buf.readLongArray(null, SimData.MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.snapshot(this));
    }
}
