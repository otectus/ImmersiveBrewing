package com.otectus.immersivebrewing.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a reagent profile or effect policy applies to (spec 16.3): exact ids, tags, namespaces and exact recipe ids.
 * Criteria present together combine with AND; entries inside one list combine with OR; an empty selector matches nothing.
 * The most specific criterion present decides the precedence layer (spec 16.5).
 */
public record Selector(Set<ResourceLocation> ids, Set<ResourceLocation> tags, Set<String> namespaces, Set<ResourceLocation> recipes) {
    public static final Selector NONE = new Selector(Set.of(), Set.of(), Set.of(), Set.of());

    /** Precedence layers, most specific first. {@link #EXPLICIT} is a profile named by a recipe; {@link #FALLBACK} the built-in default. */
    public enum Layer {
        EXPLICIT, RECIPE, ID, TAG, NAMESPACE, FALLBACK;

        public String key() {
            return "layer.immersive_brewing." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Reads a selector object. {@code idField} is "items" for item selectors and "effects" for effect selectors; the tag
     * list is "tags", the namespaces "namespaces" and recipe ids "recipes".
     */
    public static Selector parse(JsonObject json, String idField, boolean allowRecipes) {
        Set<ResourceLocation> ids = new LinkedHashSet<>(Json.ids(json, idField));
        Set<ResourceLocation> tags = new LinkedHashSet<>();
        for (String s : Json.strings(json, "tags")) tags.add(Json.id(s.startsWith("#") ? s.substring(1) : s, "tags"));
        Set<String> namespaces = new LinkedHashSet<>();
        for (String s : Json.strings(json, "namespaces")) {
            if (!ResourceLocation.isValidNamespace(s)) throw new JsonParseException("namespaces: '" + s + "' is not a valid namespace");
            namespaces.add(s);
        }
        Set<ResourceLocation> recipes = new LinkedHashSet<>();
        if (allowRecipes) recipes.addAll(Json.ids(json, "recipes"));
        else Json.reject(json, "recipes", "recipe selectors are not accepted here");
        return new Selector(Set.copyOf(ids), Set.copyOf(tags), Set.copyOf(namespaces), Set.copyOf(recipes));
    }

    public boolean isEmpty() {
        return ids.isEmpty() && tags.isEmpty() && namespaces.isEmpty() && recipes.isEmpty();
    }

    public Layer layer() {
        if (!recipes.isEmpty()) return Layer.RECIPE;
        if (!ids.isEmpty()) return Layer.ID;
        if (!tags.isEmpty()) return Layer.TAG;
        if (!namespaces.isEmpty()) return Layer.NAMESPACE;
        return Layer.FALLBACK;
    }

    /**
     * @param id the entry's registry id
     * @param inTag whether the entry is in a given tag
     * @param recipe the recipe being resolved, or null outside recipe context (a recipe criterion then fails)
     */
    public boolean matches(ResourceLocation id, Predicate<ResourceLocation> inTag, ResourceLocation recipe) {
        if (isEmpty()) return false;
        if (!recipes.isEmpty() && (recipe == null || !recipes.contains(recipe))) return false;
        if (!ids.isEmpty() && !ids.contains(id)) return false;
        if (!tags.isEmpty() && tags.stream().noneMatch(inTag)) return false;
        return namespaces.isEmpty() || namespaces.contains(id.getNamespace());
    }

    public void write(FriendlyByteBuf buf) {
        writeIds(buf, ids);
        writeIds(buf, tags);
        buf.writeVarInt(namespaces.size());
        for (String n : namespaces) buf.writeUtf(n, 128);
        writeIds(buf, recipes);
    }

    public static Selector read(FriendlyByteBuf buf) {
        Set<ResourceLocation> ids = readIds(buf);
        Set<ResourceLocation> tags = readIds(buf);
        int n = Math.min(buf.readVarInt(), Json.MAX_LIST);
        List<String> namespaces = new ArrayList<>(n);
        for (int i = 0; i < n; i++) namespaces.add(buf.readUtf(128));
        Set<ResourceLocation> recipes = readIds(buf);
        return new Selector(ids, tags, Set.copyOf(namespaces), recipes);
    }

    private static void writeIds(FriendlyByteBuf buf, Set<ResourceLocation> ids) {
        buf.writeVarInt(ids.size());
        for (ResourceLocation id : ids) buf.writeResourceLocation(id);
    }

    private static Set<ResourceLocation> readIds(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), Json.MAX_LIST);
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) out.add(buf.readResourceLocation());
        return Set.copyOf(out);
    }
}
