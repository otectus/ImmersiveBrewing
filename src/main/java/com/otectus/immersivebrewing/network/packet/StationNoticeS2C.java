package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A station message (status, instruction or refusal). The client keeps it readable above the hotbar instead of the
 * action bar (family audit: never action-bar only); {@code failure} marks problems the player must act on.
 */
public record StationNoticeS2C(Component message, boolean failure) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeComponent(message);
        buf.writeBoolean(failure);
    }

    public static StationNoticeS2C decode(FriendlyByteBuf buf) {
        return new StationNoticeS2C(buf.readComponent(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.notice(this));
    }
}
