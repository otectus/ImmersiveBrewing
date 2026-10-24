package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * The open Batch Carrier: six slots written straight into the held carrier. The carrier itself is locked in place while
 * open (no click, swap or drop can move it), so its contents can not be duplicated or lost.
 */
public class BatchCarrierMenu extends AbstractContainerMenu {
    private final InteractionHand hand;
    private final int lockedSlot;
    private final Player player;
    private final ItemStackHandler handler;

    public BatchCarrierMenu(int id, Inventory inventory, InteractionHand hand) {
        super(ModMenus.BATCH_CARRIER.get(), id);
        this.hand = hand;
        this.player = inventory.player;
        ItemStack carrier = inventory.player.getItemInHand(hand);
        this.handler = new ItemStackHandler(CarrierContents.SLOTS) {
            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return CarrierContents.accepts(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                save();
            }
        };
        List<ItemStack> contents = CarrierContents.read(carrier);
        for (int i = 0; i < CarrierContents.SLOTS; i++) handler.setStackInSlot(i, contents.get(i));
        for (int i = 0; i < CarrierContents.SLOTS; i++) {
            addSlot(new SlotItemHandler(handler, i, 35 + i * 18, 20) {
                @Override
                public void setChanged() {
                    super.setChanged();
                    save();
                }
            });
        }
        int locked = -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18));
        }
        for (int col = 0; col < 9; col++) {
            Slot slot = new Slot(inventory, col, 8 + col * 18, 109) {
                @Override
                public boolean mayPickup(Player p) {
                    return !(getItem().getItem() instanceof BatchCarrierItem) || hand != InteractionHand.MAIN_HAND || getContainerSlot() != p.getInventory().selected;
                }
            };
            addSlot(slot);
            if (hand == InteractionHand.MAIN_HAND && col == inventory.selected) locked = slots.size() - 1;
        }
        this.lockedSlot = locked;
    }

    public static BatchCarrierMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new BatchCarrierMenu(id, inventory, buf.readEnum(InteractionHand.class));
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player p) {
        if (slotId == lockedSlot) return;
        if (type == ClickType.SWAP && (hand == InteractionHand.MAIN_HAND && button == p.getInventory().selected || hand == InteractionHand.OFF_HAND && button == 40)) return;
        super.clicked(slotId, button, type, p);
    }

    /** Writes the live slots back into the held carrier (server side only; the client copy follows by sync). */
    private void save() {
        if (player.level().isClientSide || handler == null) return;
        ItemStack carrier = player.getItemInHand(hand);
        if (!(carrier.getItem() instanceof BatchCarrierItem)) return;
        List<ItemStack> contents = new java.util.ArrayList<>();
        for (int i = 0; i < CarrierContents.SLOTS; i++) contents.add(handler.getStackInSlot(i).copy());
        CarrierContents.write(carrier, contents);
    }

    @Override
    public void broadcastChanges() {
        save();
        super.broadcastChanges();
    }

    @Override
    public void removed(Player p) {
        save();
        super.removed(p);
    }

    @Override
    public boolean stillValid(Player p) {
        return p.getItemInHand(hand).getItem() instanceof BatchCarrierItem;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || index == lockedSlot) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < CarrierContents.SLOTS) {
            if (!moveItemStackTo(stack, CarrierContents.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!CarrierContents.accepts(stack) || !moveItemStackTo(stack, 0, CarrierContents.SLOTS, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }
}
