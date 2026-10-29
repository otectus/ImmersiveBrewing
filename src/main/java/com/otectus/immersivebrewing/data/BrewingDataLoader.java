package com.otectus.immersivebrewing.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.EffectPolicies;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads {@code data/<ns>/immersive_brewing/{reagent_profiles,brew_profiles,effect_policies,exclusions}} into one
 * candidate dataset, validates it as a whole and activates it atomically (spec 16.6):
 * <ul>
 * <li>on the first load, invalid files are skipped (reported) and the valid built-in core stays usable;</li>
 * <li>on a later reload, any invalid file keeps the whole previous dataset active and the report names every problem.</li>
 * </ul>
 * A file may carry Forge load {@code conditions}, as recipes do; when they fail the file is not loaded at all (no error),
 * which is how the built-in data for optional mods ({@code compat/}) stays out of worlds without them.
 */
public final class BrewingDataLoader extends SimplePreparableReloadListener<BrewingDataLoader.Candidate> {
    public static final int MAX_DEFINITIONS = 2048;
    private static final Gson GSON = new Gson();
    private static final String ROOT = "immersive_brewing/";
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static volatile Report lastReport = new Report(0, List.of(), List.of(), false);

    private final ICondition.IContext conditions;

    /** The outcome of the last reload, for the log and {@code /immersivebrewing report}. */
    public record Report(int generation, List<String> errors, List<String> warnings, boolean keptPrevious) {}

    /** Parsed files of one reload, before validation. */
    public record Candidate(List<ReagentProfile> reagents, Map<ResourceLocation, BrewProfile> brews,
                            List<EffectPolicyDefinition> policies, Exclusions exclusions, List<String> errors, List<String> warnings) {}

    /** @param conditions the reload's condition context, for definition files that carry {@code conditions} */
    public BrewingDataLoader(ICondition.IContext conditions) {
        this.conditions = conditions;
    }

    public static Report lastReport() {
        return lastReport;
    }

