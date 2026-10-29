package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Opens a practice session (spec 23.2): it runs entirely on the client with the same simulation and synced parameters,
 * and has no server session, so no practice completion can ever become a productive one.
 */
public record OpenPracticeS2C(SessionKind kind, @Nullable ResourceLocation profile,
                              @Nullable long[] params, @Nullable net.minecraft.nbt.CompoundTag display) {
    public OpenPracticeS2C(SessionKind kind, @Nullable ResourceLocation profile) { this(kind, profile, null, null); }
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeBoolean(profile != null);
        if (profile != null) buf.writeResourceLocation(profile);
        buf.writeBoolean(params != null);
        if (params != null) buf.writeLongArray(params);
        buf.writeNbt(display);
    }

    public static OpenPracticeS2C decode(FriendlyByteBuf buf) {
        return new OpenPracticeS2C(buf.readEnum(SessionKind.class), buf.readBoolean() ? buf.readResourceLocation() : null,
                buf.readBoolean() ? buf.readLongArray(null, com.otectus.immersivebrewing.minigame.SimData.MAX_LENGTH) : null, buf.readNbt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.practice(this));
    }
}
