package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.registry.ModTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact stacks inside a Batch Carrier (spec 10): up to {@link #SLOTS} potion-container stacks with every tag kept.
 * Anything inventory-bearing (carriers, recovery batches, shulker boxes, anything with an item handler) is refused.
 */
public final class CarrierContents {
    public static final String KEY = "immersive_brewing_carrier";
    public static final int SLOTS = 6;

    public static boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        if (stack.getItem() instanceof BatchCarrierItem || stack.getItem() instanceof RecoveryBatchItem) return false;
        if (!stack.getItem().canFitInsideContainerItems()) return false;
        if (stack.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) return false;
        return stack.getItem() instanceof PotionItem || stack.getItem() instanceof TippedArrowItem || stack.is(ModTags.CARRIER_STORABLE)
                || stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE);
    }

    public static List<ItemStack> read(ItemStack carrier) {
        List<ItemStack> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) out.add(ItemStack.EMPTY);
        CompoundTag root = carrier.getTag();
        if (root == null || !root.contains(KEY, Tag.TAG_LIST)) return out;
        ListTag list = root.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot");
            if (slot < 0 || slot >= SLOTS) continue;
            out.set(slot, ItemStack.of(entry));
        }
        return out;
    }

    public static void write(ItemStack carrier, List<ItemStack> contents) {
        ListTag list = new ListTag();
        for (int i = 0; i < Math.min(SLOTS, contents.size()); i++) {
            ItemStack s = contents.get(i);
            if (s.isEmpty()) continue;
            CompoundTag entry = s.save(new CompoundTag());
            entry.putByte("Slot", (byte) i);
            list.add(entry);
        }
        if (list.isEmpty()) {
            CompoundTag root = carrier.getTag();
            if (root != null) {
                root.remove(KEY);
                if (root.isEmpty()) carrier.setTag(null);
            }
        } else {
            carrier.getOrCreateTag().put(KEY, list);
        }
    }

    public static boolean isEmpty(ItemStack carrier) {
        return read(carrier).stream().allMatch(ItemStack::isEmpty);
    }

    public static int count(ItemStack carrier) {
        int n = 0;
        for (ItemStack s : read(carrier)) n += s.getCount();
        return n;
    }

    private CarrierContents() {}
}
