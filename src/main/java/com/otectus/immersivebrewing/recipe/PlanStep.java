package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.data.Json;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * One step of an infusion plan (spec 16.2): the reagent, the reaction phase that receives it, what the step does to
 * the path (base, effect, corruption, modifier, container) and an optional explicit preparation profile.
 */
public record PlanStep(Ingredient reagent, KettlePhase phase, KettleSetup.Role role, @Nullable ResourceLocation preparationProfile) {
    private static final Set<String> KNOWN = Set.of("reagent", "phase", "role", "preparation_profile");

    public static PlanStep parse(JsonObject json, int index, java.util.List<String> warnings) {
        String field = "steps[" + index + "]";
        if (!json.has("reagent")) throw new JsonParseException(field + ".reagent: missing");
        // Tags are not bound while recipes parse, so whether the ingredient matches anything is checked when the plan
        // index is built, never here (resolving it now would cache a stale item list).
        Ingredient reagent = Ingredient.fromJson(json.get("reagent"));
        String phaseName = Json.string(json, "phase");
        KettlePhase phase = KettlePhase.parse(phaseName);
        if (phase == null) throw new JsonParseException(field + ".phase: '" + phaseName + "' is not one of bloom, infuse, settle");
        KettleSetup.Role role = json.has("role") ? role(Json.string(json, "role"), field) : defaultRole(phase);
        ResourceLocation profile = json.has("preparation_profile") ? Json.id(json, "preparation_profile") : null;
        for (String f : Json.unknown(json, KNOWN)) warnings.add(field + "." + f + ": unknown field ignored");
        return new PlanStep(reagent, phase, role, profile);
    }

    static KettleSetup.Role defaultRole(KettlePhase phase) {
        return switch (phase) {
            case BLOOM -> KettleSetup.Role.BASE;
            case INFUSE -> KettleSetup.Role.EFFECT;
            case SETTLE -> KettleSetup.Role.MODIFIER;
        };
    }

    private static KettleSetup.Role role(String value, String field) {
        for (KettleSetup.Role r : KettleSetup.Role.values()) if (r.name().toLowerCase(Locale.ROOT).equals(value)) return r;
        throw new JsonParseException(field + ".role: '" + value + "' is not one of base, effect, corruption, modifier, container");
    }

    /** Steps that create or change the effect (a reaction worth a signature), as opposed to base, modifier or container steps. */
    public boolean createsEffect() {
        return role == KettleSetup.Role.EFFECT || role == KettleSetup.Role.CORRUPTION;
    }

    public void write(FriendlyByteBuf buf) {
        reagent.toNetwork(buf);
        buf.writeEnum(phase);
        buf.writeEnum(role);
        buf.writeBoolean(preparationProfile != null);
        if (preparationProfile != null) buf.writeResourceLocation(preparationProfile);
    }

    public static PlanStep read(FriendlyByteBuf buf) {
        Ingredient reagent = Ingredient.fromNetwork(buf);
        KettlePhase phase = buf.readEnum(KettlePhase.class);
        KettleSetup.Role role = buf.readEnum(KettleSetup.Role.class);
        ResourceLocation profile = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new PlanStep(reagent, phase, role, profile);
    }
}
