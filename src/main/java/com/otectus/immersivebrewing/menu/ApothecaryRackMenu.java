package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/** Conventional six-slot rack inventory. Filter and pack buttons are server-routed through the open menu. */
public class ApothecaryRackMenu extends AbstractContainerMenu {
    public static final int FILTER_BASE = 0;
    public static final int PACK_BUTTON = 10;
    private final BlockPos pos;
    @Nullable private final ApothecaryRackBlockEntity rack;

    public ApothecaryRackMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.APOTHECARY_RACK.get(), id);
        this.pos = pos;
        this.rack = inventory.player.level().getBlockEntity(pos) instanceof ApothecaryRackBlockEntity be ? be : null;
        if (rack != null) {
            for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) addSlot(new SlotItemHandler(rack.inventory(), i, 35 + i * 18, 22));
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 85 + row * 18));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 143));
    }

    public static ApothecaryRackMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new ApothecaryRackMenu(id, inventory, buf.readBlockPos());
    }

    public BlockPos pos() { return pos; }
    @Nullable public ApothecaryRackBlockEntity rack() { return rack; }

    @Override
    public boolean stillValid(Player player) {
        if (rack == null || rack.isRemoved() || player.level().getBlockEntity(pos) != rack) return false;
        double max = ServerConfig.get(ServerConfig.MAX_STATION_DISTANCE) + 2;
        return player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= max * max;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (rack == null || !stillValid(player)) return false;
        if (id >= FILTER_BASE && id < FILTER_BASE + ApothecaryRackBlockEntity.SLOTS) {
            ItemStack ghost = getCarried();
            int slot = id - FILTER_BASE;
            if (ghost.isEmpty()) ghost = rack.filter(slot).isEmpty() ? rack.inventory().getStackInSlot(slot) : ItemStack.EMPTY;
            return ghost.isEmpty() ? rack.setFilter(slot, ItemStack.EMPTY)
                    : CarrierContents.accepts(ghost) && rack.setFilter(slot, ghost);
        }
        if (id == PACK_BUTTON) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack carrier = player.getInventory().getItem(i);
                if (carrier.getItem() instanceof BatchCarrierItem) {
                    rack.exchangeWithCarrier(player, carrier);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < ApothecaryRackBlockEntity.SLOTS) {
            if (!moveItemStackTo(stack, ApothecaryRackBlockEntity.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!CarrierContents.accepts(stack) || !moveItemStackTo(stack, 0, ApothecaryRackBlockEntity.SLOTS, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return original;
    }
}
