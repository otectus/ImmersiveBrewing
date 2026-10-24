package com.otectus.immersivebrewing.blockentity;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The face-specific view hoppers and pipes get (spec 22). Insertion follows the station's own slot rules plus its face
 * rules and the server's automation switch; extraction reaches only completed, unreserved output. Simulation never
 * changes anything. Reserved stacks are not in these slots at all: the ledger owns them.
 */
final class AutomationHandler implements IItemHandler {
    private final StationBlockEntity station;
    @Nullable
    private final Direction side;

    AutomationHandler(StationBlockEntity station, @Nullable Direction side) {
        this.station = station;
        this.side = side;
    }

    @Override
    public int getSlots() {
        return station.inventory().getSlots();
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        return station.inventory().getStackInSlot(slot);
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !station.automationAllowedIn() || !station.automationInsert(side, slot, stack)) return stack;
        return station.inventory().insertItem(slot, stack, simulate);
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || !station.automationAllowedOut() || !station.automationExtract(side, slot)) return ItemStack.EMPTY;
        return station.inventory().extractItem(slot, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return station.inventory().getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return station.automationAllowedIn() && station.automationInsert(side, slot, stack);
    }
}
