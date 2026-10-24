package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.guide.GuideHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The built-in guide (spec 10): stations, controls, quality, recovery, the searchable list of supported paths, favourites,
 * the last result and practice links. With a potion in the other hand it opens the label editor for that potion instead.
 */
public class BrewersNotebookItem extends Item {
    public BrewersNotebookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            GuideHooks.openNotebook(player.getItemInHand(other), other);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.immersive_brewing.notebook").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.immersive_brewing.notebook.label").withStyle(ChatFormatting.DARK_GRAY));
    }
}
