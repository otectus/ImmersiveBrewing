package com.otectus.immersivebrewing.network.packet;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A station's compact material/control snapshot for everyone tracking its chunk (handoff 15): process stage, quantised
 * temperature, circulation and trouble, portion states, visible tool poses and their holders. Never the ledger, an
 * inventory or anyone's notebook. Stale revisions are ignored by the receiver.
 */
public record WorkshopSnapshotS2C(BlockPos pos, long revision, CompoundTag snapshot) {
    public static final long MAX_BYTES = 8192;
    public static volatile Consumer<WorkshopSnapshotS2C> clientHandler = message -> {};

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarLong(revision);
        buf.writeNbt(snapshot);
    }

    public static WorkshopSnapshotS2C decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        long revision = buf.readVarLong();
        CompoundTag tag = buf.readNbt(new NbtAccounter(MAX_BYTES * 8));
        return new WorkshopSnapshotS2C(pos, revision, tag == null ? new CompoundTag() : tag);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        clientHandler.accept(this);
    }
}
