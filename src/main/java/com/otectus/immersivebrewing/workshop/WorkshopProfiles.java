package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.data.BrewProfile;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The active workshop profiles (server side, activated atomically with the rest of the brewing data) and the
 * deterministic translator that builds a physical process from the existing reagent and brew profiles when no workshop
 * profile refines them (handoff 13: an unsupported presentation profile is never an unsupported recipe).
 *
 * <p>Precedence: an explicit {@code source_profile}/{@code target} match first, then selector layers as for reagent
 * profiles, then priority, then the lowest id. A resolution always names where it came from, for the report command.
 */
public final class WorkshopProfiles {
    /** A resolved physical description and its provenance (a profile id, or a translated legacy profile). */
    public record Resolved<T>(T value, String source, boolean fallback) {}

    private static volatile List<WorkshopProfile> active = List.of();
    private static volatile int generation;

    public static void activate(int gen, List<WorkshopProfile> profiles) {
        List<WorkshopProfile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparing((WorkshopProfile p) -> p.id().toString()));
        active = List.copyOf(sorted);
        generation = gen;
    }

    public static void reset() {
        active = List.of();
        generation = 0;
    }

    public static List<WorkshopProfile> all() {
        return active;
    }

    public static int generation() {
        return generation;
    }

    // ---- preparation ----

    /**
     * How a reagent is prepared by hand, or null when it is not preparable (it then brews at the raw extraction
     * baseline: unknown materials are never ground just because a recipe names them).
     */
    @Nullable
    public static Resolved<WorkshopProfile.Preparation> preparation(Item item, @Nullable ReagentProfile reagent) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
        WorkshopProfile best = null;
        for (WorkshopProfile p : active) {
            if (p.kind() != WorkshopProfile.Kind.PREPARATION || p.preparation() == null) continue;
            if (reagent != null && reagent.id().equals(p.sourceProfile())) {
                if (best == null || best.sourceProfile() == null || p.priority() > best.priority()) best = p;
            }
        }
        if (best == null && itemId != null) {
            List<WorkshopProfile> candidates = new ArrayList<>();
            for (WorkshopProfile p : active) if (p.kind() == WorkshopProfile.Kind.PREPARATION && p.preparation() != null && !p.selector().isEmpty()) candidates.add(p);
            ProfileSelector.Choice<WorkshopProfile> choice = ProfileSelector.choose(candidates, WorkshopProfile::id, WorkshopProfile::selector,
                    WorkshopProfile::priority, itemId, tag -> item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tag)), null);
            if (choice != null) best = choice.value();
        }
        if (best != null) return new Resolved<>(best.preparation(), best.id().toString(), false);
        if (reagent == null || reagent.preparation() == null) return null;
        return new Resolved<>(translate(reagent.preparation()), "translated:" + reagent.id(), true);
    }

    /** The fallback for a reagent profile without a workshop profile: the same family, endpoint and overwork start. */
    public static WorkshopProfile.Preparation translate(ReagentProfile.Preparation old) {
        double required = Math.max(0.25, Math.min(4.0, old.durationTicks() / 240.0));
        double usefulHigh = Math.max(0.35, Math.min(1.0, old.pressureLimit()));
        double usefulLow = Math.max(0.05, Math.min(usefulHigh - 0.1, usefulHigh * 0.4));
        return new WorkshopProfile.Preparation(old.mode(), List.of("press", "grind", "fold"), required, 0.05, usefulLow, usefulHigh,
                old.endpointLow(), old.endpointHigh(), Math.max(old.endpointHigh() + 0.01, old.overworkThreshold()));
    }

    /** The mortar setup for a batch of {@code units} of a reagent. */
    public static MortarProcess.Setup mortarSetup(Resolved<WorkshopProfile.Preparation> prep, int units, int dataGeneration) {
        WorkshopProfile.Preparation p = prep.value();
        return new MortarProcess.Setup(p.material(), units, p.endpointLow(), p.endpointHigh(), p.overworkStarts(), p.usefulLow(), p.usefulHigh(),
                1.0 / p.requiredWork(), p.maxWorkPerTick(), MortarProcess.operations(p.operations()), prep.source(), dataGeneration);
    }

    // ---- brewing ----

    public static Resolved<WorkshopProfile.Brewing> brewing(ResourceLocation brewProfile, BrewingDefinitions defs) {
        WorkshopProfile best = null;
        for (WorkshopProfile p : active) {
            if (p.kind() != WorkshopProfile.Kind.BREWING || p.brewing() == null || !brewProfile.equals(p.target())) continue;
            if (best == null || p.priority() > best.priority()) best = p;
        }
        if (best != null) return new Resolved<>(best.brewing(), best.id().toString(), false);
        BrewProfile old = defs.brewProfile(brewProfile);
        return new Resolved<>(translate(old), "translated:" + old.id(), true);
    }

    /** The fallback for a brew profile: the infusion's signature as the family and its heat band as the ideal band. */
    public static WorkshopProfile.Brewing translate(BrewProfile old) {
        ReactionFamily family = ReactionFamily.of(old.infuse().signature());
        double low = Math.max(0.2, Math.min(0.85, old.infuse().heatLow()));
        double high = Math.max(low + 0.06, Math.min(0.92, old.infuse().heatHigh()));
        return new WorkshopProfile.Brewing(family, low, high, Math.max(0.2, low - 0.12), Math.min(0.95, high + 0.14), old.inertia(), 9.0,
                family == ReactionFamily.DENSE_SEDIMENT ? 4.0 : 3.0);
    }

    // ---- distillation ----

    public static Resolved<WorkshopProfile.Distillation> distillation(ResourceLocation recipe) {
        WorkshopProfile best = null;
        for (WorkshopProfile p : active) {
            if (p.kind() != WorkshopProfile.Kind.DISTILLATION || p.distillation() == null || !recipe.equals(p.target())) continue;
            if (best == null || p.priority() > best.priority()) best = p;
        }
        if (best != null) return new Resolved<>(best.distillation(), best.id().toString(), false);
        return new Resolved<>(new WorkshopProfile.Distillation(AlembicProcess.HEADS_END, AlembicProcess.TAILS_START, AlembicProcess.RUN_SECONDS),
                "default", true);
    }

    private WorkshopProfiles() {}
}
