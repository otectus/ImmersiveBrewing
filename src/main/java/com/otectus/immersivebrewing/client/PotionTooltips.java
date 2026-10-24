package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.Grade;
import com.otectus.immersivebrewing.potion.PotionContentsService;
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
