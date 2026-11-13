package com.otectus.immersivebrewing.workshop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.data.Json;
import com.otectus.immersivebrewing.data.Selector;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code data/<ns>/immersive_brewing/workshop_profiles/<path>.json} (handoff 13): how a legitimate transformation is
 * prepared, tended or distilled by hand. A separate, versioned family: it never changes which transformations exist,
 * what they cost or what they produce (the recipe adapters decide that), only the physical process.
 *
 * <ul>
 * <li>{@code kind: preparation} selects reagents ({@code selector}) or refines a reagent profile
 * ({@code source_profile}) and gives the material family, effort window, endpoint and overwork start;</li>
 * <li>{@code kind: brewing} refines a brew profile ({@code target}) with a reaction family, temperature bands and
 * treatment times;</li>
 * <li>{@code kind: distillation} refines an Alembic recipe ({@code target}) with the heart window and run time.</li>
 * </ul>
 * Every number is finite and bounded, intervals are ordered, endpoints reachable; unknown fields are reported.
 */
public record WorkshopProfile(ResourceLocation id, Kind kind, int priority, Selector selector, @Nullable ResourceLocation sourceProfile,
                              @Nullable ResourceLocation target, @Nullable Preparation preparation, @Nullable Brewing brewing,
                              @Nullable Distillation distillation, @Nullable ResourceLocation presentation) {
    public static final int SCHEMA = 1;
    public static final int MAX_OPERATIONS = 4;

    public enum Kind {
        PREPARATION, BREWING, DISTILLATION;

        public String serialized() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Preparation(PreparationMode material, List<String> operations, double requiredWork, double maxWorkPerTick,
                              double usefulLow, double usefulHigh, double endpointLow, double endpointHigh, double overworkStarts) {}

    public record Brewing(ReactionFamily family, double idealLow, double idealHigh, double workLow, double workHigh, double inertia,
                          double stepSeconds, double restSeconds) {}

    public record Distillation(double headsEnd, double tailsStart, double runSeconds) {}

    private static final Set<String> KNOWN = Set.of("schema_version", "kind", "priority", "selector", "source_profile", "target",
            "material", "work", "reaction", "temperature", "treatment", "fractions", "run_seconds", "presentation");
    private static final Set<String> KNOWN_WORK = Set.of("operations", "required_work", "max_work_per_tick", "useful_effort", "endpoint",
            "overwork_starts");
    private static final Set<String> OPERATIONS = Set.of("press", "grind", "fold", "sweep");

    public static WorkshopProfile parse(ResourceLocation id, JsonObject json, List<String> warnings) {
        int schema = Json.integer(json, "schema_version", SCHEMA, 1, 1000);
        if (schema > SCHEMA) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (" + SCHEMA + ")");
        String kindName = Json.string(json, "kind");
        Kind kind = null;
        for (Kind k : Kind.values()) if (k.serialized().equals(kindName)) kind = k;
        if (kind == null) throw new JsonParseException("kind: '" + kindName + "' is not one of preparation, brewing, distillation");
        int priority = Json.integer(json, "priority", 0, -100000, 100000);
        ResourceLocation presentation = json.has("presentation") ? Json.id(json, "presentation") : null;
        Selector selector = Selector.NONE;
        ResourceLocation source = null;
        ResourceLocation target = null;
        Preparation preparation = null;
        Brewing brewing = null;
        Distillation distillation = null;
        switch (kind) {
            case PREPARATION -> {
                if (json.has("selector")) selector = Selector.parse(Json.object(json, "selector"), "items", false);
                if (json.has("source_profile")) source = Json.id(json, "source_profile");
                if (selector.isEmpty() && source == null) throw new JsonParseException("preparation needs a non-empty selector or a source_profile");
                String materialName = Json.string(json, "material");
                PreparationMode material = PreparationMode.parse(materialName);
                if (material == null) throw new JsonParseException("material: '" + materialName + "' is not one of crystalline, fibrous, soft, brittle, liquid");
                JsonObject work = Json.object(json, "work");
                List<String> operations = work.has("operations") ? Json.strings(work, "operations") : List.of("press", "grind", "fold");
                if (operations.isEmpty() || operations.size() > MAX_OPERATIONS) throw new JsonParseException("work.operations: 1.." + MAX_OPERATIONS + " entries");
                for (String op : operations) if (!OPERATIONS.contains(op)) throw new JsonParseException("work.operations: unknown operation '" + op + "'");
                double required = Json.number(work, "required_work", 1.0, 0.25, 4.0);
                double perTick = Json.number(work, "max_work_per_tick", 0.05, 0.005, 0.2);
                double[] useful = work.has("useful_effort") ? Json.range(work, "useful_effort", 0.05, 1.0) : new double[] {0.25, 0.70};
                if (useful[1] - useful[0] < 0.05) throw new JsonParseException("work.useful_effort: narrower than 0.05 can not be chosen");
                double[] endpoint = Json.range(work, "endpoint", 0.05, 0.95);
                if (endpoint[1] - endpoint[0] < 0.04) throw new JsonParseException("work.endpoint: narrower than 0.04 is not reachable");
                double overwork = Json.number(work, "overwork_starts", Math.min(0.98, endpoint[1] + 0.08), 0.06, 0.99);
                if (overwork <= endpoint[1]) throw new JsonParseException("work.overwork_starts: must be above the endpoint's high end " + endpoint[1]);
                for (String field : Json.unknown(work, KNOWN_WORK)) warnings.add("work." + field + ": unknown field ignored");
                preparation = new Preparation(material, List.copyOf(operations), required, perTick, useful[0], useful[1], endpoint[0], endpoint[1], overwork);
            }
            case BREWING -> {
                target = Json.id(json, "target");
                String familyName = Json.optString(json, "reaction", "generic");
                ReactionFamily family = ReactionFamily.parse(familyName);
                if (family == null) throw new JsonParseException("reaction: unknown reaction family '" + familyName + "'");
                JsonObject temperature = Json.optObject(json, "temperature");
                double[] ideal = temperature.has("ideal") ? Json.range(temperature, "ideal", 0.2, 0.95) : new double[] {family.idealLow, family.idealHigh};
                if (ideal[1] - ideal[0] < 0.06) throw new JsonParseException("temperature.ideal: narrower than 0.06 is not readable");
                double[] workable = temperature.has("workable") ? Json.range(temperature, "workable", 0.15, 0.98)
                        : new double[] {Math.max(0.2, ideal[0] - 0.12), Math.min(0.95, ideal[1] + 0.14)};
                if (workable[0] > ideal[0] || workable[1] < ideal[1]) throw new JsonParseException("temperature.workable: must contain temperature.ideal");
                double inertia = Json.number(temperature, "inertia", 1.0, 0.5, 2.0);
                JsonObject treatment = Json.optObject(json, "treatment");
                double step = Json.number(treatment, "step_seconds", 9.0, 3.0, 30.0);
                double rest = Json.number(treatment, "rest_seconds", 3.0, 1.0, 10.0);
                brewing = new Brewing(family, ideal[0], ideal[1], workable[0], workable[1], inertia, step, rest);
            }
            case DISTILLATION -> {
                target = Json.id(json, "target");
                double[] fractions = json.has("fractions") ? Json.range(json, "fractions", 0.08, 0.92)
                        : new double[] {AlembicProcess.HEADS_END, AlembicProcess.TAILS_START};
                if (fractions[0] > 0.40) throw new JsonParseException("fractions[0]: the heads must end by 0.40");
                if (fractions[1] - fractions[0] < 0.25) throw new JsonParseException("fractions: the clear heart must span at least 0.25 of the run");
                double run = Json.number(json, "run_seconds", AlembicProcess.RUN_SECONDS, 10.0, 60.0);
                distillation = new Distillation(fractions[0], fractions[1], run);
            }
        }
        for (String field : Json.unknown(json, KNOWN)) warnings.add(field + ": unknown field ignored");
        return new WorkshopProfile(id, kind, priority, selector, source, target, preparation, brewing, distillation, presentation);
    }
}