    @Override
    protected Candidate prepare(ResourceManager manager, ProfilerFiller profiler) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ReagentProfile> reagents = new ArrayList<>();
        Map<ResourceLocation, BrewProfile> brews = new LinkedHashMap<>();
        List<EffectPolicyDefinition> policies = new ArrayList<>();
        Exclusions[] exclusions = {Exclusions.NONE};
        read(manager, "reagent_profiles", errors, warnings, conditions, (id, json, w) -> reagents.add(ReagentProfile.parse(id, json, w)));
        read(manager, "brew_profiles", errors, warnings, conditions, (id, json, w) -> brews.put(id, BrewProfile.parse(id, json, w)));
        read(manager, "effect_policies", errors, warnings, conditions, (id, json, w) -> policies.add(EffectPolicyDefinition.parse(id, json, w)));
        read(manager, "exclusions", errors, warnings, conditions, (id, json, w) -> exclusions[0] = exclusions[0].merge(Exclusions.parse(json, w)));
        return new Candidate(reagents, brews, policies, exclusions[0], errors, warnings);
    }

    private interface Parser {
        void parse(ResourceLocation id, JsonObject json, List<String> warnings);
    }

    private static void read(ResourceManager manager, String kind, List<String> errors, List<String> warnings, ICondition.IContext conditions,
                             Parser parser) {
        String dir = ROOT + kind;
        Map<ResourceLocation, Resource> files = manager.listResources(dir, loc -> loc.getPath().endsWith(".json"));
        int count = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath();
            ResourceLocation id = new ResourceLocation(file.getNamespace(), path.substring(dir.length() + 1, path.length() - ".json".length()));
            if (++count > MAX_DEFINITIONS) {
                errors.add(kind + ": more than " + MAX_DEFINITIONS + " files; the rest were ignored");
                break;
            }
            List<String> fileWarnings = new ArrayList<>();
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject json = Json.object(GSON.fromJson(reader, JsonElement.class), "root");
                if (json.has("conditions")) {
                    if (!CraftingHelper.processConditions(json, "conditions", conditions)) continue;
                    json.remove("conditions");
                }
                parser.parse(id, json, fileWarnings);
            } catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
                errors.add(kind + " " + id + ": " + e.getMessage());
            } catch (Exception e) {
                errors.add(kind + " " + id + ": unreadable (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
            for (String w : fileWarnings) warnings.add(kind + " " + id + ": " + w);
        }
    }

    @Override
    protected void apply(Candidate candidate, ResourceManager manager, ProfilerFiller profiler) {
        List<String> errors = new ArrayList<>(candidate.errors());
        List<String> warnings = new ArrayList<>(candidate.warnings());
        // Cross-references: a reagent's reaction profile must exist.
        List<ReagentProfile> reagents = new ArrayList<>();
        for (ReagentProfile p : candidate.reagents()) {
            boolean missing=false;
            for(var item:p.selector().ids()) if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(item)) {
                errors.add("data/"+p.id().getNamespace()+"/immersive_brewing/reagent_profiles/"+p.id().getPath()+".json: selector.items: unknown registry id "+item); missing=true;
            }
            if(missing)continue;
            ResourceLocation ref = p.reaction().profile();
            if (ref != null && !candidate.brews().containsKey(ref)) {
                errors.add("reagent_profiles " + p.id() + ": reaction.profile: unknown brew profile " + ref);
                continue;
            }
            reagents.add(p);
        }
        List<EffectPolicyDefinition> policies = new ArrayList<>();
        for (EffectPolicyDefinition p : candidate.policies()) {
            boolean missing=false;
            for(var effect:p.selector().ids()) if(!net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.containsKey(effect)) {
                errors.add("data/"+p.id().getNamespace()+"/immersive_brewing/effect_policies/"+p.id().getPath()+".json: effects: unknown registry id "+effect); missing=true;
            }
            if(missing)continue;
            if (candidate.exclusions().effectPolicies().contains(p.id())) {
                warnings.add("effect_policies " + p.id() + ": excluded by an exclusions file");
                continue;
            }
            policies.add(p);
        }
        BrewingDefinitions previous = BrewingDefinitions.server();
        // Linear conflict diagnostics; do not turn reload into a pairwise scan of a large pack.
        record SelectorKey(Selector selector, int priority) {}
        java.util.Map<SelectorKey, ResourceLocation> selectors = new java.util.HashMap<>();
        for (var profile : reagents) {
            ResourceLocation first = selectors.putIfAbsent(new SelectorKey(profile.selector(), profile.priority()), profile.id());
            if (first != null) warnings.add("data/" + profile.id().getNamespace() + "/immersive_brewing/reagent_profiles/"
                    + profile.id().getPath() + ".json: selector and priority conflict with " + first + "; lowest id wins");
        }
        boolean initial = previous.generation() == 0;
        if (!errors.isEmpty() && !initial) {
            lastReport = new Report(previous.generation(), List.copyOf(errors), List.copyOf(warnings), true);
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: the reloaded brewing data has {} problem(s); keeping generation {} active", errors.size(), previous.generation());
            errors.forEach(e -> ImmersiveBrewing.LOGGER.error("  {}", e));
            return;
        }
        int generation = GENERATIONS.incrementAndGet();
        BrewingDefinitions.activateServer(new BrewingDefinitions(generation, reagents, candidate.brews(), policies, candidate.exclusions()));
        EffectPolicies.invalidate();
        lastReport = new Report(generation, List.copyOf(errors), List.copyOf(warnings), false);
        if (!errors.isEmpty()) {
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: {} brewing data file(s) were skipped on first load; coverage is incomplete", errors.size());
            errors.forEach(e -> ImmersiveBrewing.LOGGER.error("  {}", e));
        }
        warnings.forEach(w -> ImmersiveBrewing.LOGGER.warn("Immersive Brewing: {}", w));
        ImmersiveBrewing.LOGGER.info("Immersive Brewing: brewing data generation {} active ({} reagent profiles, {} brew profiles, {} effect policies)",
                generation, reagents.size(), candidate.brews().size(), policies.size());
    }

    /** Forgets the server dataset when a server stops, so the next world loads from scratch. */
    public static void reset() {
        BrewingDefinitions.activateServer(BrewingDefinitions.EMPTY);
        EffectPolicies.invalidate();
    }
}
