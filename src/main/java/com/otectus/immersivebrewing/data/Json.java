package com.otectus.immersivebrewing.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strict JSON readers for the datapack formats. Every failure names the field, so the reload report can print
 * "resource id: field: problem" (spec 16.6). Numbers must be finite and inside the documented range.
 */
public final class Json {
    /** Bound on any list in a definition, so a hostile pack cannot make the server allocate without limit. */
    public static final int MAX_LIST = 256;

    public static JsonObject object(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) throw new JsonParseException(field + ": expected an object");
        return element.getAsJsonObject();
    }

    public static JsonObject object(JsonObject json, String field) {
        return object(json.get(field), field);
    }

    public static JsonObject optObject(JsonObject json, String field) {
        return json.has(field) ? object(json.get(field), field) : new JsonObject();
    }

    public static String string(JsonObject json, String field) {
        JsonElement e = json.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException(field + ": expected a string");
        return e.getAsString();
    }

    public static String optString(JsonObject json, String field, String fallback) {
        return json.has(field) ? string(json, field) : fallback;
    }

    public static ResourceLocation id(JsonObject json, String field) {
        return id(string(json, field), field);
    }

    public static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new JsonParseException(field + ": '" + value + "' is not a valid resource id");
        return id;
    }

    public static boolean bool(JsonObject json, String field, boolean fallback) {
        if (!json.has(field)) return fallback;
        JsonElement e = json.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new JsonParseException(field + ": expected true or false");
        return e.getAsBoolean();
    }

    public static double number(JsonObject json, String field, double min, double max) {
        JsonElement e = json.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new JsonParseException(field + ": expected a number");
        return check(e.getAsDouble(), field, min, max);
    }

    public static double number(JsonObject json, String field, double fallback, double min, double max) {
        return json.has(field) ? number(json, field, min, max) : fallback;
    }

    public static int integer(JsonObject json, String field, int min, int max) {
        double v = number(json, field, min, max);
        if (v != Math.rint(v)) throw new JsonParseException(field + ": expected a whole number");
        return (int) v;
    }

    public static int integer(JsonObject json, String field, int fallback, int min, int max) {
        return json.has(field) ? integer(json, field, min, max) : fallback;
    }

    public static double check(double value, String field, double min, double max) {
        if (!Double.isFinite(value)) throw new JsonParseException(field + ": must be a finite number");
        if (value < min || value > max) throw new JsonParseException(field + ": " + value + " is outside " + min + ".." + max);
        return value;
    }

    /** A two-number array {@code [low, high]} with {@code min <= low <= high <= max}. */
    public static double[] range(JsonObject json, String field, double min, double max) {
        JsonArray array = array(json, field);
        if (array.size() != 2) throw new JsonParseException(field + ": expected [low, high]");
        double low = check(array.get(0).getAsDouble(), field + "[0]", min, max);
        double high = check(array.get(1).getAsDouble(), field + "[1]", min, max);
        if (low > high) throw new JsonParseException(field + ": low " + low + " is above high " + high);
        return new double[] {low, high};
    }

    public static JsonArray array(JsonObject json, String field) {
        JsonElement e = json.get(field);
        if (e == null || !e.isJsonArray()) throw new JsonParseException(field + ": expected an array");
        JsonArray array = e.getAsJsonArray();
        if (array.size() > MAX_LIST) throw new JsonParseException(field + ": more than " + MAX_LIST + " entries");
        return array;
    }

    public static List<String> strings(JsonObject json, String field) {
        if (!json.has(field)) return List.of();
        JsonArray array = array(json, field);
        List<String> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement e = array.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new JsonParseException(field + "[" + i + "]: expected a string");
            out.add(e.getAsString());
        }
        return out;
    }

    public static List<ResourceLocation> ids(JsonObject json, String field) {
        List<ResourceLocation> out = new ArrayList<>();
        for (String s : strings(json, field)) out.add(id(s, field));
        return out;
    }

    /** Fails on a field this format rejects outright (documented as rejected in docs/DATAPACKS.md). */
    public static void reject(JsonObject json, String field, String reason) {
        if (json.has(field)) throw new JsonParseException(field + ": " + reason);
    }

    /** Names of fields not in {@code known}, for a warning (unknown fields are ignored, never silently trusted). */
    public static List<String> unknown(JsonObject json, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String key : json.keySet()) if (!known.contains(key)) out.add(key);
        return out;
    }

    private Json() {}
}
