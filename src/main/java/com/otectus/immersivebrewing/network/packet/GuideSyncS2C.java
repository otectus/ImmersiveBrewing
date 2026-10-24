package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** The player's own bounded notebook data: favourites, preferred batch size, last result, tutorial progress. */
public record GuideSyncS2C(CompoundTag guide) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeNbt(guide);
    }

    public static GuideSyncS2C decode(FriendlyByteBuf buf) {
        return new GuideSyncS2C(SessionStartS2C.orEmpty(buf.readNbt()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.guide(this));
    }
}
