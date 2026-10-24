package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Base menu of a station. Every slot is backed by the station's own {@code StationInventory}, so clicks, shift-clicks,
 * drags and hotbar swaps all pass through the station's placement and reservation rules (spec 5.3).
 */
public abstract class StationMenu extends AbstractContainerMenu {
    protected final BlockPos pos;
    @Nullable
    protected final StationBlockEntity station;
    protected final int stationSlots;

    protected StationMenu(@Nullable MenuType<?> type, int id, Inventory inventory, BlockPos pos, int stationSlots) {
        super(type, id);
        this.pos = pos;
        this.station = inventory.player.level().getBlockEntity(pos) instanceof StationBlockEntity be ? be : null;
        this.stationSlots = stationSlots;
    }

    public BlockPos pos() {
        return pos;
    }

    @Nullable
    public StationBlockEntity station() {
        return station;
    }

    protected void addStationSlot(int index, int x, int y, boolean takeOnly) {
        if (station == null) return;
        StationBlockEntity owner = station;
        addSlot(new SlotItemHandler(station.inventory(), index, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !takeOnly && super.mayPlace(stack);
            }

            @Override
            public boolean mayPickup(Player player) {
                return owner.canTake(index) && super.mayPickup(player);
            }

            @Override
            public void setChanged() {
                // Menus grow and shrink the live stack in place; tell the station so it saves, syncs and updates comparators.
                super.setChanged();
                owner.slotChanged(index);
            }
        });
    }

    protected void addPlayerSlots(Inventory inventory, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, x + col * 18, y + row * 18));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, x + col * 18, y + 58));
    }

    @Override
    public boolean stillValid(Player player) {
        if (station == null || station.isRemoved() || player.level().getBlockEntity(pos) != station) return false;
        double max = ServerConfig.get(ServerConfig.MAX_STATION_DISTANCE) + 2;
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= max * max;
    }

    /** Station slot indices a stack moved from the player inventory should try, in order. */
    protected abstract int[] routeFor(ItemStack stack);

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < stationSlots) {
            if (!moveItemStackTo(stack, stationSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            boolean moved = false;
            for (int target : routeFor(stack)) {
                if (stack.isEmpty()) break;
                if (moveItemStackTo(stack, target, target + 1, false)) moved = true;
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    /**
     * The station a player is actually working at: their open menu is this station's and still valid. Packets from a
     * player without the menu open are ignored.
     */
    @Nullable
    public static StationBlockEntity openStation(ServerPlayer player, BlockPos pos) {
        if (player.containerMenu instanceof StationMenu menu && menu.pos.equals(pos) && menu.station != null && menu.stillValid(player)) {
            return menu.station;
        }
        return null;
    }
}
