package com.otectus.immersivebrewing.guide;

import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.GuideSyncS2C;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * A player's notebook data (spec 10, 25.1), stored in the persisted player compound so it survives death and relog:
 * at most {@link #MAX_FAVORITES} favourite plans, the preferred batch size, the last result explanation, tutorial steps
 * and the one-time notebook grant. Bounded; never an item history.
 */
public final class PlayerGuide {
    public static final int DATA_VERSION = 2;
    public static final int MAX_FAVORITES = 16;
    public static final int MAX_HISTORY = 32;
    public static final int MAX_KEY = 512;
    public static final int MAX_SEARCH = 128;
    public static final int MAX_FILTER = 64;
    private static final String ROOT = "immersive_brewing";

    public static CompoundTag data(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag guide = persisted.getCompound(ROOT);
        migrate(guide);
        return guide;
    }

    /**
     * Adds only defaults required by a known older schema. A payload written by a newer mod is deliberately left
     * untouched: downgrading must not reinterpret or discard fields it does not understand.
     */
    public static void migrate(CompoundTag guide) {
        int version = guide.contains("dataVersion", Tag.TAG_INT) ? guide.getInt("dataVersion") : 0;
        if (version > DATA_VERSION) return;
        if (version < 1 && !guide.contains("servings", Tag.TAG_BYTE)) guide.putByte("servings", (byte) 3);
        if (version < 2) {
            if (!guide.contains("history", Tag.TAG_LIST)) guide.put("history", new ListTag());
            if (!guide.contains("filter", Tag.TAG_STRING)) guide.putString("filter", "all");
        }
        guide.putInt("dataVersion", DATA_VERSION);
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
                if (list.size() >= MAX_FAVORITES) {
                    data.putString("favoriteNotice", "notebook.immersive_brewing.favorite.capacity");
                    CompoundTag pending = new CompoundTag();
                    pending.putString("key", msg.key());
                    pending.put("output", displayOnly(msg.display()).save(new CompoundTag()));
                    data.put("pendingFavorite", pending);
                    break;
                }
                CompoundTag entry = new CompoundTag();
                entry.putString("key", msg.key());
                entry.put("output", displayOnly(msg.display()).save(new CompoundTag()));
                list.add(entry);
                data.put("favorites", list);
                data.remove("favoriteNotice");
                data.remove("pendingFavorite");
            }
            case REPLACE_FAVORITE -> {
                if (PlanKey.parse(msg.key()) == null) return;
                ListTag list = data.getList("favorites", Tag.TAG_COMPOUND);
                if (msg.value() < 0 || msg.value() >= list.size()) return;
                for (int i = 0; i < list.size(); i++) {
                    if (i != msg.value() && list.getCompound(i).getString("key").equals(msg.key())) return;
                }
                CompoundTag entry = new CompoundTag();
                entry.putString("key", msg.key());
                entry.put("output", displayOnly(msg.display()).save(new CompoundTag()));
                list.set(msg.value(), entry);
                data.put("favorites", list);
                data.remove("favoriteNotice");
                data.remove("pendingFavorite");
            }
            case REMOVE_FAVORITE -> {
                ListTag list = data.getList("favorites", Tag.TAG_COMPOUND);
                list.removeIf(t -> t instanceof CompoundTag c && c.getString("key").equals(msg.key()));
                data.put("favorites", list);
            }
            case SET_SERVINGS -> data.putByte("servings", (byte) Math.max(1, Math.min(6, msg.value())));
            case TUTORIAL_STEP -> data.putInt("tutorial", data.getInt("tutorial") | (1 << Math.max(0, Math.min(15, msg.value()))));
            case CLEAR_HISTORY -> {
                data.put("history", new ListTag());
                data.remove("last");
            }
            case SET_SEARCH -> data.putString("search", bounded(msg.key(), MAX_SEARCH));
            case SET_FILTER -> {
                if (msg.key().startsWith("catalog:")) data.putString("catalogFilter", bounded(msg.key(), MAX_FILTER));
                else data.putString("filter", bounded(msg.key(), MAX_FILTER));
            }
            case SET_TAB -> data.putString("tab", bounded(msg.key(), 32));
            case SET_ONBOARDING -> data.putByte("onboarding", (byte) Math.max(0, Math.min(3, msg.value())));
            case PIN_RECIPE -> {
                PlanKey key = PlanKey.parse(msg.key());
                if (key == null) return;
                PlanIndex index = PlanIndex.get(player.server.getRecipeManager());
                ItemStack water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
                ItemStack input = index.inputForKey(key, water);
                BrewPlan plan = index.resolve(key, input, List.of());
                if (plan == null) {
                    ModNetwork.notice(player, net.minecraft.network.chat.Component.translatable("notice.immersive_brewing.plan_invalid"), true);
                    return;
                }
                int servings = Math.max(1, Math.min(6, msg.value()));
                CompoundTag pin = new CompoundTag();
                pin.putString("key", plan.key().serialize());
                pin.putByte("servings", (byte) servings);
                pin.put("output", displayOnly(plan.output()).save(new CompoundTag()));
                pin.putInt("generation", index.generation());
                ListTag costs = new ListTag();
                for (BrewPlan.Cost cost : plan.costs(servings)) {
                    if (costs.size() >= PlanKey.MAX_PARTS) break;
                    var id = ForgeRegistries.ITEMS.getKey(cost.item());
                    if (id == null || cost.count() <= 0) continue;
                    CompoundTag encoded = new CompoundTag();
                    encoded.putString("item", id.toString());
                    encoded.putInt("count", Math.min(64, cost.count()));
                    costs.add(encoded);
                }
                pin.put("costs", costs);
                data.put("pinned", pin);
            }
            case DISMISS_PIN -> data.remove("pinned");
            case REPEAT_SETUP -> {
                if (PlanKey.parse(msg.key()) == null) return;
                GuideHooks.repeat(player, msg.key(), displayOnly(msg.display()), Math.max(1, Math.min(6, msg.value())));
            }
            case PRACTICE_PLAN -> {
                PlanKey key = PlanKey.parse(msg.key());
                if (key == null) return;
                PlanIndex index = PlanIndex.get(player.server.getRecipeManager());
                ItemStack water = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
                BrewPlan plan = index.resolve(key, index.inputForKey(key, water), List.of());
                if (plan == null) {
                    ModNetwork.notice(player, net.minecraft.network.chat.Component.translatable("notice.immersive_brewing.practice_unavailable"), true);
                    return;
                }
                int servings = Math.max(1, Math.min(6, msg.value()));
                var setup = com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.setupPlan(plan, servings,
                        com.otectus.immersivebrewing.data.BrewingDefinitions.server());
                var params = com.otectus.immersivebrewing.minigame.brewing.KettleSetup.build(setup, player.getRandom().nextLong(),
                        com.otectus.immersivebrewing.config.ServerConfig.get(com.otectus.immersivebrewing.config.ServerConfig.MAX_BREW_DURATION_SECONDS),
                        com.otectus.immersivebrewing.minigame.brewing.KettleSetup.Assist.NONE);
                CompoundTag display = new CompoundTag();
                display.put("output", displayOnly(plan.output()).save(new CompoundTag()));
                display.putInt("servings", servings);
                ModNetwork.sendTo(player, new com.otectus.immersivebrewing.network.packet.OpenPracticeS2C(
                        com.otectus.immersivebrewing.session.SessionKind.BREWING, plan.brewProfile(), params.encode(), display));
            }
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

    /** Records the last committed result plus a bounded journal summary. Input recordings and phase timelines are omitted. */
    public static void recordResult(ServerPlayer player, CompoundTag result) {
        CompoundTag data = data(player);
        CompoundTag copy = result.copy();
        copy.remove("outputs");
        data.put("last", copy);
        if ("brewing".equals(result.getString("kind"))) {
            ListTag history = data.getList("history", Tag.TAG_COMPOUND);
            history.add(0, summary(result));
            while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
            data.put("history", history);
        }
        save(player, data);
        sync(player);
    }

    /** Compact, stable journal record: identity, time, output, assistance and the aggregate metrics needed to compare. */
    public static CompoundTag summary(CompoundTag result) {
        CompoundTag out = new CompoundTag();
        out.putLong("timestamp", System.currentTimeMillis());
        out.putString("plan", bounded(result.getString("plan"), MAX_KEY));
        out.putByte("servings", (byte) Math.max(1, Math.min(6, result.getInt("count"))));
        ItemStack output = ItemStack.of(result.getCompound("output"));
        out.put("output", displayOnly(output).save(new CompoundTag()));
        if (result.contains("input", Tag.TAG_COMPOUND)) {
            out.put("input", displayOnly(ItemStack.of(result.getCompound("input"))).save(new CompoundTag()));
        }
        for (String key : new String[] {"Q", "E", "C"}) out.putByte(key, (byte) Math.max(0, Math.min(100, result.getInt(key))));
        for (String key : new String[] {"thermal", "circulation", "dosing"}) {
            out.putFloat(key, (float) Math.max(0, Math.min(1, result.getDouble(key))));
        }
        out.putFloat("assistTime", Math.max(1F, result.contains("assistTime") ? result.getFloat("assistTime") : 1F));
        out.putFloat("assistBand", Math.max(1F, result.contains("assistBand") ? result.getFloat("assistBand") : 1F));
        if (result.contains("tip", Tag.TAG_STRING)) out.putString("observation", bounded(result.getString("tip"), MAX_KEY));
        if (result.contains("tipArgs", Tag.TAG_LIST)) {
            ListTag source = result.getList("tipArgs", Tag.TAG_STRING);
            ListTag args = new ListTag();
            for (int i = 0; i < Math.min(2, source.size()); i++) args.add(source.get(i).copy());
            out.put("observationArgs", args);
        }
        return out;
    }

    private static String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
