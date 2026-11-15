package com.otectus.immersivebrewing.client.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client presentation profiles (handoff 12.1): {@code assets/<ns>/immersive_brewing/presentation/<path>.json}, named by
 * a workshop profile's {@code presentation} field. They say how a batch looks and sounds (the arrival and incorporation
 * family of its doses, liquid tint and clarity, fragment representatives and their scale, bounded effect strengths, small
 * camera angle adjustments) and nothing else: never a cost, a yield, a reach, a permission or a quality rule, which stay in
 * server data. Every field is optional, bounded and validated; anything missing or invalid falls back to the generic
 * scene, so a resource pack that omits an effect never makes a recipe unusable.
 *
 * <p>{@code immersive_brewing:reagents} is the core reagent table: how each vanilla ingredient arrives in the liquid
 * ({@code assets/immersive_brewing/immersive_brewing/presentation/reagents.json}). Unknown modded ingredients use their own
 * item model as a bounded neutral representative with generic dissolution.
 *
 * <p>Reloaded with the client's resources; changing a profile never needs a world migration.
 */
public final class PresentationProfiles implements ResourceManagerReloadListener {
    public static final int SCHEMA = 1;
    public static final ResourceLocation REAGENTS = ImmersiveBrewing.id("reagents");

    /** How a dose arrives in the liquid and incorporates (handoff 8.2). */
    public enum Arrival {
        /** Small grains or a short stream; brief cloudy trails; dissolve where mixed. */
        CRYSTAL,
        /** A few recognisable pieces that soften, disperse and leave infusion trails. */
        FIBROUS,
        /** A cohesive dollop; thick ribbons fold in and become uniform. */
        PASTE,
        /** A continuous stream; a plume spreads from the impact before blending. */
        LIQUID,
        /** A restrained cluster of flakes or motes; motion changes as it settles in. */
        PARTICULATE,
        /** Unknown: a bounded neutral representation with generic dissolution. */
        GENERIC;

        public static Arrival parse(String name) {
            for (Arrival m : values()) if (m.name().equalsIgnoreCase(name)) return m;
            return null;
        }
    }

    /** A resolved batch presentation. Every number is within its documented bounds. */
    public record Profile(ResourceLocation id, Arrival material, int tint, double clarity, int foam, int fragments, double fragmentScale,
                          @Nullable Item fragmentItem, double motes, double glints, double bubbles, double steam, double cameraPitch,
                          double cameraYaw) {
        public boolean tinted() {
            return tint >= 0;
        }
    }

    /** How one reagent looks arriving: its family, a colour for grains and trails, and the size of its pieces. */
    public record Reagent(Arrival material, int color, double size) {}

    public static final Profile GENERIC = new Profile(ImmersiveBrewing.id("generic"), Arrival.GENERIC, -1, 0.75, 0xF4F6FF, 3, 1.0, null,
            0, 0, 0.5, 0.5, 0, 0);
    private static final Reagent UNKNOWN = new Reagent(Arrival.GENERIC, 0xB8A890, 1.0);
    private static final Set<String> KNOWN = Set.of("schema_version", "station", "material", "liquid", "fragments", "effects", "camera", "sounds");

    private static volatile Map<ResourceLocation, Profile> profiles = Map.of();
    private static volatile Map<Item, Reagent> reagents = Map.of();
    private static volatile int warnings;

