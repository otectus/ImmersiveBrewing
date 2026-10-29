package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.EffectPolicies;
import com.otectus.immersivebrewing.potion.Grade;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.potion.QualityMath;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * Graded potion tooltips (spec 10, 12.2: the tooltip describes what consumption actually does). The vanilla effect lines
 * are regenerated from the effective effects with the item's own duration factor and replaced in place; the grade
 * prefixes the name; scores, purification, brewer, label and note follow. Unlabelled base names stay visible.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID, value = Dist.CLIENT)
public final class PotionTooltips {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (BrewingMetadata.hasUnreadableCompound(stack)) {
            event.getToolTip().add(Component.translatable("tooltip.immersive_brewing.newer").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        Optional<BrewingMetadata> found = BrewingMetadata.read(stack);
        if (found.isEmpty()) return;
        BrewingMetadata meta = found.get();
        List<Component> tooltip = event.getToolTip();
        if (PotionContentsService.effectsSupported(stack)) replaceEffectLines(stack, tooltip);
        Grade grade = meta.grade();
        if (!tooltip.isEmpty() && !stack.hasCustomHoverName()) {
            MutableComponent name = Component.translatable("tooltip.immersive_brewing.graded_name", Component.translatable(grade.key()), tooltip.get(0));
            tooltip.set(0, name.withStyle(tooltip.get(0).getStyle()));
        }
        tooltip.add(Component.literal(grade.symbol() + " ").append(Component.translatable("tooltip.immersive_brewing.quality", meta.quality(),
                Component.translatable(grade.key()))).withStyle(s -> s.withColor(grade.color())));
        addBenefitExplanation(stack, meta, tooltip);
        tooltip.add(Component.translatable("tooltip.immersive_brewing.scores", meta.extraction(), meta.control()).withStyle(ChatFormatting.GRAY));
        if (!PotionContentsService.effectsSupported(stack)) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.metadata_only").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (meta.purified()) {
            tooltip.add((meta.refinerName() != null ? Component.translatable("tooltip.immersive_brewing.refined_by", meta.refinerName())
                    : Component.translatable("tooltip.immersive_brewing.purified")).withStyle(ChatFormatting.AQUA));
        }
        if (meta.brewerName() != null) tooltip.add(Component.translatable("tooltip.immersive_brewing.brewed_by", meta.brewerName()).withStyle(ChatFormatting.GRAY));
        if (meta.label() != null) tooltip.add(Component.literal("“" + meta.label() + "”").withStyle(ChatFormatting.ITALIC, ChatFormatting.GOLD));
        if (meta.note() != null) tooltip.add(Component.literal(meta.note()).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Uses the same resolved effect instances shown above, so comparisons include policy bounds and integer rounding. */
    private static void addBenefitExplanation(ItemStack stack, BrewingMetadata meta, List<Component> tooltip) {
        BrewingDefinitions defs = BrewingDefinitions.client();
        if (!PotionContentsService.effectsSupported(stack)) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.unsupported_item").withStyle(ChatFormatting.DARK_GRAY));
            addGradeContext(meta, tooltip);
            return;
        }
        ItemStack ordinary = stack.copy();
        BrewingMetadata.write(ordinary, meta.withScores(meta.extraction(), meta.control(), QualityMath.BASELINE));
        List<MobEffectInstance> actual = PotionContentsService.effectiveEffects(stack, defs);
        List<MobEffectInstance> baseline = PotionContentsService.effectiveEffects(ordinary, defs);
        float delivery = stack.is(Items.TIPPED_ARROW) ? 0.125F : stack.is(Items.LINGERING_POTION) ? 0.25F : 1.0F;
        int changed = 0;
        for (int i = 0; i < Math.min(actual.size(), baseline.size()) && changed < 3; i++) {
            MobEffectInstance resolved = actual.get(i);
            MobEffectInstance normal = baseline.get(i);
            if (resolved.getEffect() != normal.getEffect() || resolved.isInfiniteDuration() || normal.isInfiniteDuration()) continue;
            int delta = (int) Math.floor(resolved.getDuration() * delivery) - (int) Math.floor(normal.getDuration() * delivery);
            if (delta == 0) continue;
            String seconds = String.format(Locale.ROOT, "%+.1f", delta / 20.0);
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.duration_delta", seconds,
                    Component.translatable(resolved.getDescriptionId())).withStyle(delta > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            changed++;
        }

        boolean bundle = EffectPolicies.isBundle(PotionUtils.getPotion(stack));
        boolean instant = PotionUtils.getPotion(stack).getEffects().stream().anyMatch(e -> e.getEffect().isInstantenous());
        boolean custom = !PotionUtils.getCustomEffects(stack).isEmpty();
        if (bundle) tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.mixed_unchanged").withStyle(ChatFormatting.DARK_GRAY));
        if (instant) tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.instant_unchanged").withStyle(ChatFormatting.DARK_GRAY));
        if (custom) tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.custom_unchanged").withStyle(ChatFormatting.DARK_GRAY));
        if (changed == 0 && !bundle && !instant && !PotionUtils.getPotion(stack).getEffects().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.policy_unchanged").withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(Items.POTION)) {
            int ordinaryTicks = ordinary.getUseDuration();
            int actualTicks = PotionContentsService.effectiveUseTicks(stack, ordinaryTicks);
            if (actualTicks != ordinaryTicks) tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.drink_time",
                    actualTicks, ordinaryTicks).withStyle(actualTicks < ordinaryTicks ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        addGradeContext(meta, tooltip);
    }

    private static void addGradeContext(BrewingMetadata meta, List<Component> tooltip) {
        if (meta.quality() == QualityMath.BASELINE) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.exact_baseline").withStyle(ChatFormatting.DARK_GRAY));
        } else if (meta.grade() == Grade.STANDARD) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.standard_band", meta.quality()).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (meta.extraction() == QualityMath.RAW_EXTRACTION) {
            int rawCeiling = QualityMath.quality(QualityMath.RAW_EXTRACTION, QualityMath.MAX);
            tooltip.add(Component.translatable("tooltip.immersive_brewing.quality.raw_ceiling", rawCeiling).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void replaceEffectLines(ItemStack stack, List<Component> tooltip) {
        float factor = stack.is(Items.TIPPED_ARROW) ? 0.125F : stack.is(Items.LINGERING_POTION) ? 0.25F : 1.0F;
        List<Component> vanilla = new ArrayList<>();
        PotionUtils.addPotionTooltip(stack, vanilla, factor);
        List<MobEffectInstance> effective = PotionContentsService.effectiveEffects(stack, BrewingDefinitions.client());
        List<Component> resolved = new ArrayList<>();
        PotionUtils.addPotionTooltip(effective, resolved, factor);
        if (vanilla.isEmpty() || vanilla.equals(resolved)) return;
        for (int i = 1; i + vanilla.size() <= tooltip.size(); i++) {
            if (tooltip.subList(i, i + vanilla.size()).equals(vanilla)) {
                for (int j = 0; j < vanilla.size(); j++) tooltip.remove(i);
                tooltip.addAll(i, resolved);
                return;
            }
        }
    }

    private PotionTooltips() {}
}
