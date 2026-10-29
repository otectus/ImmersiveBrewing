package com.otectus.immersivebrewing.potion;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.api.IEffectQualityHandler;
import com.otectus.immersivebrewing.api.ImmersiveBrewingAPI;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The single place that turns canonical potion contents plus brewing metadata into effective contents (spec 12.1).
 *
 * <ul>
 * <li><b>Canonical contents</b> are the stack exactly as the authoritative brewing operation returned it; this service
 * never changes them, never touches {@link Potion#getEffects()} and never writes {@code CustomPotionEffects}.</li>
 * <li><b>Effective contents</b> are fresh {@link MobEffectInstance}s: the potion's registered effects with finite
 * durations scaled by quality where an effect policy allows it, followed by exact copies of any custom effects
 * (foreign data, never scaled). Amplifier, ambient, particle and icon flags, hidden effects and Forge curative items
 * are preserved.</li>
 * </ul>
 * Resolution only reads, so {@code resolve(stack) == resolve(stack)}: repeated reads, saving, loading or moving the
 * stack never compound the multiplier. Untagged stacks resolve to exactly the vanilla list.
 */
public final class PotionContentsService {
    /** Entity persistent-data key carrying the metadata of the potion a cloud or arrow came from. */
    public static final String ENTITY_KEY = BrewingMetadata.ROOT;

    /** A copy of the stack without this mod's compound: the input every brewing query and fingerprint uses. */
    public static ItemStack canonical(ItemStack stack) {
        ItemStack copy = stack.copy();
        BrewingMetadata.strip(copy);
        return copy;
    }

    public static Optional<BrewingMetadata> metadata(ItemStack stack) {
        return BrewingMetadata.read(stack);
    }

    /** Effective quality: the recorded score, or the baseline 50 for ordinary potions (spec 12.3). */
    public static int quality(ItemStack stack) {
        return metadata(stack).map(BrewingMetadata::quality).orElse(QualityMath.BASELINE);
    }

    /**
     * Vanilla potion items whose effect paths this mod hooks. Other potion-like items keep their own behaviour; their
     * quality is metadata only (spec 11.3: no guessed hooks for unknown custom potion items).
     */
    public static boolean effectsSupported(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW);
    }

    /** Whether the current policy can actually change a registered duration, rather than merely record a grade. */
    public static boolean durationCanScale(ItemStack stack, BrewingDefinitions defs) {
        if (!effectsSupported(stack) || EffectPolicies.isBundle(PotionUtils.getPotion(stack))) return false;
        for (MobEffectInstance effect : PotionUtils.getPotion(stack).getEffects()) {
            if (effect.isInfiniteDuration() || effect.getDuration() <= 0) continue;
            var treatment = EffectPolicies.treatment(effect.getEffect(), defs);
            if (treatment.scales() && (QualityMath.scaleDuration(effect.getDuration(), 0, treatment.minimum(), treatment.maximum()) != effect.getDuration()
                    || QualityMath.scaleDuration(effect.getDuration(), 100, treatment.minimum(), treatment.maximum()) != effect.getDuration())) return true;
        }
        return false;
    }

    /**
     * Effective effects of a supported stack (drinking and splash): scaled registered effects, then exact custom effects.
     * Untagged or unsupported stacks return exactly {@link PotionUtils#getMobEffects(ItemStack)}.
     */
    public static List<MobEffectInstance> effectiveEffects(ItemStack stack, BrewingDefinitions defs) {
        Optional<BrewingMetadata> meta = metadata(stack);
        if (meta.isEmpty() || meta.get().quality() == QualityMath.BASELINE) return PotionUtils.getMobEffects(stack);
        if (!effectsSupported(stack)) return handled(stack, meta.get().quality());
        List<MobEffectInstance> out = new ArrayList<>(effectivePotionEffects(PotionUtils.getPotion(stack), meta.get().quality(), defs));
        for (MobEffectInstance custom : PotionUtils.getCustomEffects(stack)) out.add(new MobEffectInstance(custom));
        return out;
    }

    public static List<MobEffectInstance> effectiveEffects(ItemStack stack) {
        return effectiveEffects(stack, BrewingDefinitions.server());
    }

    /** The application path a stack's effects take, as effect-quality handlers declare them. */
    public static IEffectQualityHandler.Path pathOf(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof LingeringPotionItem) return IEffectQualityHandler.Path.LINGERING;
        if (item instanceof ThrowablePotionItem) return IEffectQualityHandler.Path.SPLASH;
        if (item instanceof TippedArrowItem) return IEffectQualityHandler.Path.ARROW;
        return IEffectQualityHandler.Path.DRINK;
    }

    /**
     * Effects of a graded item other than the four vanilla potion items: the first registered handler that takes the
     * stack on its path, where API version 1 consults handlers (drinking and splash impact, the paths that apply the stack
     * itself), as fresh instances; otherwise, or if the handler fails, the item's own effects (metadata-only).
     */
    private static List<MobEffectInstance> handled(ItemStack stack, int quality) {
        var path = pathOf(stack);
        if (path == IEffectQualityHandler.Path.DRINK || path == IEffectQualityHandler.Path.SPLASH) {
            for (var handler : ImmersiveBrewingAPI.effectHandlers()) {
                try {
                    if (!handler.handles(stack) || !handler.paths().contains(path)) continue;
                    List<MobEffectInstance> out = new ArrayList<>();
                    for (MobEffectInstance e : handler.resolve(stack.copy(), quality)) out.add(new MobEffectInstance(e));
                    return out;
                } catch (RuntimeException e) {
                    ImmersiveBrewing.LOGGER.error("Immersive Brewing: effect handler {} failed for {}; using the item's own effects",
                            handler.getClass().getName(), stack, e);
                    break;
                }
            }
        }
        return PotionUtils.getMobEffects(stack);
    }

    /**
     * The potion's registered effects resolved at {@code quality}: fresh instances, finite durations scaled only for
     * policy-approved effects of a non-bundle potion. Used where vanilla reads {@link Potion#getEffects()} directly
     * (lingering clouds and arrows), so their own conversions (/4, /8) apply exactly once afterwards.
     */
    public static List<MobEffectInstance> effectivePotionEffects(Potion potion, int quality, BrewingDefinitions defs) {
        List<MobEffectInstance> base = potion.getEffects();
        if (quality == QualityMath.BASELINE || base.isEmpty()) return base;
        boolean bundle = EffectPolicies.isBundle(potion);
        List<MobEffectInstance> out = new ArrayList<>(base.size());
        for (MobEffectInstance instance : base) {
            out.add(bundle ? new MobEffectInstance(instance) : resolve(instance, quality, defs));
        }
        return out;
    }

    private static MobEffectInstance resolve(MobEffectInstance instance, int quality, BrewingDefinitions defs) {
        if (instance.isInfiniteDuration() || instance.getDuration() <= 0) return new MobEffectInstance(instance);
        EffectPolicies.Treatment treatment = EffectPolicies.treatment(instance.getEffect(), defs);
        if (!treatment.scales()) return new MobEffectInstance(instance);
        int duration = QualityMath.scaleDuration(instance.getDuration(), quality, treatment.minimum(), treatment.maximum());
        return withDuration(instance, duration);
    }

    /**
     * A fresh copy with another duration and every other property kept (through the instance's own NBT round trip,
     * which carries the hidden-effect chain, factor data and Forge curative items).
     */
    public static MobEffectInstance withDuration(MobEffectInstance instance, int duration) {
        if (duration == instance.getDuration()) return new MobEffectInstance(instance);
        CompoundTag tag = instance.save(new CompoundTag());
        tag.putInt("Duration", duration);
        MobEffectInstance copy = MobEffectInstance.load(tag);
        return copy != null ? copy : new MobEffectInstance(instance);
    }

    /**
     * Drinking time (spec 11.2) for a supported drinkable potion with metadata; {@code vanillaTicks} for anything else.
     */
    public static int effectiveUseTicks(ItemStack stack, int vanillaTicks) {
        if (!stack.is(Items.POTION) || !ServerConfig.qualityAffectsDrinkTime()) return vanillaTicks;
        Optional<BrewingMetadata> meta = metadata(stack);
        if (meta.isEmpty()) return vanillaTicks;
        return QualityMath.useTicks(meta.get().quality(), vanillaTicks);
    }

    // ---- canonical identity ----

    /** True when both stacks have the same item and the same tag apart from this mod's compound. */
    public static boolean sameCanonical(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem()) return false;
        return Objects.equals(canonicalTag(a), canonicalTag(b));
    }

    @Nullable
    public static CompoundTag canonicalTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        if (!tag.contains(BrewingMetadata.ROOT)) return tag.isEmpty() ? null : tag;
        CompoundTag copy = tag.copy();
        copy.remove(BrewingMetadata.ROOT);
        return copy.isEmpty() ? null : copy;
    }

    /**
     * A stable 64-bit fingerprint of item id plus canonical tag (keys sorted by NBT's string form), independent of this
     * mod's labels and bookkeeping and of stack size. Stable across restarts, so ledgers may persist it.
     */
    public static long fingerprint(ItemStack stack) {
        String id = String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        CompoundTag tag = canonicalTag(stack);
        return fnv(id + "|" + (tag == null ? "" : tag.getAsString()));
    }

    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ---- entities that carry a potion's metadata (lingering clouds, tipped arrows) ----

    /** Copies the source stack's metadata onto the entity, or clears it when the stack has none. */
    public static void tagEntity(Entity entity, ItemStack source) {
        CompoundTag data = entity.getPersistentData();
        CompoundTag stackTag = source.getTag();
        if (effectsSupported(source) && stackTag != null && stackTag.contains(BrewingMetadata.ROOT, Tag.TAG_COMPOUND)
                && BrewingMetadata.read(source).isPresent()) {
            data.put(ENTITY_KEY, stackTag.getCompound(BrewingMetadata.ROOT).copy());
        } else {
            data.remove(ENTITY_KEY);
        }
    }

    public static Optional<BrewingMetadata> entityMetadata(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(ENTITY_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        return BrewingMetadata.fromTag(data.getCompound(ENTITY_KEY));
    }

    public static OptionalInt entityQuality(Entity entity) {
        return entityMetadata(entity).map(m -> OptionalInt.of(m.quality())).orElse(OptionalInt.empty());
    }

    /** The registered effects a tagged entity applies, or the potion's own list for an untagged one. */
    public static List<MobEffectInstance> entityPotionEffects(Entity entity, Potion potion) {
        OptionalInt quality = entityQuality(entity);
        if (quality.isEmpty()) return potion.getEffects();
        return effectivePotionEffects(potion, quality.getAsInt(), BrewingDefinitions.server());
    }

    /** Restores an arrow's metadata onto the stack a player picks up. */
    public static void copyEntityMetadata(Entity entity, ItemStack target) {
        CompoundTag data = entity.getPersistentData();
        if (!target.is(Items.TIPPED_ARROW) || !data.contains(ENTITY_KEY, Tag.TAG_COMPOUND)) return;
        target.getOrCreateTag().put(BrewingMetadata.ROOT, data.getCompound(ENTITY_KEY).copy());
    }

    private PotionContentsService() {}
}
