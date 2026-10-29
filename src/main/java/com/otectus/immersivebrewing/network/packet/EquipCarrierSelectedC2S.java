package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Remappable equip intent. The server resolves the held carrier, selected slot and destination inventory atomically. */
public record EquipCarrierSelectedC2S(InteractionHand hand) {
    public void encode(FriendlyByteBuf buf) { buf.writeEnum(hand); }

    public static EquipCarrierSelectedC2S decode(FriendlyByteBuf buf) {
        return new EquipCarrierSelectedC2S(buf.readEnum(InteractionHand.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) return;
        BatchCarrierItem.EquipResult result = BatchCarrierItem.equipSelected(player, hand);
        if (result != BatchCarrierItem.EquipResult.EQUIPPED) {
            ModNetwork.notice(player, Component.translatable("notice.immersive_brewing.carrier.equip." + result.name().toLowerCase(java.util.Locale.ROOT)), true);
        }
    }
}
