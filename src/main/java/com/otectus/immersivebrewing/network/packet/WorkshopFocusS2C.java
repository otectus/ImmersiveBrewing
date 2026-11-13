package com.otectus.immersivebrewing.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The server accepted (or ended) a player's working view at a station: the client opens or closes its focus surface.
 * Sent only after the ordinary block use passed every interaction and protection check on the server.
 */
public record WorkshopFocusS2C(BlockPos pos, int kind, boolean open, String reason) {
    /** Installed by client setup; common code never loads client classes. */
    public static volatile Consumer<WorkshopFocusS2C> clientHandler = message -> {};

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByte(kind);
        buf.writeBoolean(open);
        buf.writeUtf(reason, 256);
    }

    public static WorkshopFocusS2C decode(FriendlyByteBuf buf) {
        return new WorkshopFocusS2C(buf.readBlockPos(), buf.readUnsignedByte(), buf.readBoolean(), buf.readUtf(256));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        clientHandler.accept(this);
    }
}
