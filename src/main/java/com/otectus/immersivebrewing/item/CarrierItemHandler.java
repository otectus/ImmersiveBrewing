package com.otectus.immersivebrewing.item;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The narrow item handler of a Batch Carrier stack: six slots, potion containers only, item stack limits respected,
 * every change written straight back to the carrier's NBT. Simulation never writes.
 */
public final class CarrierItemHandler implements IItemHandlerModifiable {
    private final ItemStack carrier;

    public CarrierItemHandler(ItemStack carrier) {
        this.carrier = carrier;
    }

    @Override
    public int getSlots() {
        return CarrierContents.SLOTS;
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= CarrierContents.SLOTS) return ItemStack.EMPTY;
        return CarrierContents.read(carrier).get(slot);
    }

    @Override
    public void setStackInSlot(int slot, @NotNull ItemStack stack) {
        if (slot < 0 || slot >= CarrierContents.SLOTS) return;
        List<ItemStack> contents = CarrierContents.read(carrier);
        contents.set(slot, stack.copy());
        CarrierContents.write(carrier, contents);
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || slot < 0 || slot >= CarrierContents.SLOTS || !isItemValid(slot, stack)) return stack;
        List<ItemStack> contents = CarrierContents.read(carrier);
        ItemStack existing = contents.get(slot);
        int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameTags(existing, stack)) return stack;
            limit -= existing.getCount();
        }
        if (limit <= 0) return stack;
        int moved = Math.min(limit, stack.getCount());
        if (!simulate) {
            contents.set(slot, existing.isEmpty() ? stack.copyWithCount(moved) : existing.copyWithCount(existing.getCount() + moved));
            CarrierContents.write(carrier, contents);
        }
        return moved == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || slot < 0 || slot >= CarrierContents.SLOTS) return ItemStack.EMPTY;
        List<ItemStack> contents = CarrierContents.read(carrier);
        ItemStack existing = contents.get(slot);
        if (existing.isEmpty()) return ItemStack.EMPTY;
        int moved = Math.min(amount, existing.getCount());
        if (!simulate) {
            contents.set(slot, moved == existing.getCount() ? ItemStack.EMPTY : existing.copyWithCount(existing.getCount() - moved));
            CarrierContents.write(carrier, contents);
        }
        return existing.copyWithCount(moved);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return CarrierContents.accepts(stack);
    }
}
