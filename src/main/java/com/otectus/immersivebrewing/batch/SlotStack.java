package com.otectus.immersivebrewing.batch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** An exact stack moved out of a station slot, remembered with that slot so cancelling returns it in place. */
public record SlotStack(int slot, ItemStack stack) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("slot", (byte) slot);
        tag.put("stack", LedgerCodec.saveStack(stack));
        return tag;
    }

    public static SlotStack load(CompoundTag tag) {
        return new SlotStack(tag.getByte("slot"), LedgerCodec.loadStack(tag.getCompound("stack")));
    }
}
