package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationVisual;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compact observer update, with no inventory or ledger payload. Handler installed only on the client. */
public record StationVisualS2C(BlockPos pos, StationVisual visual) {
    public static volatile Consumer<StationVisualS2C> clientHandler = packet -> {};
    public void encode(FriendlyByteBuf b) { b.writeBlockPos(pos); visual.write(b); }
    public static StationVisualS2C decode(FriendlyByteBuf b) { return new StationVisualS2C(b.readBlockPos(), StationVisual.read(b)); }
    public void handle(Supplier<NetworkEvent.Context> context) { clientHandler.accept(this); }
}
