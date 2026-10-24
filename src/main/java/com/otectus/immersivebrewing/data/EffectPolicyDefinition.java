package com.otectus.immersivebrewing.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code data/<ns>/immersive_brewing/effect_policies/<path>.json} (spec 16.4): which mob effects may have their duration
 * scaled by quality, and within which bounds. Effects without a policy stay metadata-only. The server's global bounds
 * are applied last and can only narrow these values.
 */
public record EffectPolicyDefinition(ResourceLocation id, int priority, Selector selector, Mode mode, double minimum, double maximum) {
    public static final int SCHEMA = 1;
    private static final Set<String> KNOWN = Set.of("schema_version", "priority", "effects", "tags", "namespaces", "quality_mode",
            "minimum_multiplier", "maximum_multiplier", "allow_amplifier_change", "allow_infinite_duration_change");

    public enum Mode {
        /** Finite durations scale by the quality multiplier. */
        DURATION,
        /** Quality is recorded and shown but never changes the effect. */
        METADATA_ONLY;

        static Mode parse(String value) {
            return switch (value) {
                case "duration" -> DURATION;
                case "metadata_only" -> METADATA_ONLY;
                default -> throw new JsonParseException("quality_mode: '" + value + "' is not one of duration, metadata_only");
            };
        }

        public String serialized() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static EffectPolicyDefinition parse(ResourceLocation id, JsonObject json, List<String> warnings) {
        int schema = Json.integer(json, "schema_version", SCHEMA, 1, 1000);
        if (schema > SCHEMA) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (" + SCHEMA + ")");
        int priority = Json.integer(json, "priority", 0, -100000, 100000);
        Selector selector = Selector.parse(json, "effects", false);
        if (selector.isEmpty()) throw new JsonParseException("effects: an empty selector matches nothing; name effects, tags or namespaces");
        Mode mode = Mode.parse(Json.optString(json, "quality_mode", "duration"));
        double min = Json.number(json, "minimum_multiplier", 0.80, 0.0, 1.0);
        double max = Json.number(json, "maximum_multiplier", 1.20, 1.0, 4.0);
        if (Json.bool(json, "allow_amplifier_change", false)) {
            throw new JsonParseException("allow_amplifier_change: true is rejected in v1.0; quality never changes an amplifier");
        }
        if (Json.bool(json, "allow_infinite_duration_change", false)) {
            throw new JsonParseException("allow_infinite_duration_change: true is reserved and rejected in v1.0; infinite durations stay unchanged");
        }
        for (String field : Json.unknown(json, KNOWN)) warnings.add(field + ": unknown field ignored");
        return new EffectPolicyDefinition(id, priority, selector, mode, min, max);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        buf.writeVarInt(priority);
        selector.write(buf);
        buf.writeEnum(mode);
        buf.writeDouble(minimum);
        buf.writeDouble(maximum);
    }

    public static EffectPolicyDefinition read(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        int priority = buf.readVarInt();
        Selector selector = Selector.read(buf);
        Mode mode = buf.readEnum(Mode.class);
        double min = Json.check(buf.readDouble(), "minimum_multiplier", 0, 1);
        double max = Json.check(buf.readDouble(), "maximum_multiplier", 1, 4);
        return new EffectPolicyDefinition(id, priority, selector, mode, min, max);
    }
}
