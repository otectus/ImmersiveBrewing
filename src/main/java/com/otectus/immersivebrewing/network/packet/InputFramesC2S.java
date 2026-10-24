package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Up to {@link #MAX_FRAMES} consecutive packed input frames starting at {@code firstStep}, with a per-session sequence
 * number (spec 21.1). Only inputs: never a score, an item or an outcome. An oversized packet decodes as malformed
 * without allocating its contents.
 */
public record InputFramesC2S(UUID sessionId, int sequence, int firstStep, int[] frames, boolean malformed) {
    public static final int MAX_FRAMES = 4;

    public InputFramesC2S(UUID sessionId, int sequence, int firstStep, int[] frames) {
        this(sessionId, sequence, firstStep, frames, false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeVarInt(sequence);
        buf.writeVarInt(firstStep);
        buf.writeByte(frames.length);
        for (int f : frames) buf.writeVarInt(f);
    }

    public static InputFramesC2S decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int sequence = buf.readVarInt();
        int first = buf.readVarInt();
        int count = buf.readUnsignedByte();
        if (count > MAX_FRAMES || first < 0 || sequence < 0) {
            buf.skipBytes(buf.readableBytes());
            return new InputFramesC2S(id, sequence, first, new int[0], true);
        }
        int[] frames = new int[count];
        for (int i = 0; i < count; i++) frames[i] = buf.readVarInt();
        return new InputFramesC2S(id, sequence, first, frames, false);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) SessionManager.receive(player, this);
    }
}
