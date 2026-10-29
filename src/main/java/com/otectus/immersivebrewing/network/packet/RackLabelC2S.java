package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.menu.ApothecaryRackMenu;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Short shelf label. Position, distance, open menu, slot and text are all checked on the server. */
public record RackLabelC2S(BlockPos pos, int slot, String label) {
    private static final int MAX_WIRE = 64;
    public void encode(FriendlyByteBuf buf) { buf.writeBlockPos(pos); buf.writeByte(slot); buf.writeUtf(label, MAX_WIRE); }
    public static RackLabelC2S decode(FriendlyByteBuf buf) { return new RackLabelC2S(buf.readBlockPos(), buf.readUnsignedByte(), buf.readUtf(MAX_WIRE)); }
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) apply(player);
    }
    public boolean apply(ServerPlayer player) {
        if (slot < 0 || slot >= ApothecaryRackBlockEntity.SLOTS || !BrewingMetadata.Text.valid(label, ApothecaryRackBlockEntity.MAX_LABEL)) return false;
        if (!(player.containerMenu instanceof ApothecaryRackMenu menu) || !menu.pos().equals(pos) || !menu.stillValid(player)) return false;
        ApothecaryRackBlockEntity rack = menu.rack();
        return rack != null && rack.setLabel(slot, label);
    }
}