    public static final PresentationProfiles LISTENER = new PresentationProfiles();

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Map<ResourceLocation, Profile> loaded = new HashMap<>();
        Map<Item, Reagent> table = new HashMap<>();
        int problems = 0;
        Map<ResourceLocation, Resource> found = manager.listResources("immersive_brewing/presentation", id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
            String path = e.getKey().getPath();
            ResourceLocation id = new ResourceLocation(e.getKey().getNamespace(),
                    path.substring("immersive_brewing/presentation/".length(), path.length() - ".json".length()));
            try (Reader reader = e.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) throw new IllegalArgumentException("not an object");
                if (id.equals(REAGENTS)) problems += parseReagents(json.getAsJsonObject(), table);
                else loaded.put(id, parse(id, json.getAsJsonObject()));
            } catch (Exception ex) {
                problems++;
                ImmersiveBrewing.LOGGER.warn("Immersive Brewing: presentation profile {} is invalid and falls back to the generic scene: {}", id, ex.getMessage());
            }
        }
        profiles = Map.copyOf(loaded);
        reagents = Map.copyOf(table);
        warnings = problems;
        com.otectus.immersivebrewing.client.workshop.WorkshopCamera.invalidate();
    }

    static Profile parse(ResourceLocation id, JsonObject json) {
        int schema = json.has("schema_version") ? json.get("schema_version").getAsInt() : SCHEMA;
        if (schema > SCHEMA || schema < 1) throw new IllegalArgumentException("schema_version " + schema + " is not 1.." + SCHEMA);
        for (String field : json.keySet()) {
            if (!KNOWN.contains(field)) ImmersiveBrewing.LOGGER.warn("Immersive Brewing: presentation profile {}: unknown field '{}' ignored", id, field);
        }
        Arrival material = json.has("material") ? Arrival.parse(json.get("material").getAsString()) : Arrival.GENERIC;
        if (material == null) throw new IllegalArgumentException("material '" + json.get("material").getAsString() + "' is unknown");
        JsonObject liquid = object(json, "liquid");
        int tint = liquid.has("tint") ? color(liquid.get("tint").getAsString()) : -1;
        double clarity = bounded(liquid, "clarity", GENERIC.clarity(), 0.2, 1.0);
        int foam = liquid.has("foam") ? color(liquid.get("foam").getAsString()) : GENERIC.foam();
        JsonObject fragments = object(json, "fragments");
        int count = (int) bounded(fragments, "count", GENERIC.fragments(), 0, 6);
        double scale = bounded(fragments, "scale", 1.0, 0.5, 2.0);
        Item item = null;
        if (fragments.has("item")) {
            ResourceLocation itemId = ResourceLocation.tryParse(fragments.get("item").getAsString());
            item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null || itemId == null || !ForgeRegistries.ITEMS.containsKey(itemId)) {
                ImmersiveBrewing.LOGGER.warn("Immersive Brewing: presentation profile {}: fragments.item '{}' is not an item; using the dose's own", id,
                        fragments.get("item").getAsString());
                item = null;
            }
        }
        JsonObject effects = object(json, "effects");
        JsonObject camera = object(json, "camera");
        return new Profile(id, material, tint, clarity, foam, count, scale, item, bounded(effects, "motes", 0, 0, 1), bounded(effects, "glints", 0, 0, 1),
                bounded(effects, "bubbles", GENERIC.bubbles(), 0, 1), bounded(effects, "steam", GENERIC.steam(), 0, 1),
                bounded(camera, "pitch", 0, -10, 10), bounded(camera, "yaw", 0, -20, 20));
    }

    private static int parseReagents(JsonObject json, Map<Item, Reagent> out) {
        int problems = 0;
        JsonObject entries = object(json, "reagents");
        for (String key : entries.keySet()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (id == null || item == null || !ForgeRegistries.ITEMS.containsKey(id)) continue; // a modded item that is not installed
            try {
                JsonObject e = entries.getAsJsonObject(key);
                Arrival m = Arrival.parse(e.get("material").getAsString());
                if (m == null) throw new IllegalArgumentException("unknown material");
                out.put(item, new Reagent(m, e.has("color") ? color(e.get("color").getAsString()) : UNKNOWN.color(), bounded(e, "size", 1, 0.5, 2)));
            } catch (Exception ex) {
                problems++;
                ImmersiveBrewing.LOGGER.warn("Immersive Brewing: presentation reagent entry {} is invalid: {}", key, ex.getMessage());
            }
        }
        return problems;
    }

    private static JsonObject object(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonObject() ? json.getAsJsonObject(field) : new JsonObject();
    }

    private static double bounded(JsonObject json, String field, double fallback, double min, double max) {
        if (!json.has(field)) return fallback;
        double v = json.get(field).getAsDouble();
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException(field + ": " + v + " is not within " + min + ".." + max);
        return v;
    }

    static int color(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) throw new IllegalArgumentException("colour '" + hex + "' is not #RRGGBB");
        return Integer.parseInt(h.toLowerCase(Locale.ROOT), 16);
    }

    // ---- lookups (render thread) ----

    /** The profile with this id, or the generic one. */
    public static Profile get(@Nullable ResourceLocation id) {
        if (id == null) return GENERIC;
        Profile p = profiles.get(id);
        return p == null ? GENERIC : p;
    }

    /** The profile the batch at a station names (from its snapshot), or null when it names none. */
    @Nullable
    public static Profile forStation(@Nullable BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (pos == null || level == null || !(level.getBlockEntity(pos) instanceof StationBlockEntity station)) return null;
        String id = station.workshopView().getString("presentation");
        if (id.isEmpty()) return null;
        Profile p = profiles.get(ResourceLocation.tryParse(id));
        return p;
    }

    /**
     * How a reagent arrives: the core table, else the family its synced reagent profile prepares as, else a neutral
     * generic representative (no texture analysis, no guessed lore).
     */
    public static Reagent reagent(ItemStack stack) {
        if (stack.isEmpty()) return UNKNOWN;
        Reagent known = reagents.get(stack.getItem());
        if (known != null) return known;
        var choice = BrewingDefinitions.client().reagentProfile(stack.getItem(), null, null);
        if (choice != null) {
            ReagentProfile.Preparation prep = choice.value().preparation();
            if (prep != null) {
                Arrival m = switch (prep.mode()) {
                    case CRYSTALLINE, BRITTLE -> Arrival.CRYSTAL;
                    case FIBROUS -> Arrival.FIBROUS;
                    case SOFT -> Arrival.PASTE;
                    case LIQUID -> Arrival.LIQUID;
                };
                return new Reagent(m, UNKNOWN.color(), 1.0);
            }
        }
        return UNKNOWN;
    }

    /** Problems found at the last reload (for the debug report). */
    public static int warnings() {
        return warnings;
    }

    public static int count() {
        return profiles.size();
    }

    private PresentationProfiles() {}
}
