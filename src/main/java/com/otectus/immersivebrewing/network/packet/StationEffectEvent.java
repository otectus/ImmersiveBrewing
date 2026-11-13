package com.otectus.immersivebrewing.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Accepted server action, never an instruction to play a client-supplied resource. */
public record StationEffectEvent(BlockPos pos, UUID incarnation, UUID jobId, long sequence, long sampleTime,
                                 int simulationStep, Kind kind, int index, int outcome) {
    /** Accepted outcomes observers hear and see. Appended kinds (POUR onwards) are the hands-on workshop's. */
    public enum Kind { LOAD, ADDITION, FOLD, RECEIVER, COMPLETE, SEAL, RECOVERY_INSTALL, COLLECT, WATER, VALVE, POUR, FILL, PRESS, RESUME, GRAB, DROP }
    public static volatile Consumer<StationEffectEvent> clientHandler = packet -> {};
    public void encode(FriendlyByteBuf b) {
        b.writeBlockPos(pos); b.writeUUID(incarnation); b.writeUUID(jobId); b.writeVarLong(sequence); b.writeVarLong(sampleTime);
        b.writeVarInt(simulationStep); b.writeEnum(kind); b.writeVarInt(index); b.writeVarInt(outcome);
    }
    public static StationEffectEvent decode(FriendlyByteBuf b) {
        return new StationEffectEvent(b.readBlockPos(), b.readUUID(), b.readUUID(), b.readVarLong(), b.readVarLong(),
                b.readVarInt(), b.readEnum(Kind.class), b.readVarInt(), b.readVarInt());
    }
    public void handle(Supplier<NetworkEvent.Context> context) { clientHandler.accept(this); }
}
