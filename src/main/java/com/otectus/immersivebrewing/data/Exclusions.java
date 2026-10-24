package com.otectus.immersivebrewing.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code data/<ns>/immersive_brewing/exclusions/<path>.json} (spec 16.5): items, item tags, recipes, effect policies and
 * whole adapters this pack refuses. Every exclusion file is merged; an exclusion always wins over any selector, however
 * broad.
 */
public record Exclusions(Set<ResourceLocation> items, Set<ResourceLocation> itemTags, Set<ResourceLocation> recipes,
                         Set<ResourceLocation> effectPolicies, Set<ResourceLocation> adapters) {
    public static final Exclusions NONE = new Exclusions(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    private static final Set<String> KNOWN = Set.of("schema_version", "items", "item_tags", "recipes", "effect_policies", "adapters");

    public static Exclusions parse(JsonObject json, List<String> warnings) {
        int schema = Json.integer(json, "schema_version", 1, 1, 1000);
        if (schema > 1) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (1)");
        Set<ResourceLocation> tags = new LinkedHashSet<>();
        for (String s : Json.strings(json, "item_tags")) tags.add(Json.id(s.startsWith("#") ? s.substring(1) : s, "item_tags"));
        for (String field : Json.unknown(json, KNOWN)) warnings.add(field + ": unknown field ignored");
        return new Exclusions(Set.copyOf(Json.ids(json, "items")), Set.copyOf(tags), Set.copyOf(Json.ids(json, "recipes")),
                Set.copyOf(Json.ids(json, "effect_policies")), Set.copyOf(Json.ids(json, "adapters")));
    }

    public Exclusions merge(Exclusions other) {
        return new Exclusions(union(items, other.items), union(itemTags, other.itemTags), union(recipes, other.recipes),
                union(effectPolicies, other.effectPolicies), union(adapters, other.adapters));
    }

    public boolean excludesItem(ItemStack stack) {
        return excludesItem(stack.getItem());
    }

    public boolean excludesItem(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id != null && items.contains(id)) return true;
        if (itemTags.isEmpty()) return false;
        for (ResourceLocation tag : itemTags) {
            if (item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag))) return true;
        }
        return false;
    }

    public boolean excludesRecipe(ResourceLocation id) {
        return recipes.contains(id);
    }

    public boolean excludesAdapter(ResourceLocation id) {
        return adapters.contains(id);
    }

    public void write(FriendlyByteBuf buf) {
        for (Set<ResourceLocation> set : List.of(items, itemTags, recipes, effectPolicies, adapters)) {
            buf.writeVarInt(set.size());
            for (ResourceLocation id : set) buf.writeResourceLocation(id);
        }
    }

    public static Exclusions read(FriendlyByteBuf buf) {
        @SuppressWarnings("unchecked")
        Set<ResourceLocation>[] sets = new Set[5];
        for (int i = 0; i < 5; i++) {
            int n = Math.min(buf.readVarInt(), 4096);
            Set<ResourceLocation> set = new LinkedHashSet<>();
            for (int j = 0; j < n; j++) set.add(buf.readResourceLocation());
            sets[i] = Set.copyOf(set);
        }
        return new Exclusions(sets[0], sets[1], sets[2], sets[3], sets[4]);
    }

    private static Set<ResourceLocation> union(Set<ResourceLocation> a, Set<ResourceLocation> b) {
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        Set<ResourceLocation> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }
}
