package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.menu.ApothecaryRackMenu;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Six independent exact stacks, six reservation ghosts and short shelf labels. No ticker: sync happens on edits only. */
public class ApothecaryRackBlockEntity extends BlockEntity implements MenuProvider {
    public static final int SCHEMA = 1;
    public static final int SLOTS = 6;
    public static final int MAX_LABEL = 16;

    private final List<ItemStack> filters = emptyStacks();
    private final String[] labels = new String[SLOTS];
    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return readable() && CarrierContents.accepts(stack) && filterMatches(slot, stack);
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override protected void onContentsChanged(int slot) { contentChanged(); }
    };
    private LazyOptional<ItemStackHandler> itemHandler = LazyOptional.of(() -> inventory);
    @Nullable private CompoundTag futurePayload;
    private boolean dropped;

    public ApothecaryRackBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.APOTHECARY_RACK.get(), pos, state);
    }

    public ItemStackHandler inventory() { return inventory; }
    public boolean readable() { return futurePayload == null; }

    public ItemStack filter(int slot) {
        return slot < 0 || slot >= SLOTS ? ItemStack.EMPTY : filters.get(slot).copy();
    }

    public boolean setFilter(int slot, ItemStack stack) {
        if (!readable() || slot < 0 || slot >= SLOTS || (!stack.isEmpty() && !CarrierContents.accepts(stack))) return false;
        ItemStack existing = inventory.getStackInSlot(slot);
        ItemStack ghost = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(Math.max(1, stack.getCount()));
        if (!existing.isEmpty() && !ghost.isEmpty() && !ItemStack.isSameItemSameTags(existing, ghost)) return false;
        filters.set(slot, ghost);
        contentChanged();
        return true;
    }

    private boolean filterMatches(int slot, ItemStack stack) {
        ItemStack filter = filters.get(slot);
        return filter.isEmpty() || ItemStack.isSameItemSameTags(filter, stack);
    }

    public String label(int slot) {
        if (slot < 0 || slot >= SLOTS) return "";
        String explicit = labels[slot];
        if (explicit != null && !explicit.isEmpty()) return explicit;
        return BrewingMetadata.read(inventory.getStackInSlot(slot)).map(BrewingMetadata::label).orElse("");
    }

    public boolean setLabel(int slot, String label) {
        if (!readable() || slot < 0 || slot >= SLOTS || !BrewingMetadata.Text.valid(label, MAX_LABEL)) return false;
        labels[slot] = label.isBlank() ? null : label.strip();
        contentChanged();
        return true;
    }

    /** Rack -> carrier when anything is displayed; otherwise carrier -> rack using exact reservation ghosts. */
    public int exchangeWithCarrier(Player player, ItemStack carrier) {
        if (!readable()) return 0;
        boolean packing = hasContents();
        int moved = packing ? packInto(carrier) : restockFrom(carrier);
        if (player instanceof ServerPlayer sp) ModNetwork.notice(sp, Component.translatable(packing
                ? "notice.immersive_brewing.rack.remaining" : "notice.immersive_brewing.rack.transferred", moved), moved == 0);
        return moved;
    }

    /** Conservation-safe packing: commit each source only after its exact remainder is known. */
    public int packInto(ItemStack carrier) {
        if (!readable()) return 0;
        List<ItemStack> contents = CarrierContents.read(carrier);
        int moved = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack source = inventory.getStackInSlot(slot);
            if (source.isEmpty()) continue;
            ItemStack left = source.copy();
            for (int c = 0; c < CarrierContents.SLOTS && !left.isEmpty(); c++) {
                ItemStack target = contents.get(c);
                if (target.isEmpty()) {
                    int n = Math.min(left.getCount(), left.getMaxStackSize());
                    contents.set(c, left.copyWithCount(n));
                    left.shrink(n);
                } else if (ItemStack.isSameItemSameTags(target, left)) {
                    int n = Math.min(left.getCount(), target.getMaxStackSize() - target.getCount());
                    if (n > 0) {
                        contents.set(c, target.copyWithCount(target.getCount() + n));
                        left.shrink(n);
                    }
                }
            }
            int delta = source.getCount() - left.getCount();
            if (delta > 0) {
                inventory.setStackInSlot(slot, left);
                moved += delta;
            }
        }
        if (moved > 0) CarrierContents.write(carrier, contents);
        return moved;
    }

    public int restockFrom(ItemStack carrier) {
        if (!readable()) return 0;
        List<ItemStack> contents = CarrierContents.read(carrier);
        int moved = 0;
        // Reservations get first refusal. Otherwise an earlier generic empty shelf can consume the only exact stack
        // intended for a later labelled/filter slot, leaving the visible loadout promise unfulfilled.
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) continue;
                ItemStack wanted = filters.get(slot);
                if ((pass == 0) == wanted.isEmpty()) continue;
                int sourceSlot = -1;
                for (int c = 0; c < CarrierContents.SLOTS; c++) {
                    ItemStack candidate = contents.get(c);
                    if (!candidate.isEmpty() && (wanted.isEmpty() || ItemStack.isSameItemSameTags(wanted, candidate))) {
                        sourceSlot = c;
                        break;
                    }
                }
                if (sourceSlot < 0) continue;
                ItemStack source = contents.get(sourceSlot);
                int limit = source.getMaxStackSize();
                int count = wanted.isEmpty() ? source.getCount() : Math.min(source.getCount(), Math.max(1, wanted.getCount()));
                count = Math.min(count, limit);
                ItemStack insert = source.copyWithCount(count);
                if (!inventory.insertItem(slot, insert, true).isEmpty()) continue;
                inventory.setStackInSlot(slot, insert);
                contents.set(sourceSlot, source.copyWithCount(source.getCount() - count));
                moved += count;
            }
        }
        if (moved > 0) CarrierContents.write(carrier, contents);
        return moved;
    }

    private boolean hasContents() {
        for (int i = 0; i < SLOTS; i++) if (!inventory.getStackInSlot(i).isEmpty()) return true;
        return false;
    }

    public int comparatorSignal() {
        int occupied = 0;
        for (int i = 0; i < SLOTS; i++) if (!inventory.getStackInSlot(i).isEmpty()) occupied++;
        return occupied == 0 ? 0 : Math.max(1, Math.round(15F * occupied / SLOTS));
    }

    public void dropContentsOnce() {
        if (dropped || level == null || level.isClientSide || !readable()) return;
        dropped = true;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, stack.copy());
            inventory.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    private void contentChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        int schema = tag.contains("schema", Tag.TAG_ANY_NUMERIC) ? tag.getInt("schema") : 1;
        if (schema > SCHEMA) {
            futurePayload = tag.copy();
            return;
        }
        futurePayload = null;
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) inventory.deserializeNBT(tag.getCompound("Inventory"));
        for (int i = 0; i < SLOTS; i++) filters.set(i, ItemStack.EMPTY);
        if (tag.contains("Filters", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Filters", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int slot = entry.getByte("Slot");
                ItemStack stack = ItemStack.of(entry);
                if (slot >= 0 && slot < SLOTS && CarrierContents.accepts(stack)) filters.set(slot, stack);
            }
        }
        ListTag savedLabels = tag.getList("Labels", Tag.TAG_STRING);
        for (int i = 0; i < SLOTS; i++) labels[i] = i < savedLabels.size() ? BrewingMetadata.Text.limit(savedLabels.getString(i), MAX_LABEL) : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (futurePayload != null) {
            for (String key : futurePayload.getAllKeys()) if (!key.equals("id") && !key.equals("x") && !key.equals("y") && !key.equals("z")) {
                Tag value = futurePayload.get(key);
                if (value != null) tag.put(key, value.copy());
            }
            return;
        }
        tag.putInt("schema", SCHEMA);
        tag.put("Inventory", inventory.serializeNBT());
        ListTag filterList = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            if (filters.get(i).isEmpty()) continue;
            CompoundTag entry = filters.get(i).save(new CompoundTag());
            entry.putByte("Slot", (byte) i);
            filterList.add(entry);
        }
        if (!filterList.isEmpty()) tag.put("Filters", filterList);
        ListTag labelList = new ListTag();
        for (String label : labels) labelList.add(StringTag.valueOf(label == null ? "" : label));
        tag.put("Labels", labelList);
    }

    @Override public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override public Component getDisplayName() { return Component.translatable("container.immersive_brewing.apothecary_rack"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return readable() ? new ApothecaryRackMenu(id, inventory, worldPosition) : null;
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && !remove && readable()) return itemHandler.cast();
        return super.getCapability(cap, side);
    }
    @Override public void invalidateCaps() { super.invalidateCaps(); itemHandler.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); itemHandler = LazyOptional.of(() -> inventory); }

    private static List<ItemStack> emptyStacks() {
        List<ItemStack> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) out.add(ItemStack.EMPTY);
        return out;
    }
}
