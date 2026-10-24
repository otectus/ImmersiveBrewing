package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.data.Json;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which finished potions an Alembic recipe accepts: container items, then potion ids or potion namespaces, minus
 * excluded potions; {@code requires_effects} skips base brews whose quality changes nothing.
 */
public record PotionMatcher(Set<Item> items, Set<ResourceLocation> potions, Set<String> namespaces, Set<ResourceLocation> excluded,
                            boolean requiresEffects) {

    public static PotionMatcher parse(JsonObject json, String field) {
        Set<Item> items = new LinkedHashSet<>();
        for (ResourceLocation id : Json.ids(json, "items")) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) throw new JsonParseException(field + ".items: unknown item " + id);
            items.add(item);
        }
        if (items.isEmpty()) throw new JsonParseException(field + ".items: at least one container item is required");
        Set<String> namespaces = new LinkedHashSet<>(Json.strings(json, "potion_namespaces"));
        return new PotionMatcher(Set.copyOf(items), Set.copyOf(Json.ids(json, "potions")), Set.copyOf(namespaces),
                Set.copyOf(Json.ids(json, "exclude_potions")), Json.bool(json, "requires_effects", true));
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty() || !items.contains(stack.getItem())) return false;
        ResourceLocation potion = ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(stack));
        if (potion == null || excluded.contains(potion)) return false;
        if (requiresEffects && PotionUtils.getMobEffects(stack).isEmpty()) return false;
        if (!potions.isEmpty() || !namespaces.isEmpty()) {
            return potions.contains(potion) || namespaces.contains(potion.getNamespace());
        }
        return true;
    }

    public List<Item> itemList() {
        return List.copyOf(items);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (Item i : items) buf.writeId(BuiltInRegistries.ITEM, i);
        writeIds(buf, potions);
        buf.writeVarInt(namespaces.size());
        for (String n : namespaces) buf.writeUtf(n, 128);
        writeIds(buf, excluded);
        buf.writeBoolean(requiresEffects);
    }

    public static PotionMatcher read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 64);
        Set<Item> items = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) items.add(buf.readById(BuiltInRegistries.ITEM));
        Set<ResourceLocation> potions = readIds(buf);
        int m = Math.min(buf.readVarInt(), 64);
        Set<String> namespaces = new LinkedHashSet<>();
        for (int i = 0; i < m; i++) namespaces.add(buf.readUtf(128));
        Set<ResourceLocation> excluded = readIds(buf);
        return new PotionMatcher(Set.copyOf(items), potions, Set.copyOf(namespaces), excluded, buf.readBoolean());
    }

    private static void writeIds(FriendlyByteBuf buf, Set<ResourceLocation> ids) {
        buf.writeVarInt(ids.size());
        for (ResourceLocation id : ids) buf.writeResourceLocation(id);
    }

    private static Set<ResourceLocation> readIds(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 512);
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) out.add(buf.readResourceLocation());
        return Set.copyOf(out);
    }
}
