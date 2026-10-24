package com.otectus.immersivebrewing.data;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Deterministic whole-profile resolution (spec 16.5): the most specific matching layer wins; inside a layer the highest
 * priority wins; equal priority falls to the lexicographically smallest id and records a tie diagnostic. Profiles are
 * never merged field by field.
 */
public final class ProfileSelector {

    /** The chosen candidate, its layer, and the id it tied with (null when there was no tie). */
    public record Choice<T>(T value, ResourceLocation id, Selector.Layer layer, @Nullable ResourceLocation tiedWith) {}

    public static <T> Choice<T> choose(List<T> candidates, Function<T, ResourceLocation> idOf, Function<T, Selector> selectorOf,
                                       Function<T, Integer> priorityOf, ResourceLocation entry, Predicate<ResourceLocation> inTag,
                                       @Nullable ResourceLocation recipe) {
        Selector.Layer bestLayer = null;
        T best = null;
        ResourceLocation tie = null;
        Comparator<ResourceLocation> byId = Comparator.comparing(ResourceLocation::toString);
        for (T candidate : candidates) {
            Selector selector = selectorOf.apply(candidate);
            if (!selector.matches(entry, inTag, recipe)) continue;
            Selector.Layer layer = selector.layer();
            if (best == null || layer.ordinal() < bestLayer.ordinal()) {
                best = candidate;
                bestLayer = layer;
                tie = null;
                continue;
            }
            if (layer != bestLayer) continue;
            int p = priorityOf.apply(candidate);
            int bp = priorityOf.apply(best);
            if (p > bp) {
                best = candidate;
                tie = null;
            } else if (p == bp) {
                ResourceLocation a = idOf.apply(candidate);
                ResourceLocation b = idOf.apply(best);
                if (byId.compare(a, b) < 0) {
                    tie = b;
                    best = candidate;
                } else {
                    tie = a;
                }
            }
        }
        return best == null ? null : new Choice<>(best, idOf.apply(best), bestLayer, tie);
    }

    private ProfileSelector() {}
}
