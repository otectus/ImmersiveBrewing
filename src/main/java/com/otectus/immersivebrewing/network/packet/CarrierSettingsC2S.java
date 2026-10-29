package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.menu.BatchCarrierMenu;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Carrier-screen preferences. Only the currently open, still-held carrier can be changed. */
public record CarrierSettingsC2S(Action action, int value, String text) {
    public enum Action { SELECT, SAVE_LOADOUT, SET_COLOR, SET_EMBLEM, SET_ADVANCE }
    private static final int MAX_WIRE = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(value);
        buf.writeUtf(text, MAX_WIRE);
    }

    public static CarrierSettingsC2S decode(FriendlyByteBuf buf) {
        return new CarrierSettingsC2S(buf.readEnum(Action.class), buf.readVarInt(), buf.readUtf(MAX_WIRE));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) apply(player);
    }

    public boolean apply(ServerPlayer player) {
        if (!(player.containerMenu instanceof BatchCarrierMenu menu) || !menu.stillValid(player)) return false;
        ItemStack carrier = menu.carrier();
        if (!CarrierContents.metadataWritable(carrier)) return false;
        CarrierContents.Meta m = CarrierContents.meta(carrier);
        return switch (action) {
            case SELECT -> value >= 0 && value < CarrierContents.SLOTS && CarrierContents.setSelected(carrier, value);
            case SAVE_LOADOUT -> BrewingMetadata.Text.valid(text, CarrierContents.MAX_LOADOUT_NAME)
                    && CarrierContents.saveLoadout(carrier, text.isBlank() ? null : text.strip());
            case SET_COLOR -> CarrierContents.writeMeta(carrier, new CarrierContents.Meta(m.selected(), m.loadoutName(),
                    value, m.emblem(), m.advanceOnEmpty(), m.templates()));
            case SET_EMBLEM -> value >= 0 && value < CarrierContents.EMBLEM_COUNT && CarrierContents.writeMeta(carrier,
                    new CarrierContents.Meta(m.selected(), m.loadoutName(), m.labelColor(), value, m.advanceOnEmpty(), m.templates()));
            case SET_ADVANCE -> CarrierContents.writeMeta(carrier, new CarrierContents.Meta(m.selected(), m.loadoutName(),
                    m.labelColor(), m.emblem(), value != 0, m.templates()));
        };
    }
}
