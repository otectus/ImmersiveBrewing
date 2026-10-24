package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.config.CommonConfig;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Map;

/**
 * The Kettle's stirring tool (spec 4). Each completed productive brew wears it by one. A better paddle lasts longer and
 * grants no quality. Only Unbreaking and Mending apply (spec 17, Immersive Enchanting), at tables and from books.
 */
public class BrewingPaddleItem extends Item {
    public BrewingPaddleItem(Properties properties) {
        super(properties);
    }

    private static boolean allowed(Enchantment enchantment) {
        return enchantment == Enchantments.UNBREAKING || enchantment == Enchantments.MENDING;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return CommonConfig.get(CommonConfig.ENCHANTING_INTEGRATION) && stack.getCount() == 1 && !stack.isEnchanted();
    }

    @Override
    public int getEnchantmentValue() {
        return 12;
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
        return CommonConfig.get(CommonConfig.ENCHANTING_INTEGRATION) && allowed(enchantment);
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        if (!CommonConfig.get(CommonConfig.ENCHANTING_INTEGRATION) || !(book.getItem() instanceof EnchantedBookItem)) return false;
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(book);
        return !enchantments.isEmpty() && enchantments.keySet().stream().allMatch(BrewingPaddleItem::allowed);
    }
}
