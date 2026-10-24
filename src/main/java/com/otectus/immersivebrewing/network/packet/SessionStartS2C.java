package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.minigame.SimData;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * A session's final, negotiated parameters (including the seeded schedule and any approved assistance), its current
 * state, the count-in and the input buffer. {@code display} carries presentation only (plan name, item ids, colours).
 */
public record SessionStartS2C(UUID sessionId, SessionKind kind, BlockPos pos, long[] params, long[] state, int countInTicks,
                              int bufferTicks, boolean resumed, CompoundTag display) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeEnum(kind);
        buf.writeBlockPos(pos);
        buf.writeLongArray(params);
        buf.writeLongArray(state);
        buf.writeVarInt(countInTicks);
        buf.writeVarInt(bufferTicks);
        buf.writeBoolean(resumed);
        buf.writeNbt(display);
    }

    public static SessionStartS2C decode(FriendlyByteBuf buf) {
        return new SessionStartS2C(buf.readUUID(), buf.readEnum(SessionKind.class), buf.readBlockPos(), buf.readLongArray(null, SimData.MAX_LENGTH),
                buf.readLongArray(null, SimData.MAX_LENGTH), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), orEmpty(buf.readNbt()));
    }

    static CompoundTag orEmpty(CompoundTag tag) {
        return tag == null ? new CompoundTag() : tag;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.sessionStart(this));
    }
}
