package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A practice setup resolved by the server (handoff 10): the same hands-on process parameters a real batch of that route
 * or reagent would use, and what to show (doses, servings, the output's look). The client runs it locally at an idle
 * station; nothing it does is ever sent back, so practice can create no item, quality metadata, XP or advancement.
 */
public record OpenPracticeS2C(SessionKind kind, CompoundTag practice) {
    public static final long MAX_BYTES = 16 * 1024;

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeNbt(practice);
    }

    public static OpenPracticeS2C decode(FriendlyByteBuf buf) {
        SessionKind kind = buf.readEnum(SessionKind.class);
        CompoundTag tag = buf.readNbt(new NbtAccounter(MAX_BYTES * 8));
        return new OpenPracticeS2C(kind, tag == null ? new CompoundTag() : tag);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.practice(this));
    }
}
