package com.otectus.immersivebrewing.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The outcome of one acknowledged intent (handoff 7.3, 12.2), named by the envelope's sequence: a predicted transfer on
 * the sender's client flows on acceptance and returns to its source on anything else. Only the sender receives it;
 * observers reconstruct the same state from snapshots and effect events. A late or repeated acknowledgement for a
 * transfer that already settled changes nothing on the client.
 */
public record WorkshopAckS2C(BlockPos pos, int sequence, int outcome, @Nullable Component reason) {
    public static final int ACCEPTED = 0, REFUSED = 1, IGNORED = 2;
    public static volatile Consumer<WorkshopAckS2C> clientHandler = message -> {};

    public boolean accepted() {
        return outcome == ACCEPTED;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(sequence);
        buf.writeByte(outcome);
        buf.writeBoolean(reason != null);
        if (reason != null) buf.writeComponent(reason);
    }

    public static WorkshopAckS2C decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int sequence = buf.readVarInt();
        int outcome = buf.readUnsignedByte();
        Component reason = buf.readBoolean() ? buf.readComponent() : null;
        return new WorkshopAckS2C(pos, sequence, Math.min(IGNORED, outcome), reason);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        clientHandler.accept(this);
    }
}
