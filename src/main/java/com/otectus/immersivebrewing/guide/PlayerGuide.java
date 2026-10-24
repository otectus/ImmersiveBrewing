package com.otectus.immersivebrewing.guide;

import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.GuideSyncS2C;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A player's notebook data (spec 10, 25.1), stored in the persisted player compound so it survives death and relog:
 * at most {@link #MAX_FAVORITES} favourite plans, the preferred batch size, the last result explanation, tutorial steps
 * and the one-time notebook grant. Bounded; never an item history.
 */
public final class PlayerGuide {
    public static final int MAX_FAVORITES = 16;
    public static final int MAX_KEY = 512;
    private static final String ROOT = "immersive_brewing";

    public static CompoundTag data(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.getCompound(ROOT);
    }

    private static void save(Player player, CompoundTag data) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag persisted = persistent.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(ROOT, data);
        persistent.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    public static void apply(ServerPlayer player, NotebookPrefsC2S msg) {
        CompoundTag data = data(player);
        switch (msg.action()) {
            case ADD_FAVORITE -> {
                if (PlanKey.parse(msg.key()) == null) return;
                ListTag list = data.getList("favorites", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) if (list.getCompound(i).getString("key").equals(msg.key())) return;
                if (list.size() >= MAX_FAVORITES) list.remove(0);
                CompoundTag entry = new CompoundTag();
                entry.putString("key", msg.key());
                entry.put("output", displayOnly(msg.display()).save(new CompoundTag()));
                list.add(entry);
                data.put("favorites", list);
            }
            case REMOVE_FAVORITE -> {
                ListTag list = data.getList("favorites", Tag.TAG_COMPOUND);
                list.removeIf(t -> t instanceof CompoundTag c && c.getString("key").equals(msg.key()));
                data.put("favorites", list);
            }
            case SET_SERVINGS -> data.putByte("servings", (byte) Math.max(1, Math.min(6, msg.value())));
            case TUTORIAL_STEP -> data.putInt("tutorial", data.getInt("tutorial") | (1 << Math.max(0, Math.min(15, msg.value()))));
        }
        save(player, data);
        sync(player);
    }

    /**
     * The client's display stack reduced to its item and potion id: favourites are player data with bounded storage, so
     * no client-supplied NBT is kept (spec 10).
     */
    static ItemStack displayOnly(ItemStack display) {
        ItemStack shown = new ItemStack(display.getItem());
        net.minecraft.world.item.alchemy.Potion potion = net.minecraft.world.item.alchemy.PotionUtils.getPotion(display);
        if (potion != net.minecraft.world.item.alchemy.Potions.EMPTY) net.minecraft.world.item.alchemy.PotionUtils.setPotion(shown, potion);
        return shown;
    }

    /** Records the last committed result for the notebook's explanation page (overwrites; bounded). */
    public static void recordResult(ServerPlayer player, CompoundTag result) {
        CompoundTag data = data(player);
        CompoundTag copy = result.copy();
        copy.remove("outputs");
        data.put("last", copy);
        save(player, data);
        sync(player);
    }

    /**
     * Gives the Notebook once, the first time the player holds a Mortar or Kettle (spec 25.1). The flag survives login
     * and death; the crafting recipe stays available.
     */
    public static void grantNotebookOnce(ServerPlayer player) {
        CompoundTag data = data(player);
        if (data.getBoolean("granted")) return;
        data.putBoolean("granted", true);
        save(player, data);
        ItemStack book = new ItemStack(ModItems.BREWERS_NOTEBOOK.get());
        if (!player.getInventory().add(book)) player.drop(book, false);
        ModNetwork.notice(player, net.minecraft.network.chat.Component.translatable("notice.immersive_brewing.notebook_granted"), false);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        ModNetwork.sendTo(player, new GuideSyncS2C(data(player).copy()));
    }

    private PlayerGuide() {}
}
