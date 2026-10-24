package com.otectus.immersivebrewing.blockentity;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

/**
 * A station's public slots. Every path into them (menu clicks, shift-click, drag, hotbar swap, hoppers, pipes) goes
 * through {@link #isItemValid} and {@link #getSlotLimit}, which ask the owning station, so reservation rules are the same
 * everywhere (spec 5.3).
 */
public final class StationInventory extends ItemStackHandler {
    private final StationBlockEntity owner;

    StationInventory(StationBlockEntity owner, int size) {
        super(size);
        this.owner = owner;
    }

    @Override
    public int getSlotLimit(int slot) {
        return owner.slotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return owner.canPlace(slot, stack);
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!owner.canTake(slot)) return ItemStack.EMPTY;
        return super.extractItem(slot, amount, simulate);
    }

    @Override
    protected void onContentsChanged(int slot) {
        owner.onInventoryChanged(slot);
    }

    /** Direct write that bypasses validation: only the station itself uses this, for commits and returns. */
    void force(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        stacks.set(slot, stack);
        onContentsChanged(slot);
    }

    public int size() {
        return getSlots();
    }
}
