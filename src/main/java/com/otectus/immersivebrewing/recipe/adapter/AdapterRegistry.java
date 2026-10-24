package com.otectus.immersivebrewing.recipe.adapter;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.api.BrewingAdapter;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registered adapters, resolved deterministically by priority then id (spec 15.2). A second registration of an id is
 * refused and logged as an ownership conflict. Registration closes when common setup finishes.
 */
public final class AdapterRegistry {
    private static final Map<ResourceLocation, BrewingAdapter> ADAPTERS = new LinkedHashMap<>();
    private static volatile List<BrewingAdapter> ordered = List.of();
    private static volatile boolean frozen;

    static {
        register(new ForgeRegistryAdapter());
    }

    public static synchronized boolean register(BrewingAdapter adapter) {
        if (frozen) {
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: adapter {} registered after setup; ignored", adapter.id());
            return false;
        }
        BrewingAdapter existing = ADAPTERS.get(adapter.id());
        if (existing != null) {
            ImmersiveBrewing.LOGGER.error("Immersive Brewing: adapter id {} is already owned by {}; {} ignored", adapter.id(),
                    existing.getClass().getName(), adapter.getClass().getName());
            return false;
        }
        ADAPTERS.put(adapter.id(), adapter);
        List<BrewingAdapter> list = new ArrayList<>(ADAPTERS.values());
        list.sort(Comparator.comparingInt(BrewingAdapter::priority).reversed().thenComparing(a -> a.id().toString()));
        ordered = List.copyOf(list);
        return true;
    }

    public static void freeze() {
        frozen = true;
    }

    /** The adapter with this id, unless missing or excluded by the active data. */
    @Nullable
    public static BrewingAdapter get(ResourceLocation id, BrewingDefinitions defs) {
        if (defs.exclusions().excludesAdapter(id)) return null;
        return ADAPTERS.get(id);
    }

    @Nullable
    public static BrewingAdapter getAny(ResourceLocation id) {
        return ADAPTERS.get(id);
    }

    /** Adapters in resolution order, excluded ones removed. */
    public static List<BrewingAdapter> ordered(BrewingDefinitions defs) {
        return ordered.stream().filter(a -> !defs.exclusions().excludesAdapter(a.id())).toList();
    }

    public static List<BrewingAdapter> all() {
        return ordered;
    }

    private AdapterRegistry() {}
}
