package com.otectus.immersivebrewing.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** The local copy of the player's notebook data (synced from the server on login and after each change). */
public final class ClientGuide {
    private static CompoundTag data = new CompoundTag();
    private static Pinned cachedPinned;

    public record Favorite(String key, ItemStack output) {}
    public record History(long timestamp, String plan, int servings, ItemStack input, ItemStack output, int quality, int extraction, int control,
                          float thermal, float circulation, float dosing, float assistTime, float assistBand,
                          String observation, List<String> observationArgs) {
        public boolean comparableTo(History other) {
            return !plan.isEmpty() && plan.equals(other.plan) && servings == other.servings;
        }

        public boolean sameAssistance(History other) {
            return Float.compare(assistTime, other.assistTime) == 0 && Float.compare(assistBand, other.assistBand) == 0;
        }
    }
    public record PinCost(ItemStack item, int count) {}
    public record Pinned(String key, int servings, ItemStack output, int generation, List<PinCost> costs) {}

    public static void set(CompoundTag tag) {
        data = tag.copy();
        cachedPinned = decodePinned(data.getCompound("pinned"));
    }

    public static void clear() {
        data = new CompoundTag();
        cachedPinned = null;
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

    public static String search() {
        return data.getString("search");
    }

    public static String catalogFilter() { return data.getString("catalogFilter"); }

    public static String filter() {
        String filter = data.getString("filter");
        return filter.isEmpty() ? "all" : filter;
    }

    public static String lastTab() {
        return data.getString("tab");
    }

    public static int onboardingChoice() {
        return data.getByte("onboarding");
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

    public static List<History> history() {
        List<History> out = new ArrayList<>();
        ListTag list = data.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            List<String> args = new ArrayList<>();
            ListTag encodedArgs = t.getList("observationArgs", Tag.TAG_STRING);
            for (int j = 0; j < encodedArgs.size(); j++) args.add(encodedArgs.getString(j));
            out.add(new History(t.getLong("timestamp"), t.getString("plan"), Math.max(1, t.getByte("servings")),
                    ItemStack.of(t.getCompound("input")), ItemStack.of(t.getCompound("output")), Byte.toUnsignedInt(t.getByte("Q")), Byte.toUnsignedInt(t.getByte("E")),
                    Byte.toUnsignedInt(t.getByte("C")), t.getFloat("thermal"), t.getFloat("circulation"), t.getFloat("dosing"),
                    t.getFloat("assistTime"), t.getFloat("assistBand"), t.getString("observation"), List.copyOf(args)));
        }
        return List.copyOf(out);
    }

    /** The closest older attempt with the same server recipe identity and batch size. */
    public static History previousComparable(int selected) {
        List<History> list = history();
        if (selected < 0 || selected >= list.size()) return null;
        History current = list.get(selected);
        for (int i = selected + 1; i < list.size(); i++) if (current.comparableTo(list.get(i))) return list.get(i);
        return null;
    }

    public static Pinned pinned() {
        return cachedPinned;
    }

    private static Pinned decodePinned(CompoundTag pin) {
        if (pin.isEmpty()) return null;
        List<PinCost> costs = new ArrayList<>();
        ListTag encoded = pin.getList("costs", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(encoded.size(), com.otectus.immersivebrewing.recipe.PlanKey.MAX_PARTS); i++) {
            CompoundTag cost = encoded.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(cost.getString("item"));
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            int count = cost.getInt("count");
            if (item != null && count > 0) costs.add(new PinCost(new ItemStack(item), Math.min(64, count)));
        }
        return new Pinned(pin.getString("key"), Math.max(1, pin.getByte("servings")), ItemStack.of(pin.getCompound("output")),
                pin.getInt("generation"), List.copyOf(costs));
    }

    public static String favoriteNotice() {
        return data.getString("favoriteNotice");
    }

    public static Favorite pendingFavorite() {
        CompoundTag pending = data.getCompound("pendingFavorite");
        return pending.isEmpty() ? null : new Favorite(pending.getString("key"), ItemStack.of(pending.getCompound("output")));
    }

    private ClientGuide() {}
}
