package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.AlembicMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-checked request to retain the largest exact-compatible complete purification group. */
public record AlembicGroupC2S(BlockPos pos) {
    public void encode(FriendlyByteBuf buf) { buf.writeBlockPos(pos); }
    public static AlembicGroupC2S decode(FriendlyByteBuf buf) { return new AlembicGroupC2S(buf.readBlockPos()); }
    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null || !(player.containerMenu instanceof AlembicMenu menu) || !menu.pos().equals(pos) || !menu.stillValid(player)) return;
        StationBlockEntity station = menu.station();
        if (!(station instanceof AlembicBlockEntity alembic)) return;
        int moved = alembic.selectCompatibleGroup(player);
        ModNetwork.notice(player, Component.translatable(moved < 0 ? "notice.immersive_brewing.alembic.group.no_room"
                : moved == 0 ? "notice.immersive_brewing.alembic.group.none" : "notice.immersive_brewing.alembic.group.done", Math.max(0, moved)), moved <= 0);
    }
}
