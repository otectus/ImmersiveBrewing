package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.recipe.PlanKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Start a brew with a server-known plan key and the player's accessibility request (spec 21.1). The server re-resolves
 * the key against the Kettle's actual bottles and tray and caps the request.
 */
public record StartBrewC2S(BlockPos pos, PlanKey key, float assistTime, float assistBand) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        key.write(buf);
        buf.writeFloat(assistTime);
        buf.writeFloat(assistBand);
    }

    public static StartBrewC2S decode(FriendlyByteBuf buf) {
        return new StartBrewC2S(buf.readBlockPos(), PlanKey.read(buf), buf.readFloat(), buf.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) return;
        StationBlockEntity station = StationMenu.openStation(player, pos);
        if (station instanceof BrewersKettleBlockEntity kettle) kettle.startBrew(player, key, assistTime, assistBand);
    }
}
