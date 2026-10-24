package com.otectus.immersivebrewing.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The local copy of the player's notebook data (synced from the server on login and after each change). */
public final class ClientGuide {
    private static CompoundTag data = new CompoundTag();

    public record Favorite(String key, ItemStack output) {}

    public static void set(CompoundTag tag) {
        data = tag.copy();
    }

    public static void clear() {
        data = new CompoundTag();
    }

    public static int tutorialFlags() {
        return data.getInt("tutorial");
    }

    public static void markTutorial(int bit) {
        data.putInt("tutorial", data.getInt("tutorial") | 1 << bit);
    }

    public static int preferredServings() {
        int s = data.getByte("servings");
        return s <= 0 ? 3 : s;
    }

    public static List<Favorite> favorites() {
        List<Favorite> out = new ArrayList<>();
        ListTag list = data.getList("favorites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            out.add(new Favorite(t.getString("key"), ItemStack.of(t.getCompound("output"))));
        }
        return out;
    }

    public static boolean isFavorite(String key) {
        return favorites().stream().anyMatch(f -> f.key().equals(key));
    }

    public static CompoundTag lastResult() {
        return data.getCompound("last");
    }

    private ClientGuide() {}
}
