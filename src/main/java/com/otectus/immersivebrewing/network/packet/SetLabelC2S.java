package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.StationMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Set or clear a label and note (spec 10): on the potion in a hand, or on the batch just finished in an open Kettle.
 * The server rejects (never silently edits) text over 48 / 120 code points or with control characters. Labels are
 * literal text and never change recipe identity, cost, quality or power.
 */
public record SetLabelC2S(boolean station, BlockPos pos, InteractionHand hand, String label, String note) {
    private static final int MAX_WIRE = 512;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(station);
        buf.writeBlockPos(pos);
        buf.writeEnum(hand);
        buf.writeUtf(label, MAX_WIRE);
        buf.writeUtf(note, MAX_WIRE);
    }

    public static SetLabelC2S decode(FriendlyByteBuf buf) {
        return new SetLabelC2S(buf.readBoolean(), buf.readBlockPos(), buf.readEnum(InteractionHand.class), buf.readUtf(MAX_WIRE), buf.readUtf(MAX_WIRE));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) apply(player);
    }

    /** Validates and applies the request for {@code player} (server thread). */
    public void apply(ServerPlayer player) {
        if (!BrewingMetadata.Text.valid(label, BrewingMetadata.MAX_LABEL) || !BrewingMetadata.Text.valid(note, BrewingMetadata.MAX_NOTE)) {
            ModNetwork.notice(player, Component.translatable("notice.immersive_brewing.label.invalid", BrewingMetadata.MAX_LABEL, BrewingMetadata.MAX_NOTE), true);
            return;
        }
        String l = label.isBlank() ? null : label.strip();
        String n = note.isBlank() ? null : note.strip();
        if (station) {
            StationBlockEntity be = StationMenu.openStation(player, pos);
            if (be instanceof BrewersKettleBlockEntity kettle) kettle.labelLastBatch(player, l, n);
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        Optional<BrewingMetadata> meta = BrewingMetadata.read(stack);
        if (meta.isEmpty()) {
            ModNetwork.notice(player, Component.translatable("notice.immersive_brewing.label.not_brewed"), true);
            return;
        }
        BrewingMetadata.write(stack, meta.get().withLabel(l, n));
        ModNetwork.notice(player, Component.translatable(l == null ? "notice.immersive_brewing.label.cleared" : "notice.immersive_brewing.label.set"), false);
    }
}
