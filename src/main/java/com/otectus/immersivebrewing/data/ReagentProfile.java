package com.otectus.immersivebrewing.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * {@code data/<ns>/immersive_brewing/reagent_profiles/<path>.json} (spec 16.3): how an effect reagent behaves in the
 * Mortar and what it does to the kettle when added. A reagent with no matching profile, or a profile without
 * {@code preparation}, is unprofiled: it skips preparation and brews at the raw extraction of 50. Chemistry is never
 * inferred from an item's name.
 */
public record ReagentProfile(ResourceLocation id, int priority, Selector selector, @Nullable Preparation preparation, Reaction reaction) {
    public static final int SCHEMA = 1;
    private static final Set<String> KNOWN = Set.of("schema_version", "priority", "selector", "preparation", "reaction");
    private static final Set<String> KNOWN_PREPARATION = Set.of("mode", "duration_ticks", "endpoint", "pressure_limit", "overwork_threshold");
    private static final Set<String> KNOWN_REACTION = Set.of("profile", "addition_cooling", "foam_sensitivity", "sensitivity");

    /** Mortar behaviour. Values are normalised 0..1 processed fractions; durations are game ticks. */
    public record Preparation(PreparationMode mode, int durationTicks, double endpointLow, double endpointHigh,
                              double pressureLimit, double overworkThreshold) {}

    /**
     * Kettle behaviour: the brew profile this reagent's reaction uses (or null to use the plan's), how much the addition
     * cools the liquid and how strongly it raises the phase's signature.
     */
    public record Reaction(@Nullable ResourceLocation profile, double additionCooling, double sensitivity) {
        public static final Reaction DEFAULT = new Reaction(null, 0.06, 1.0);
    }

    public static ReagentProfile parse(ResourceLocation id, JsonObject json, List<String> warnings) {
        int schema = Json.integer(json, "schema_version", SCHEMA, 1, 1000);
        if (schema > SCHEMA) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (" + SCHEMA + ")");
        int priority = Json.integer(json, "priority", 0, -100000, 100000);
        Selector selector = Selector.parse(Json.object(json, "selector"), "items", true);
        if (selector.isEmpty()) throw new JsonParseException("selector: an empty selector matches nothing");
        Preparation preparation = null;
        if (json.has("preparation")) {
            JsonObject p = Json.object(json, "preparation");
            String modeName = Json.string(p, "mode");
            if (!modeName.equals("none")) {
                PreparationMode mode = PreparationMode.parse(modeName);
                if (mode == null) throw new JsonParseException("preparation.mode: '" + modeName + "' is not one of crystalline, fibrous, soft, brittle, liquid, none");
                int duration = Json.integer(p, "duration_ticks", 240, 100, 400);
                double[] endpoint = Json.range(p, "endpoint", 0.05, 0.95);
                double limit = Json.number(p, "pressure_limit", 0.70, 0.1, 1.0);
                double overwork = Json.number(p, "overwork_threshold", Math.min(0.97, endpoint[1] + 0.08), 0.06, 0.99);
                if (overwork <= endpoint[1]) throw new JsonParseException("preparation.overwork_threshold: must be above the endpoint's high end " + endpoint[1]);
                preparation = new Preparation(mode, duration, endpoint[0], endpoint[1], limit, overwork);
                for (String field : Json.unknown(p, KNOWN_PREPARATION)) warnings.add("preparation." + field + ": unknown field ignored");
            }
        }
        Reaction reaction = Reaction.DEFAULT;
        if (json.has("reaction")) {
            JsonObject r = Json.object(json, "reaction");
            ResourceLocation profile = r.has("profile") ? Json.id(r, "profile") : null;
            double cooling = Json.number(r, "addition_cooling", 0.06, 0.0, 0.3);
            double sensitivity = Json.number(r, "foam_sensitivity", Json.number(r, "sensitivity", 1.0, 0.0, 2.0), 0.0, 2.0);
            reaction = new Reaction(profile, cooling, sensitivity);
            for (String field : Json.unknown(r, KNOWN_REACTION)) warnings.add("reaction." + field + ": unknown field ignored");
        }
        for (String field : Json.unknown(json, KNOWN)) warnings.add(field + ": unknown field ignored");
        return new ReagentProfile(id, priority, selector, preparation, reaction);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        buf.writeVarInt(priority);
        selector.write(buf);
        buf.writeBoolean(preparation != null);
        if (preparation != null) {
            buf.writeEnum(preparation.mode());
            buf.writeVarInt(preparation.durationTicks());
            buf.writeDouble(preparation.endpointLow());
            buf.writeDouble(preparation.endpointHigh());
            buf.writeDouble(preparation.pressureLimit());
            buf.writeDouble(preparation.overworkThreshold());
        }
        buf.writeBoolean(reaction.profile() != null);
        if (reaction.profile() != null) buf.writeResourceLocation(reaction.profile());
        buf.writeDouble(reaction.additionCooling());
        buf.writeDouble(reaction.sensitivity());
    }

    public static ReagentProfile read(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int priority = buf.readVarInt();
        Selector selector = Selector.read(buf);
        Preparation preparation = null;
        if (buf.readBoolean()) {
            PreparationMode mode = buf.readEnum(PreparationMode.class);
            int duration = Math.max(100, Math.min(400, buf.readVarInt()));
            double lo = Json.check(buf.readDouble(), "endpoint", 0.05, 0.95);
            double hi = Json.check(buf.readDouble(), "endpoint", lo, 0.95);
            double limit = Json.check(buf.readDouble(), "pressure_limit", 0.1, 1.0);
            double overwork = Json.check(buf.readDouble(), "overwork_threshold", hi, 0.99);
            preparation = new Preparation(mode, duration, lo, hi, limit, overwork);
        }
        ResourceLocation profile = buf.readBoolean() ? buf.readResourceLocation() : null;
        Reaction reaction = new Reaction(profile, Json.check(buf.readDouble(), "addition_cooling", 0, 0.3),
                Json.check(buf.readDouble(), "sensitivity", 0, 2));
        return new ReagentProfile(id, priority, selector, preparation, reaction);
    }
}
