package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.interaction.WorkshopIntents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The compact action envelope of the hands-on workshop (handoff 15): station dimension and position, the batch the
 * client believes it is acting on, a per-player sequence, the part, the action and bounded action data. The client sends
 * intent only; every field is re-validated on the server, and a malformed envelope is dropped as a violation.
 */
public record WorkshopIntentC2S(ResourceLocation dimension, BlockPos pos, UUID job, int sequence, int part, int action, int argument,
                                float x, float z, int flags) {
    public static final int CONTACT = 1;
    public static final int OFFHAND = 2;
    public static final int BATCH = 4;
    /** Effort occupies bits 4-5. */
    public static final int EFFORT_SHIFT = 4;
    /** Stands in for an unparseable dimension id: never matches a real level, so the server refuses the envelope. */
    public static final ResourceLocation INVALID = new ResourceLocation("immersive_brewing", "invalid_dimension");

    public static int flags(boolean contact, boolean offhand, boolean batch, int effort) {
        return (contact ? CONTACT : 0) | (offhand ? OFFHAND : 0) | (batch ? BATCH : 0) | (Math.max(0, Math.min(2, effort)) << EFFORT_SHIFT);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeUUID(job);
        buf.writeVarInt(sequence);
        buf.writeByte(part);
        buf.writeByte(action);
        buf.writeVarInt(argument);
        buf.writeFloat(x);
        buf.writeFloat(z);
        buf.writeByte(flags);
    }

    public static WorkshopIntentC2S decode(FriendlyByteBuf buf) {
        ResourceLocation parsed = ResourceLocation.tryParse(buf.readUtf(256));
        ResourceLocation dimension = parsed == null ? INVALID : parsed;
        return new WorkshopIntentC2S(dimension, buf.readBlockPos(), buf.readUUID(), buf.readVarInt(), buf.readUnsignedByte(), buf.readUnsignedByte(),
                buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readUnsignedByte());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var player = ctx.get().getSender();
        if (player != null) WorkshopIntents.receive(player, this);
    }
}
