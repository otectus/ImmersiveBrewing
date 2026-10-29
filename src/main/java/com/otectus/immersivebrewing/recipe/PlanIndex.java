package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.api.BrewingAdapter;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every infusion recipe checked against its adapter, indexed for plan search (spec 13, 15.3, 16.2). Built lazily on the
 * server thread for one recipe manager, one data generation and one relevant config; never per tick or per tooltip.
 *
 * <ul>
 * <li>A concrete recipe is replayed step by step through its adapter on canonical copies; every candidate reagent of a
 * step must give the same result, and the final result must satisfy {@code expected_output}. A failing recipe is
 * reported with the reason and never offered.</li>
 * <li>Every suffix of a validated recipe is an entry point: entering from Awkward drops the nether-wart step and its
 * cost.</li>
 * <li>Plans chain validated segments end to start (a modifier recipe after an effect recipe, a container conversion
 * after either), up to {@code maxPlanSteps} steps, never revisiting a state, and at most {@link #MAX_PLANS} per input.</li>
 * <li>Generic container recipes ({@code match: item}) are checked per concrete state when a chain uses them.</li>
 * <li>The legacy plansFrom API stops at {@link #MAX_PLANS}; catalog queries instead traverse destinations incrementally and report budgets explicitly.</li>
 * </ul>
 */
public final class PlanIndex {
    public static final int MAX_PLANS = 512;
    private static final int CACHE = 64;

    public enum Status { SUPPORTED, METADATA_ONLY, EXCLUDED, UNSUPPORTED }

    /** One line of the coverage report. */
    public record Entry(ResourceLocation recipe, Status status, String reason, List<String> warnings) {}

    /** A validated step of a concrete recipe. */
    record ValidStep(PlanStep spec, Item reagent, BrewingAdapter adapter, BrewingAdapter.BatchPolicy batch, ItemStack before, ItemStack after) {}

    record Validated(InfusionRecipe recipe, List<ValidStep> steps) {}

    /** A suffix {@code [from, steps)} of a validated recipe. */
    record Segment(Validated recipe, int from) {
        ItemStack start() {
            return recipe.steps.get(from).before();
        }

        ItemStack end() {
            return recipe.steps.get(recipe.steps.size() - 1).after();
        }

        int length() {
            return recipe.steps.size() - from;
        }
    }

    private static volatile PlanIndex server;
    private static final java.util.concurrent.atomic.AtomicInteger EPOCHS = new java.util.concurrent.atomic.AtomicInteger();

    private final RecipeManager recipes;
    private final int generation;
    private final int dataGeneration;
    private final int configKey;
    private final BrewingDefinitions defs;
    private final Map<ResourceLocation, Validated> validated = new LinkedHashMap<>();
    private final Map<Long, List<Segment>> byStart = new HashMap<>();
    private final List<InfusionRecipe> generic = new ArrayList<>();
    private final Map<ResourceLocation, InfusionRecipe> genericById = new LinkedHashMap<>();
    private final List<Entry> report = new ArrayList<>();
    private final Map<Long, Catalog> catalogs = new LinkedHashMap<>(16, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Catalog> entry) { return size() > 32; }
    };

    /** Incremental destination search. The first (shortest) path to each state is expanded before alternatives.
     * Work counts every concrete/generic candidate inspected, including refusals, on the server tick service. */
    public final class Catalog {
        private final ItemStack input;
        private final java.util.PriorityQueue<Node> pending = new java.util.PriorityQueue<>(Comparator.comparingInt(Node::steps));
        private final Map<Long, Integer> expanded = new HashMap<>();
        private final Set<String> dedupe = new HashSet<>();
        private final List<BrewPlan> results = new ArrayList<>();
        private Node active;
        private List<Segment> activeSegments = List.of();
        private int segmentCursor, genericCursor, work, omitted;
        private boolean limited;
        private Catalog(ItemStack input) {
            this.input = input.copyWithCount(1);
            pending.add(new Node(this.input, List.of(), 0, Set.of(PotionContentsService.fingerprint(input))));
        }
        public void advance(int budget) {
            int maxSteps = ServerConfig.get(ServerConfig.MAX_PLAN_STEPS);
            while (budget-- > 0 && !limited) {
                if (work >= 32768 || pending.size() >= 8192) { limited = true; return; }
                if (active == null) {
                    if (pending.isEmpty()) return;
                    active = pending.poll();
                    long fp = PotionContentsService.fingerprint(active.state());
                    int visits = expanded.merge(fp, 1, Integer::sum);
                    if (visits > 4) { omitted++; active = null; work++; continue; }
                    activeSegments = byStart.getOrDefault(fp, List.of());
                    segmentCursor = 0;
                    genericCursor = 0;
                }
                work++;
                Node node = active;
                Chained next = inspectNextCandidate();
                if (segmentCursor >= activeSegments.size() && genericCursor >= generic.size()) active = null;
                if (next == null) continue;
                int steps = node.steps() + next.steps().size();
                if (steps > maxSteps || node.chain().size() >= PlanKey.MAX_PARTS) continue;
                Set<Long> seen = new HashSet<>(node.seen());
                boolean cycle = false;
                for (BrewPlan.Step step : next.steps()) if (!seen.add(PotionContentsService.fingerprint(step.after()))) { cycle = true; break; }
                if (cycle) continue;
                List<Chained> chain = new ArrayList<>(node.chain());
                chain.add(next);
                BrewPlan plan = assemble(input, chain, false);
                if (plan == null || !dedupe.add(dedupeKey(plan))) continue;
                results.add(plan);
                if (steps < maxSteps) pending.add(new Node(plan.output(), chain, steps, seen));
            }
        }
        @Nullable
        private Chained inspectNextCandidate() {
            if (segmentCursor < activeSegments.size()) {
                Segment segment = activeSegments.get(segmentCursor++);
                return PotionContentsService.sameCanonical(active.state(), segment.start()) ? chain(segment) : null;
            }
            if (genericCursor < generic.size()) {
                InfusionRecipe recipe = generic.get(genericCursor++);
                List<BrewPlan.Step> steps = applyGeneric(recipe, active.state(), null);
                return steps == null ? null : new Chained(authoredPart(recipe.getId(), 0, recipe.steps().size()), steps);
            }
            return null;
        }
        public List<BrewPlan> plans() { return Collections.unmodifiableList(results); }
        public boolean done() { return limited || active == null && pending.isEmpty(); }
        public boolean budgetLimited() { return limited; }
        public int omittedAlternatives() { return omitted; }
        public int work() { return work; }
        public long destinations() { return results.stream().map(p -> PotionContentsService.fingerprint(p.output())).distinct().count(); }
    }

    public Catalog catalog(ItemStack input) {
        ItemStack canonical = PotionContentsService.canonical(input).copyWithCount(1);
        return catalogs.computeIfAbsent(PotionContentsService.fingerprint(canonical), k -> new Catalog(canonical));
    }

    public int generation() { return generation; }
    public List<String> catalogDiagnostics() {
        List<String> lines=new ArrayList<>();
        for(var entry:catalogs.entrySet()) {
            Catalog c=entry.getValue();
            lines.add("Catalog "+entry.getKey()+": "+c.destinations()+" supported destinations, "+c.plans().size()
                    +" alternative routes, "+c.omittedAlternatives()+" omitted alternatives; "+c.work()+" candidate inspections; "
                    +(c.budgetLimited()?"budget limited":c.done()?"destination traversal finished":"search incomplete"));
        }
        if(lines.isEmpty())lines.add("Catalog: no destination queries yet; contextual handlers are not assumed enumerable");
        return lines;
    }

    /** Resolve the start of an authored segment without guessing from a truncated list. */
    public ItemStack inputForKey(PlanKey key,ItemStack fallback) {
        PlanKey.Part part=key.parts().get(0);
        Validated v=validated.get(part.source());
        if(v!=null&&part.from()>=0&&part.from()<v.steps().size()) return v.steps().get(part.from()).before().copy();
        return fallback.copyWithCount(1);
    }
    private final Map<Long, List<BrewPlan>> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<BrewPlan>> eldest) {
            return size() > CACHE;
        }
    });
    private final Map<String, List<BrewPlan>> usingCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<BrewPlan>> eldest) {
            return size() > CACHE;
        }
    });

    private PlanIndex(RecipeManager recipes, BrewingDefinitions defs, int configKey) {
        this.recipes = recipes;
        this.defs = defs;
        this.dataGeneration = defs.generation();
        this.generation = EPOCHS.incrementAndGet();
        this.configKey = configKey;
    }

    private static int configKey() {
        return ServerConfig.servingsPerVanillaReagent() * 31 + ServerConfig.get(ServerConfig.MAX_PLAN_STEPS) * 7
                + (ServerConfig.get(ServerConfig.REPLACE_MODIFIERS) ? 1 : 0) + ServerConfig.maxBatchServings() * 131;
    }

    /** The current server index, rebuilt when the recipes, the brewing data or the relevant config changed. */
    public static PlanIndex get(RecipeManager recipes) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        int key = configKey();
        PlanIndex current = server;
        if (current != null && current.recipes == recipes && current.dataGeneration == defs.generation() && current.configKey == key) {
            return current;
        }
        synchronized (PlanIndex.class) {
            current = server;
            if (current != null && current.recipes == recipes && current.dataGeneration == defs.generation() && current.configKey == key) {
                return current;
            }
            PlanIndex index = new PlanIndex(recipes, defs, key);
            index.build();
            server = index;
            CoverageIndex.activate(index.coverage());
            return index;
        }
    }

    /**
     * A one-off diagnostic index against client recipes. JEI and the Notebook consume synchronized server statuses. Never becomes the server
     * index and never feeds replacement-mode coverage.
     */
    public static PlanIndex forClient(RecipeManager recipes, BrewingDefinitions defs) {
        PlanIndex index = new PlanIndex(recipes, defs, configKey());
        index.build();
        return index;
    }

    /** Validation status of one recipe in this index, or null when it is not an infusion recipe here. */
    @Nullable
    public Entry entry(ResourceLocation recipe) {
        for (Entry e : report) if (e.recipe().equals(recipe)) return e;
        return null;
    }

    /** The last built index, if any (diagnostics only). */
    @Nullable
    public static PlanIndex current() {
        return server;
    }

    public static void invalidate() {
        server = null;
        CoverageIndex.activate(new CoverageIndex(Set.of(), Set.of()));
    }

    public BrewingDefinitions definitions() {
        return defs;
    }

    public List<Entry> report() {
        return Collections.unmodifiableList(report);
    }

    public int validatedCount() {
        return validated.size();
    }

    public boolean isValidated(ResourceLocation recipe) {
        return validated.containsKey(recipe) || genericById.containsKey(recipe);
    }

    // ---- build ----

    private void build() {
        long start = System.nanoTime();
        List<InfusionRecipe> all = new ArrayList<>(recipes.getAllRecipesFor(ModRecipeTypes.INFUSION.get()));
        all.sort(Comparator.comparing(r -> r.getId().toString()));
        for (InfusionRecipe recipe : all) {
            try {
                validate(recipe);
            } catch (RuntimeException e) {
                report.add(new Entry(recipe.getId(), Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.error|" + e, recipe.warnings()));
                ImmersiveBrewing.LOGGER.error("Immersive Brewing: validating {} failed", recipe.getId(), e);
            }
        }
        for (Validated v : validated.values()) {
            for (int i = 0; i < v.steps.size(); i++) {
                byStart.computeIfAbsent(PotionContentsService.fingerprint(v.steps.get(i).before()), k -> new ArrayList<>()).add(new Segment(v, i));
            }
        }
        if (ServerConfig.debug()) {
            ImmersiveBrewing.LOGGER.info("Immersive Brewing: plan index built in {} ms: {} validated, {} generic, {} reported",
                    (System.nanoTime() - start) / 1_000_000, validated.size(), generic.size(), report.size());
        }
    }

    private void validate(InfusionRecipe recipe) {
        ResourceLocation id = recipe.getId();
        if (defs.exclusions().excludesRecipe(id)) {
            report.add(new Entry(id, Status.EXCLUDED, "plan.immersive_brewing.excluded.recipe", recipe.warnings()));
            return;
        }
        BrewingAdapter adapter = AdapterRegistry.get(recipe.adapter(), defs);
        if (adapter == null) {
            String reason = defs.exclusions().excludesAdapter(recipe.adapter()) ? "plan.immersive_brewing.excluded.adapter" : "plan.immersive_brewing.unsupported.unknown_adapter";
            report.add(new Entry(id, defs.exclusions().excludesAdapter(recipe.adapter()) ? Status.EXCLUDED : Status.UNSUPPORTED,
                    reason + "|" + recipe.adapter(), recipe.warnings()));
            return;
        }
        if (defs.exclusions().excludesItem(recipe.input().item()) || defs.exclusions().excludesItem(recipe.expectedOutput().item())) {
            report.add(new Entry(id, Status.EXCLUDED, "plan.immersive_brewing.excluded.item", recipe.warnings()));
            return;
        }
        if (recipe.steps().size() > ServerConfig.get(ServerConfig.MAX_PLAN_STEPS)) {
            report.add(new Entry(id, Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.too_long", recipe.warnings()));
            return;
        }
        for(PlanStep step:recipe.steps()) if(step.reagent().getItems().length>16) {
            report.add(new Entry(id,Status.UNSUPPORTED,"plan.immersive_brewing.unsupported.expansion",List.of("data/"+id.getNamespace()+"/recipes/"+id.getPath()+".json: steps[].reagent expands beyond 16 items")));
            return;
        }
        if (recipe.generic()) {
            for (PlanStep step : recipe.steps()) {
                if (reagents(step.reagent()).isEmpty()) {
                    report.add(noReagent(recipe, step, ""));
                    return;
                }
            }
            generic.add(recipe);
            genericById.put(id, recipe);
            report.add(new Entry(id, Status.SUPPORTED, "plan.immersive_brewing.supported.generic", recipe.warnings()));
            return;
        }
        ItemStack state = recipe.input().stack(null);
        List<ValidStep> steps = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        seen.add(PotionContentsService.fingerprint(state));
        for (int i = 0; i < recipe.steps().size(); i++) {
            PlanStep step = recipe.steps().get(i);
            List<Item> candidates = reagents(step.reagent());
            if (candidates.isEmpty()) {
                report.add(noReagent(recipe, step, "|" + i));
                return;
            }
            ItemStack output = null;
            for (Item item : candidates) {
                BrewingAdapter.Preview preview = adapter.preview(state, new ItemStack(item));
                if (!preview.supported()) {
                    report.add(new Entry(id, Status.UNSUPPORTED, preview.unsupportedReason() + "|" + i + "|" + ForgeRegistries.ITEMS.getKey(item), recipe.warnings()));
                    return;
                }
                if (output == null) output = preview.output();
                else if (!PotionContentsService.sameCanonical(output, preview.output())) {
                    report.add(new Entry(id, Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.ambiguous|" + i, recipe.warnings()));
                    return;
                }
            }
            if (!seen.add(PotionContentsService.fingerprint(output))) {
                report.add(new Entry(id, Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.cycle|" + i, recipe.warnings()));
                return;
            }
            Item reagent = candidates.get(0);
            BrewingAdapter.BatchPolicy policy = effectivePolicy(adapter.batchPolicy(state, new ItemStack(reagent)), recipe);
            steps.add(new ValidStep(step, reagent, adapter, policy, state.copy(), output.copy()));
            state = output;
        }
        if (!recipe.expectedOutput().accepts(state, recipe.input().stack(null))) {
            report.add(new Entry(id, Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.result_differs|" + describe(state), recipe.warnings()));
            return;
        }
        validated.put(id, new Validated(recipe, steps));
        boolean effects = adapter.contentsPolicy(state) == BrewingAdapter.ContentsPolicy.EFFECTS;
        List<String> warnings = new ArrayList<>(recipe.warnings());
        if (recipe.servingsPerReagent() > ServerConfig.servingsPerVanillaReagent() && adapter.id().equals(com.otectus.immersivebrewing.recipe.adapter.ForgeRegistryAdapter.ID)) {
            warnings.add("batch.servings_per_reagent " + recipe.servingsPerReagent() + " exceeds the adapter's " + ServerConfig.servingsPerVanillaReagent() + "; the adapter's value applies");
        }
        report.add(new Entry(id, effects ? Status.SUPPORTED : Status.METADATA_ONLY, effects ? "plan.immersive_brewing.supported" : "plan.immersive_brewing.metadata_only", warnings));
    }

    /** A recipe may declare fewer servings per reagent than its adapter (costlier), never more. */
    private static BrewingAdapter.BatchPolicy effectivePolicy(BrewingAdapter.BatchPolicy adapter, InfusionRecipe recipe) {
        int per = Math.min(adapter.servingsPerReagent(), recipe.servingsPerReagent());
        int max = Math.min(Math.min(adapter.maxServings(), recipe.maxServings()), ServerConfig.maxBatchServings());
        return new BrewingAdapter.BatchPolicy(per, Math.max(1, max), adapter.reagentsPerApplication(), adapter.sharedReaction());
    }

    /** A step with no usable reagent: excluded when its ingredient matches only excluded items, else unsupported. */
    private Entry noReagent(InfusionRecipe recipe, PlanStep step, String suffix) {
        boolean matchesSomething = false;
        for (ItemStack stack : step.reagent().getItems()) {
            if (!stack.isEmpty()) {
                matchesSomething = true;
                break;
            }
        }
        return matchesSomething
                ? new Entry(recipe.getId(), Status.EXCLUDED, "plan.immersive_brewing.excluded.item", recipe.warnings())
                : new Entry(recipe.getId(), Status.UNSUPPORTED, "plan.immersive_brewing.unsupported.no_reagent" + suffix, recipe.warnings());
    }

    private List<Item> reagents(Ingredient ingredient) {
        Set<Item> items = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty() || defs.exclusions().excludesItem(stack.getItem())) continue;
            items.add(stack.getItem());
            if (items.size() >= 16) break;
        }
        return new ArrayList<>(items);
    }

    private static String describe(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) + (stack.getTag() != null ? stack.getTag().toString() : "");
    }

    // ---- coverage for replacement mode ----

    private CoverageIndex coverage() {
        Set<CoverageIndex.Pair> covered = new HashSet<>();
        Set<CoverageIndex.Pair> modifiers = new HashSet<>();
        for (Validated v : validated.values()) {
            for (ValidStep s : v.steps) {
                CoverageIndex.Pair pair = new CoverageIndex.Pair(PotionContentsService.fingerprint(s.before()), s.reagent());
                boolean modifier = s.spec().role() == KettleSetup.Role.MODIFIER || s.spec().role() == KettleSetup.Role.CONTAINER;
                (modifier ? modifiers : covered).add(pair);
            }
        }
        return new CoverageIndex(covered, modifiers);
    }

    // ---- plan search ----

    /**
     * Every catalog plan from this canonical input, shortest first, deduplicated by (result, reagents). Cached per
     * input identity.
     */
    public List<BrewPlan> plansFrom(ItemStack input) {
        ItemStack canonical = PotionContentsService.canonical(input).copyWithCount(1);
        if (defs.exclusions().excludesItem(canonical.getItem())) return List.of();
        long fp = PotionContentsService.fingerprint(canonical);
        List<BrewPlan> cached = cache.get(fp);
        if (cached != null) return cached;
        List<BrewPlan> plans = search(canonical, null);
        cache.put(fp, plans);
        return plans;
    }

    /**
     * Every catalog plan from this input whose reagents are all among {@code reagents} (what a Kettle tray holds), shortest
     * first. The catalog search stops at {@link #MAX_PLANS}, which a large modded registry reaches from a hub such as
     * Water or Mundane (each potion made from Mundane multiplies by every Mundane reagent and container); this search
     * follows only steps the tray can pay for (same bound), so a full catalog does not hide a brew whose reagents are
     * loaded. Cached per input and reagent set.
     */
    public List<BrewPlan> plansUsing(ItemStack input, Collection<Item> reagents) {
        ItemStack canonical = PotionContentsService.canonical(input).copyWithCount(1);
        if (reagents.isEmpty() || defs.exclusions().excludesItem(canonical.getItem())) return List.of();
        Set<String> ids = new TreeSet<>();
        for (Item item : reagents) ids.add(String.valueOf(ForgeRegistries.ITEMS.getKey(item)));
        String key = PotionContentsService.fingerprint(canonical) + "|" + String.join(",", ids);
        List<BrewPlan> cached = usingCache.get(key);
        if (cached != null) return cached;
        List<BrewPlan> plans = search(canonical, Set.copyOf(reagents));
        usingCache.put(key, plans);
        return plans;
    }

    private record Node(ItemStack state, List<Chained> chain, int steps, Set<Long> seen) {}

    private record Chained(PlanKey.Part part, List<BrewPlan.Step> steps) {}

    /** Breadth-first plan search; with {@code only}, every step's reagent must be one of those items. */
    private List<BrewPlan> search(ItemStack input, @Nullable Set<Item> only) {
        int maxSteps = ServerConfig.get(ServerConfig.MAX_PLAN_STEPS);
        List<BrewPlan> out = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(input, List.of(), 0, Set.of(PotionContentsService.fingerprint(input))));
        while (!queue.isEmpty() && out.size() < MAX_PLANS) {
            Node node = queue.poll();
            for (Chained next : extensions(node.state, only)) {
                int steps = node.steps + next.steps.size();
                if (steps > maxSteps) continue;
                Set<Long> seen = new HashSet<>(node.seen);
                boolean cycle = false;
                for (BrewPlan.Step s : next.steps) {
                    if (!seen.add(PotionContentsService.fingerprint(s.after()))) {
                        cycle = true;
                        break;
                    }
                }
                if (cycle) continue;
                List<Chained> chain = new ArrayList<>(node.chain);
                chain.add(next);
                BrewPlan plan = assemble(input, chain, false);
                if (plan == null) continue;
                if (dedupe.add(dedupeKey(plan))) {
                    out.add(plan);
                    if (out.size() >= MAX_PLANS) break;
                }
                queue.add(new Node(plan.output(), chain, steps, seen));
            }
        }
        return List.copyOf(out);
    }

    private static String dedupeKey(BrewPlan plan) {
        List<String> reagents = new ArrayList<>();
        for (BrewPlan.Step s : plan.steps()) reagents.add(String.valueOf(ForgeRegistries.ITEMS.getKey(s.reagent())));
        Collections.sort(reagents);
        return PotionContentsService.fingerprint(plan.output()) + "|" + String.join(",", reagents);
    }

    /** Segments and generic conversions that can follow {@code state}, with {@code only} limiting their reagents. */
    private List<Chained> extensions(ItemStack state, @Nullable Set<Item> only) {
        List<Chained> out = new ArrayList<>();
        segments:
        for (Segment seg : byStart.getOrDefault(PotionContentsService.fingerprint(state), List.of())) {
            if (only != null) {
                for (int i = seg.from; i < seg.recipe.steps.size(); i++) if (!only.contains(seg.recipe.steps.get(i).reagent())) continue segments;
            }
            out.add(chain(seg));
        }
        for (InfusionRecipe recipe : generic) {
            List<BrewPlan.Step> steps = applyGeneric(recipe, state, only);
            if (steps != null) out.add(new Chained(authoredPart(recipe.getId(), 0, recipe.steps().size()), steps));
        }
        return out;
    }

    private Chained chain(Segment segment) {
        List<BrewPlan.Step> steps = new ArrayList<>();
        for (int i = segment.from; i < segment.recipe.steps.size(); i++) steps.add(toPlanStep(segment.recipe, i));
        ResourceLocation id = segment.recipe.recipe.getId();
        return new Chained(authoredPart(id, segment.from, segment.recipe.steps.size()), steps);
    }

    private static PlanKey.Part authoredPart(ResourceLocation id, int from, int to) {
        return new PlanKey.Part(id, from, to, id);
    }

    private BrewPlan.Step toPlanStep(Validated v, int i) {
        ValidStep s = v.steps.get(i);
        return new BrewPlan.Step(v.recipe.getId(), s.spec(), s.reagent(), s.adapter().id(), s.adapter().version(), s.batch(), s.before(), s.after());
    }

    /** Replays a generic (container) recipe from a concrete state, or null when it does not apply (or needs a reagent not in {@code only}). */
    @Nullable
    private List<BrewPlan.Step> applyGeneric(InfusionRecipe recipe, ItemStack state, @Nullable Set<Item> only) {
        if (!recipe.input().matches(state)) return null;
        BrewingAdapter adapter = AdapterRegistry.get(recipe.adapter(), defs);
        if (adapter == null) return null;
        ItemStack current = state;
        List<BrewPlan.Step> steps = new ArrayList<>();
        for (PlanStep step : recipe.steps()) {
            List<Item> candidates = reagents(step.reagent());
            if (candidates.isEmpty()) return null;
            Item reagent = candidates.get(0);
            if (only != null && !only.contains(reagent)) return null;
            BrewingAdapter.Preview preview = adapter.preview(current, new ItemStack(reagent));
            if (!preview.supported()) return null;
            ItemStack after=step.role()==KettleSetup.Role.CONTAINER
                    ? QuickFinishing.preserve(current,new ItemStack(reagent),preview.output()) : preview.output();
            BrewingAdapter.BatchPolicy policy = effectivePolicy(adapter.batchPolicy(current, new ItemStack(reagent)), recipe);
            steps.add(new BrewPlan.Step(recipe.getId(), step, reagent, adapter.id(), adapter.version(), policy, current.copy(), after.copy()));
            current = after;
        }
        if (!recipe.expectedOutput().accepts(current, state)) return null;
        if (defs.exclusions().excludesItem(current.getItem())) return null;
        return steps;
    }

    @Nullable
    private BrewPlan assemble(ItemStack input, List<Chained> chain, boolean contextual) {
        List<BrewPlan.Step> steps = new ArrayList<>();
        List<PlanKey.Part> parts = new ArrayList<>();
        int maxServings = 6;
        for (Chained c : chain) {
            steps.addAll(c.steps);
            parts.add(c.part);
        }
        if (steps.isEmpty()) return null;
        for (BrewPlan.Step s : steps) maxServings = Math.min(maxServings, s.batch().maxServings());
        ItemStack output = steps.get(steps.size() - 1).after();
        return new BrewPlan(new PlanKey(parts), input.copy(), steps, output.copy(), brewProfile(steps), maxServings, contextual);
    }

    /**
     * The reaction a plan presents: the explicit profile of the recipe owning the last effect-creating step, else that
     * step's reagent profile's reaction, else the first recipe's explicit profile, else the generic profile.
     */
    private ResourceLocation brewProfile(List<BrewPlan.Step> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            BrewPlan.Step s = steps.get(i);
            if (!s.spec().createsEffect()) continue;
            ResourceLocation explicit = explicitProfile(s.source());
            if (explicit != null && defs.hasBrewProfile(explicit)) return explicit;
            ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(s.reagent(), s.source(), s.spec().preparationProfile());
            if (choice != null && choice.value().reaction().profile() != null) return choice.value().reaction().profile();
            return BrewingDefinitions.GENERIC_PROFILE;
        }
        ResourceLocation first = explicitProfile(steps.get(0).source());
        return first != null && defs.hasBrewProfile(first) ? first : BrewingDefinitions.GENERIC_PROFILE;
    }

    @Nullable
    private ResourceLocation explicitProfile(ResourceLocation recipeId) {
        Validated v = validated.get(recipeId);
        if (v != null) return v.recipe.brewProfile();
        InfusionRecipe g = genericById.get(recipeId);
        return g == null ? null : g.brewProfile();
    }

    /**
     * Contextual (opaque) plans: single adapter queries with what is actually in the tray, for transformations no catalog
     * plan offers from this input (spec 15.3). Bounded by the tray size.
     */
    public List<BrewPlan> contextualPlans(ItemStack input, List<ItemStack> trayItems, List<BrewPlan> catalog) {
        ItemStack canonical = PotionContentsService.canonical(input).copyWithCount(1);
        if (defs.exclusions().excludesItem(canonical.getItem())) return List.of();
        Set<Item> offered = new HashSet<>();
        for (BrewPlan p : catalog) if (p.steps().size() == 1) offered.add(p.steps().get(0).reagent());
        List<BrewPlan> out = new ArrayList<>();
        Set<Item> tried = new HashSet<>();
        for (ItemStack tray : trayItems) {
            if (tray.isEmpty() || !tried.add(tray.getItem()) || offered.contains(tray.getItem())) continue;
            BrewPlan plan = contextual(canonical, tray);
            if (plan != null) out.add(plan);
        }
        return out;
    }

    /** One adapter query with a tray item; null when nothing applies or the item is excluded (listing and resolving alike). */
    @Nullable
    private BrewPlan contextual(ItemStack input, ItemStack reagentStack) {
        if (defs.exclusions().excludesItem(reagentStack)) return null;
        ItemStack reagent = reagentStack.copyWithCount(1);
        for (BrewingAdapter adapter : AdapterRegistry.ordered(defs)) {
            if (!adapter.applies(input, reagent)) continue;
            BrewingAdapter.Preview preview = adapter.preview(input, reagent);
            if (!preview.supported() || defs.exclusions().excludesItem(preview.output().getItem())) continue;
            PlanStep spec = new PlanStep(Ingredient.of(reagent.getItem()), KettlePhase.INFUSE, KettleSetup.Role.EFFECT, null);
            BrewingAdapter.BatchPolicy policy = adapter.batchPolicy(input, reagent);
            policy = new BrewingAdapter.BatchPolicy(policy.servingsPerReagent(), Math.min(policy.maxServings(), ServerConfig.maxBatchServings()),
                    policy.reagentsPerApplication(), policy.sharedReaction());
            BrewPlan.Step step = new BrewPlan.Step(adapter.id(), spec, reagent.getItem(), adapter.id(), adapter.version(), policy, input.copy(),
                    preview.output().copy());
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(reagent.getItem());
            PlanKey key = new PlanKey(List.of(new PlanKey.Part(adapter.id(), -1, -1, itemId)));
            ResourceLocation profile = BrewingDefinitions.GENERIC_PROFILE;
            ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(reagent.getItem(), null, null);
            if (choice != null && choice.value().reaction().profile() != null) profile = choice.value().reaction().profile();
            return new BrewPlan(key, input.copy(), List.of(step), preview.output().copy(), profile, policy.maxServings(), true);
        }
        return null;
    }

    /** Resolves exactly one authored key part without enumerating unrelated recipes. */
    @Nullable
    private Chained resolveAuthoredPart(PlanKey.Part part, ItemStack state) {
        if (!part.equals(authoredPart(part.source(), part.from(), part.to()))) return null;
        Validated concrete = validated.get(part.source());
        if (concrete != null) {
            if (part.from() < 0 || part.from() >= concrete.steps().size() || part.to() != concrete.steps().size()) return null;
            if (!PotionContentsService.sameCanonical(state, concrete.steps().get(part.from()).before())) return null;
            return chain(new Segment(concrete, part.from()));
        }
        InfusionRecipe recipe = genericById.get(part.source());
        if (recipe == null || part.from() != 0 || part.to() != recipe.steps().size()) return null;
        List<BrewPlan.Step> steps = applyGeneric(recipe, state, null);
        return steps == null ? null : new Chained(part, steps);
    }

    /**
     * Re-resolves a client's key against the actual input: the same plan if it is still valid, otherwise null. The tray
     * decides contextual keys and the paths {@link #plansUsing} adds beyond the bounded catalog.
     */
    @Nullable
    public BrewPlan resolve(PlanKey key, ItemStack input, List<ItemStack> trayItems) {
        ItemStack canonical = PotionContentsService.canonical(input).copyWithCount(1);
        if (key.parts().size() == 1 && key.parts().get(0).contextual()) {
            PlanKey.Part part = key.parts().get(0);
            Item item = ForgeRegistries.ITEMS.getValue(part.item());
            if (item == null) return null;
            for (ItemStack tray : trayItems) {
                if (tray.is(item)) {
                    BrewPlan plan = contextual(canonical, tray);
                    return plan != null && plan.key().equals(key) ? plan : null;
                }
            }
            return null;
        }
        // Replay the referenced segments directly. A page limit or unloaded tray is never recipe authority.
        if (defs.exclusions().excludesItem(canonical.getItem())) return null;
        List<Chained> chain = new ArrayList<>();
        ItemStack state = canonical;
        Set<Long> seen = new HashSet<>();
        seen.add(PotionContentsService.fingerprint(state));
        int count = 0;
        for (PlanKey.Part part : key.parts()) {
            if (part.contextual()) return null;
            Chained found = resolveAuthoredPart(part, state);
            if (found == null || (count += found.steps().size()) > ServerConfig.get(ServerConfig.MAX_PLAN_STEPS)) return null;
            for (BrewPlan.Step step : found.steps()) if (!seen.add(PotionContentsService.fingerprint(step.after()))) return null;
            chain.add(found);
            state = found.steps().get(found.steps().size() - 1).after();
        }
        return assemble(canonical, chain, false);
    }
}
