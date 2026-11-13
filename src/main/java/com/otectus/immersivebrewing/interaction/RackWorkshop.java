package com.otectus.immersivebrewing.interaction;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

import java.util.List;

/**
 * The Apothecary Rack's physical actions (handoff 10): each of its six positions is a bottle target, and taking one,
 * taking the matching group, packing a carrier and unpacking a carrier's selected stack are distinct actions with
 * visible quantities. Exact stacks only, through the rack's own filtered inventory, and nothing is dropped: a full
 * player inventory refuses the take.
 */
public final class RackWorkshop {
    public static WorkshopResult act(ApothecaryRackBlockEntity rack, ServerPlayer player, WorkshopIntent intent) {
        if (!rack.readable()) return WorkshopResult.refuse("workshop.immersive_brewing.rack.unreadable");
        int slot = intent.part() - WorkshopParts.R_POSITION;
        boolean position = slot >= 0 && slot < ApothecaryRackBlockEntity.SLOTS;
        ItemStack held = player.getItemInHand(intent.hand());
        return switch (intent.action()) {
            case TAKE -> position ? take(rack, player, slot) : WorkshopResult.IGNORED;
            case TAKE_GROUP -> position ? takeGroup(rack, player, slot) : WorkshopResult.IGNORED;
            case INSERT -> {
                if (!position || held.isEmpty()) yield WorkshopResult.IGNORED;
                if (!CarrierContents.accepts(held)) yield WorkshopResult.refuse("workshop.immersive_brewing.rack.not_storable");
                ItemStack left = rack.inventory().insertItem(slot, held.copy(), false);
                if (left.getCount() == held.getCount()) yield WorkshopResult.refuse("workshop.immersive_brewing.rack.position_refuses");
                player.setItemInHand(intent.hand(), left);
                yield WorkshopResult.ACCEPTED;
            }
            case PACK -> {
                if (!(held.getItem() instanceof BatchCarrierItem)) yield WorkshopResult.refuse("workshop.immersive_brewing.rack.hold_carrier");
                int moved = rack.packInto(held);
                notice(player, Component.translatable("workshop.immersive_brewing.rack.packed", moved), moved == 0);
                yield moved > 0 ? WorkshopResult.ACCEPTED : WorkshopResult.IGNORED;
            }
            case UNPACK -> {
                if (!(held.getItem() instanceof BatchCarrierItem)) yield WorkshopResult.refuse("workshop.immersive_brewing.rack.hold_carrier");
                yield unpackSelected(rack, player, held, position ? slot : -1);
            }
            case DRAWER -> {
                NetworkHooks.openScreen(player, rack, buf -> buf.writeBlockPos(rack.getBlockPos()));
                yield WorkshopResult.ACCEPTED;
            }
            default -> WorkshopResult.IGNORED;
        };
    }

    private static WorkshopResult take(ApothecaryRackBlockEntity rack, ServerPlayer player, int slot) {
        ItemStack stack = rack.inventory().getStackInSlot(slot);
        if (stack.isEmpty()) return WorkshopResult.IGNORED;
        if (!fits(player.getInventory(), stack)) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.inventory_full");
        ItemStack taken = rack.inventory().extractItem(slot, stack.getCount(), false);
        Component name = taken.getHoverName();
        int count = give(player, taken);
        notice(player, Component.translatable("workshop.immersive_brewing.rack.took", count, name), false);
        return WorkshopResult.ACCEPTED;
    }

    private static WorkshopResult takeGroup(ApothecaryRackBlockEntity rack, ServerPlayer player, int slot) {
        ItemStack template = rack.inventory().getStackInSlot(slot).copy();
        if (template.isEmpty()) return WorkshopResult.IGNORED;
        int moved = 0;
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS; i++) {
            ItemStack stack = rack.inventory().getStackInSlot(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, template)) continue;
            if (!fits(player.getInventory(), stack)) break;
            ItemStack taken = rack.inventory().extractItem(i, stack.getCount(), false);
            moved += give(player, taken);
        }
        notice(player, Component.translatable("workshop.immersive_brewing.rack.took_group", moved, template.getHoverName()), moved == 0);
        return moved > 0 ? WorkshopResult.ACCEPTED : WorkshopResult.refuse("workshop.immersive_brewing.refuse.inventory_full");
    }

    /** Moves the carrier's selected stack into the targeted position (or the first one that takes it). */
    private static WorkshopResult unpackSelected(ApothecaryRackBlockEntity rack, ServerPlayer player, ItemStack carrier, int target) {
        List<ItemStack> contents = CarrierContents.read(carrier);
        int selected = CarrierContents.meta(carrier).selected();
        if (selected < 0 || selected >= contents.size() || contents.get(selected).isEmpty()) {
            return WorkshopResult.refuse("workshop.immersive_brewing.rack.selected_empty");
        }
        ItemStack source = contents.get(selected);
        ItemStack left = source.copy();
        if (target >= 0) left = rack.inventory().insertItem(target, left, false);
        for (int i = 0; i < ApothecaryRackBlockEntity.SLOTS && !left.isEmpty() && target < 0; i++) left = rack.inventory().insertItem(i, left, false);
        int moved = source.getCount() - left.getCount();
        if (moved <= 0) return WorkshopResult.refuse("workshop.immersive_brewing.rack.position_refuses");
        contents.set(selected, left);
        CarrierContents.write(carrier, contents);
        notice(player, Component.translatable("workshop.immersive_brewing.rack.unpacked", moved, source.getHoverName()), false);
        return WorkshopResult.ACCEPTED;
    }

    /**
     * Gives an exact stack to the player and returns how many items that was. {@code Inventory.add} empties the stack it
     * is handed, so the count is taken first. Callers check {@link #fits} beforehand; anything that still does not fit
     * drops at the player's feet rather than vanishing.
     */
    public static int give(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        int count = stack.getCount();
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
        return count;
    }

    /** Whether the whole stack fits the player's main inventory (no drops, no partial take). */
    public static boolean fits(Inventory inventory, ItemStack stack) {
        int left = stack.getCount();
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) left -= Math.min(left, stack.getMaxStackSize());
            else if (ItemStack.isSameItemSameTags(s, stack)) left -= Math.min(left, Math.max(0, s.getMaxStackSize() - s.getCount()));
        }
        return left <= 0;
    }

    private static void notice(ServerPlayer player, Component text, boolean failure) {
        com.otectus.immersivebrewing.network.ModNetwork.notice(player, text, failure);
    }

    private RackWorkshop() {}
}
