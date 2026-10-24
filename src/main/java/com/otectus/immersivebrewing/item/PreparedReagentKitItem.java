package com.otectus.immersivebrewing.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The Mortar's output: one serving group's worth of a prepared effect reagent (see {@link KitData}). */
public class PreparedReagentKitItem extends Item {
    public PreparedReagentKitItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return KitData.read(stack)
                .<Component>map(kit -> Component.translatable("item.immersive_brewing.prepared_reagent_kit.of", kit.source().getHoverName()))
                .orElseGet(() -> super.getName(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        KitData.read(stack).ifPresentOrElse(kit -> {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.kit.extraction", kit.extraction()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.immersive_brewing.kit.covers", kit.groups() * 3).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.immersive_brewing.kit.replaces", kit.source().getHoverName()).withStyle(ChatFormatting.DARK_GRAY));
        }, () -> tooltip.add(Component.translatable("tooltip.immersive_brewing.kit.empty").withStyle(ChatFormatting.RED)));
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return true;
    }
}
