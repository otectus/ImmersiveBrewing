package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.api.BrewingAdapter;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDataLoader;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.event.CommonEvents;
import com.otectus.immersivebrewing.integration.PotionMods;
import com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The compatibility and coverage report (spec 25.3): every infusion recipe as supported, metadata-only, excluded or
 * unsupported with its reason; every discovered transformation no plan covers as opaque/query-only; adapters with their
 * versions; the active data generation and its problems; and the exact mod versions loaded. Bounded in size; contains
 * no labels and no item NBT beyond potion ids.
 */
public final class CoverageReport {
    public static final int MAX_LINES = 4000;

    public static List<String> build(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        PlanIndex plans = PlanIndex.get(server.getRecipeManager());
        BrewingDefinitions defs = BrewingDefinitions.server();
        out.add("Immersive Brewing coverage report");
        out.add("Generated " + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        out.add("Mode: " + ServerConfig.brewingMode() + (ServerConfig.get(ServerConfig.REPLACE_MODIFIERS) ? " (modifiers too)" : ""));
        out.add("Tipped-arrow override active: " + CommonEvents.tippedArrowOverrideActive());
        out.add("");
        out.add("Loaded mods:");
        ModList.get().getMods().stream().sorted(java.util.Comparator.comparing(m -> m.getModId()))
                .forEach(m -> out.add("  " + m.getModId() + " " + m.getVersion()));
        out.add("");
        out.add("Adapters:");
        for (BrewingAdapter a : AdapterRegistry.all()) {
            out.add("  " + a.id() + " v" + a.version() + " priority " + a.priority() + (defs.exclusions().excludesAdapter(a.id()) ? " (excluded)" : ""));
        }
        out.add("");
        BrewingDataLoader.Report data = BrewingDataLoader.lastReport();
        out.add("Brewing data generation " + defs.generation() + ": " + defs.reagentProfiles().size() + " reagent profiles, "
                + defs.brewProfiles().size() + " brew profiles, " + defs.effectPolicies().size() + " effect policies"
                + (data.keptPrevious() ? " (the last reload was invalid; previous generation kept)" : ""));
        data.errors().forEach(e -> out.add("  error: " + e));
        data.warnings().forEach(w -> out.add("  warning: " + w));
        out.add("");
        Map<PlanIndex.Status, Integer> counts = new EnumMap<>(PlanIndex.Status.class);
        for (PlanIndex.Entry e : plans.report()) counts.merge(e.status(), 1, Integer::sum);
        out.add("Infusion recipes: " + counts);
        for (PlanIndex.Entry e : plans.report()) {
            if (e.status() == PlanIndex.Status.SUPPORTED && e.warnings().isEmpty()) continue;
            out.add("  " + e.status().name().toLowerCase(java.util.Locale.ROOT) + " " + e.recipe() + ": " + e.reason().replace('|', ' ')
                    + (e.warnings().isEmpty() ? "" : " " + e.warnings()));
            if (out.size() > MAX_LINES) break;
        }
        out.add("");
        out.add("Optional potion mods (data under compat/, loaded only with its mod):");
        for (String mod : PotionMods.IDS) {
            var container = ModList.get().getModContainerById(mod);
            if (container.isEmpty()) {
                out.add("  " + mod + ": not installed");
                continue;
            }
            String prefix = PotionMods.prefix(mod);
            Map<PlanIndex.Status, Integer> own = new EnumMap<>(PlanIndex.Status.class);
            for (PlanIndex.Entry e : plans.report()) {
                if (e.recipe().getNamespace().equals(ImmersiveBrewing.MOD_ID) && e.recipe().getPath().startsWith(prefix)) own.merge(e.status(), 1, Integer::sum);
            }
            long profiles = defs.reagentProfiles().stream().filter(p -> p.id().getPath().startsWith(prefix)).count();
            boolean policy = defs.effectPolicies().stream().anyMatch(p -> p.id().getPath().equals("compat/" + mod));
            out.add("  " + mod + " " + container.get().getModInfo().getVersion() + ": plans " + own + ", " + profiles + " reagent profiles, effect policy "
                    + (policy ? "active" : "missing"));
        }
        DiscoveryIndex discovery = DiscoveryIndex.get(server.getRecipeManager());
        out.add("");
        out.add("Discovery: " + discovery.queries() + " queries" + (discovery.truncated() ? " (budget reached)" : "") + ", "
                + discovery.found().size() + " transformations, " + discovery.uncovered() + " not covered by a plan, "
                + discovery.excluded() + " excluded by data, " + discovery.loose() + " loose (not offered)");
        for (DiscoveryIndex.Found f : discovery.found()) {
            if (f.covered() || f.excluded() || f.loose()) continue;
            out.add("  opaque/query-only: " + describe(f.input()) + " + " + ForgeRegistries.ITEMS.getKey(f.ingredient()) + " -> " + describe(f.output())
                    + (f.finite() ? " (finite Forge recipe)" : ""));
            if (out.size() > MAX_LINES) {
                out.add("  ... truncated");
                break;
            }
        }
        out.add("");
        out.add("Replacement-mode coverage pairs: " + CoverageIndex.server().size());
        return out;
    }

    static String describe(ItemStack stack) {
        String item = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        String potion = String.valueOf(ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(stack)));
        return item + "[" + potion + "]";
    }

    private CoverageReport() {}
}
