package com.otectus.immersivebrewing.potion;

import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.EffectPolicyDefinition;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.Selector;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, per mob effect, whether quality may scale its duration (spec 11.3, 16.4). Without a matching policy an effect
 * is metadata-only. Instant effects, infinite durations and bundles (a potion whose own effects mix beneficial and
 * harmful categories, such as Turtle Master) never scale. Results are cached per definitions generation.
 */
public final class EffectPolicies {

    /** The treatment of one effect: whether its finite duration scales, and the bounds after the server caps. */
    public record Treatment(boolean scales, double minimum, double maximum, @Nullable ResourceLocation policy, Selector.Layer layer) {
        public static final Treatment METADATA_ONLY = new Treatment(false, 1, 1, null, Selector.Layer.FALLBACK);
    }

    /**
     * One decision cache per definitions instance (the server's and the client's copy live side by side in single
     * player), keyed by identity and pruned to a few generations.
     */
    private static final Map<BrewingDefinitions, Map<MobEffect, Treatment>> CACHES = new java.util.IdentityHashMap<>();

    public static Treatment treatment(MobEffect effect, BrewingDefinitions defs) {
        Map<MobEffect, Treatment> cache;
        synchronized (CACHES) {
            cache = CACHES.get(defs);
            if (cache == null) {
                if (CACHES.size() >= 4) CACHES.clear();
                cache = new ConcurrentHashMap<>();
                CACHES.put(defs, cache);
            }
        }
        return cache.computeIfAbsent(effect, e -> resolve(e, defs));
    }

    /** True when the potion's own effects mix beneficial and harmful categories: an explicit bundle (spec 11.3). */
    public static boolean isBundle(Potion potion) {
        boolean beneficial = false;
        boolean harmful = false;
        for (MobEffectInstance instance : potion.getEffects()) {
            MobEffectCategory category = instance.getEffect().getCategory();
            if (category == MobEffectCategory.BENEFICIAL) beneficial = true;
            if (category == MobEffectCategory.HARMFUL) harmful = true;
        }
        return beneficial && harmful;
    }

    private static Treatment resolve(MobEffect effect, BrewingDefinitions defs) {
        if (effect.isInstantenous()) return Treatment.METADATA_ONLY;
        ResourceLocation id = ForgeRegistries.MOB_EFFECTS.getKey(effect);
        if (id == null) return Treatment.METADATA_ONLY;
        Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
        List<EffectPolicyDefinition> policies = defs.effectPolicies();
        ProfileSelector.Choice<EffectPolicyDefinition> choice = ProfileSelector.choose(policies, EffectPolicyDefinition::id,
                EffectPolicyDefinition::selector, EffectPolicyDefinition::priority, id,
                tag -> holder.is(TagKey.create(Registries.MOB_EFFECT, tag)), null);
        if (choice == null) return Treatment.METADATA_ONLY;
        EffectPolicyDefinition policy = choice.value();
        if (policy.mode() != EffectPolicyDefinition.Mode.DURATION) {
            return new Treatment(false, 1, 1, policy.id(), choice.layer());
        }
        double min = Math.max(policy.minimum(), ServerConfig.qualityDurationMin());
        double max = Math.min(policy.maximum(), ServerConfig.qualityDurationMax());
        if (min > 1.0 || max < 1.0 || min > max) return new Treatment(false, 1, 1, policy.id(), choice.layer());
        return new Treatment(true, min, max, policy.id(), choice.layer());
    }

    /** Drops cached decisions, for example after the server config changed the global bounds. */
    public static void invalidate() {
        synchronized (CACHES) {
            CACHES.clear();
        }
    }

    private EffectPolicies() {}
}
