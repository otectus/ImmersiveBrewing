package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.guide.PlayerGuide;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Bounded Notebook preferences and journal actions. Recipe selections remain hints and are re-resolved by the server. */
public record NotebookPrefsC2S(Action action, String key, ItemStack display, int value) {
    public enum Action {
        ADD_FAVORITE, REPLACE_FAVORITE, REMOVE_FAVORITE, SET_SERVINGS, TUTORIAL_STEP,
        CLEAR_HISTORY, SET_SEARCH, SET_FILTER, SET_TAB, SET_ONBOARDING, PIN_RECIPE, DISMISS_PIN, REPEAT_SETUP, PRACTICE_PLAN
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(key, PlayerGuide.MAX_KEY);
        buf.writeItem(display);
        buf.writeVarInt(value);
    }

    public static NotebookPrefsC2S decode(FriendlyByteBuf buf) {
        return new NotebookPrefsC2S(buf.readEnum(Action.class), buf.readUtf(PlayerGuide.MAX_KEY), buf.readItem(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) PlayerGuide.apply(player, this);
    }
}
