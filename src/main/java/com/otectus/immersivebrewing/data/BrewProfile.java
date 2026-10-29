package com.otectus.immersivebrewing.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.minigame.brewing.Disturbance;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.minigame.brewing.Signature;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code data/<ns>/immersive_brewing/brew_profiles/<path>.json}: the reaction a kettle session presents (spec 7, 16.1).
 * {@code infuse} is required; {@code bloom} and {@code settle} override the shared defaults. The server's copy decides
 * scoring; {@code presentation} only chooses colours and particles.
 */
public record BrewProfile(ResourceLocation id, KettleSetup.Reaction infuse, @Nullable KettleSetup.Reaction bloom,
                          @Nullable KettleSetup.Reaction settle, List<Disturbance.Kind> disturbances, double inertia,
                          int accentColor, String particle) {
    public static final int SCHEMA = 1;
    private static final Set<String> KNOWN = Set.of("schema_version", "infuse", "bloom", "settle", "disturbances", "inertia", "presentation");
    private static final Set<String> KNOWN_PHASE = Set.of("signature", "heat_band", "circulation_band", "initial_trouble");

    /** The safe fallback for reactions without a profile: no signature, broad bands. */
    public static BrewProfile generic(ResourceLocation id) {
        return new BrewProfile(id, KettleSetup.DEFAULT_INFUSE, null, null, List.of(Disturbance.Kind.COLD_DRAFT, Disturbance.Kind.BURNER_FLARE),
                1.0, 0xFFFFFF, "steam");
    }

    public KettleSetup.Reaction bloomOrDefault() {
        return bloom != null ? bloom : KettleSetup.DEFAULT_BLOOM;
    }

    public KettleSetup.Reaction settleOrDefault() {
        return settle != null ? settle : KettleSetup.DEFAULT_SETTLE;
    }

    public static BrewProfile parse(ResourceLocation id, JsonObject json, List<String> warnings) {
        int schema = Json.integer(json, "schema_version", SCHEMA, 1, 1000);
        if (schema > SCHEMA) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (" + SCHEMA + ")");
        KettleSetup.Reaction infuse = phase(Json.object(json, "infuse"), "infuse", warnings);
        KettleSetup.Reaction bloom = json.has("bloom") ? phase(Json.object(json, "bloom"), "bloom", warnings) : null;
        KettleSetup.Reaction settle = json.has("settle") ? phase(Json.object(json, "settle"), "settle", warnings) : null;
        List<Disturbance.Kind> disturbances = new ArrayList<>();
        for (String s : Json.strings(json, "disturbances")) {
            Disturbance.Kind kind = Disturbance.Kind.parse(s);
            if (kind == null) throw new JsonParseException("disturbances: '" + s + "' is not one of cold_draft, burner_flare, surge");
            if (!disturbances.contains(kind)) disturbances.add(kind);
        }
        if (!json.has("disturbances")) disturbances.addAll(List.of(Disturbance.Kind.values()));
        double inertia = Json.number(json, "inertia", 1.0, 0.5, 2.0);
        JsonObject presentation = Json.optObject(json, "presentation");
        int accent = color(Json.optString(presentation, "accent", "#FFFFFF"), "presentation.accent");
        String particle = Json.optString(presentation, "particle", "steam");
        if(!Set.of("steam","foam","bubble","glint","mote").contains(particle)) {
            warnings.add("presentation.particle: unknown '"+particle+"'; using steam"); particle="steam";
        }
        for (String field : Json.unknown(json, KNOWN)) warnings.add(field + ": unknown field ignored");
        return new BrewProfile(id, infuse, bloom, settle, List.copyOf(disturbances), inertia, accent, particle);
    }

    private static KettleSetup.Reaction phase(JsonObject json, String name, List<String> warnings) {
        String sigName = Json.optString(json, "signature", "none");
        Signature signature = Signature.parse(sigName);
        if (signature == null) throw new JsonParseException(name + ".signature: unknown signature '" + sigName + "'");
        double[] heat = Json.range(json, "heat_band", 0.0, 1.0);
        double[] circ = Json.range(json, "circulation_band", 0.0, 1.0);
        if (heat[1] - heat[0] < 0.06) throw new JsonParseException(name + ".heat_band: narrower than 0.06 is not readable");
        if (circ[1] - circ[0] < 0.10) throw new JsonParseException(name + ".circulation_band: narrower than 0.10 is not readable");
        double initial = Json.number(json, "initial_trouble", 0.0, 0.0, 0.6);
        for (String field : Json.unknown(json, KNOWN_PHASE)) warnings.add(name + "." + field + ": unknown field ignored");
        return new KettleSetup.Reaction(signature, heat[0], heat[1], circ[0], circ[1], initial);
    }

    static int color(String value, String field) {
        String v = value.startsWith("#") ? value.substring(1) : value;
        try {
            if (v.length() != 6) throw new NumberFormatException();
            return Integer.parseInt(v, 16);
        } catch (NumberFormatException e) {
            throw new JsonParseException(field + ": '" + value + "' is not a #RRGGBB colour");
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
        writeReaction(buf, infuse);
        buf.writeBoolean(bloom != null);
        if (bloom != null) writeReaction(buf, bloom);
        buf.writeBoolean(settle != null);
        if (settle != null) writeReaction(buf, settle);
        buf.writeVarInt(disturbances.size());
        for (Disturbance.Kind k : disturbances) buf.writeEnum(k);
        buf.writeDouble(inertia);
        buf.writeInt(accentColor);
        buf.writeUtf(particle, 64);
    }

    public static BrewProfile read(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        KettleSetup.Reaction infuse = readReaction(buf);
        KettleSetup.Reaction bloom = buf.readBoolean() ? readReaction(buf) : null;
        KettleSetup.Reaction settle = buf.readBoolean() ? readReaction(buf) : null;
        int n = Math.min(buf.readVarInt(), Disturbance.Kind.values().length);
        List<Disturbance.Kind> kinds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) kinds.add(buf.readEnum(Disturbance.Kind.class));
        return new BrewProfile(id, infuse, bloom, settle, List.copyOf(kinds), Json.check(buf.readDouble(), "inertia", 0.5, 2.0),
                buf.readInt(), buf.readUtf(64));
    }

    private static void writeReaction(FriendlyByteBuf buf, KettleSetup.Reaction r) {
        buf.writeEnum(r.signature());
        buf.writeDouble(r.heatLow());
        buf.writeDouble(r.heatHigh());
        buf.writeDouble(r.circLow());
        buf.writeDouble(r.circHigh());
        buf.writeDouble(r.initialTrouble());
    }

    private static KettleSetup.Reaction readReaction(FriendlyByteBuf buf) {
        Signature s = buf.readEnum(Signature.class);
        double hl = Json.check(buf.readDouble(), "heat", 0, 1);
        double hh = Json.check(buf.readDouble(), "heat", hl, 1);
        double cl = Json.check(buf.readDouble(), "circulation", 0, 1);
        double ch = Json.check(buf.readDouble(), "circulation", cl, 1);
        return new KettleSetup.Reaction(s, hl, hh, cl, ch, Json.check(buf.readDouble(), "initial_trouble", 0, 0.6));
    }
}
