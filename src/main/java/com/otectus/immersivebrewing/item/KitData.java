package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * A prepared reagent kit's data (spec 6): the exact source reagent it consumed, how many serving groups it covers, the
 * preparation profile and the fixed extraction score. One registered item carries every kit; identical kits stack, and a
 * kit can never be unpacked, re-ground or count for extra servings.
 */
public record KitData(ItemStack source, int groups, ResourceLocation profile, int extraction) {
    public static final String KEY = "immersive_brewing_kit";
    public static final int SCHEMA = 1;

    public KitData {
        source = source.copyWithCount(1);
        groups = Math.max(1, Math.min(4, groups));
        extraction = QualityMath.clampScore(extraction);
    }

    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(ModItems.PREPARED_REAGENT_KIT.get(), count);
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        CompoundTag src = LedgerCodec.saveStack(source);
        src.remove("FullCount");
        tag.put("source", src);
        tag.putByte("groups", (byte) groups);
        tag.putString("profile", profile.toString());
        tag.putByte("E", (byte) extraction);
        stack.getOrCreateTag().put(KEY, tag);
        return stack;
    }

    public static Optional<KitData> read(ItemStack stack) {
        if (!stack.is(ModItems.PREPARED_REAGENT_KIT.get())) return Optional.empty();
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        CompoundTag tag = root.getCompound(KEY);
        if (tag.getInt("schema") != SCHEMA) return Optional.empty();
        ItemStack source = ItemStack.of(tag.getCompound("source"));
        ResourceLocation profile = ResourceLocation.tryParse(tag.getString("profile"));
        if (source.isEmpty() || profile == null) return Optional.empty();
        return Optional.of(new KitData(source, tag.getByte("groups"), profile, tag.getByte("E")));
    }
}
