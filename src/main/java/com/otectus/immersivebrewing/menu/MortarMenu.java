package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Loading the Mortar: the reagent bowl and the kit shelf. */
public class MortarMenu extends StationMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 176;

    public MortarMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.MORTAR.get(), id, inventory, pos, ApothecaryMortarBlockEntity.SLOTS);
        addStationSlot(ApothecaryMortarBlockEntity.INPUT, 44, 34, false);
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) addStationSlot(ApothecaryMortarBlockEntity.OUTPUT + i, 98 + (i % 2) * 18, 25 + (i / 2) * 18, true);
        addPlayerSlots(inventory, 8, 94);
    }

    public static MortarMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new MortarMenu(id, inventory, buf.readBlockPos());
    }

    @Override
    protected int[] routeFor(ItemStack stack) {
        return new int[] {ApothecaryMortarBlockEntity.INPUT};
    }

    public ApothecaryMortarBlockEntity mortar() {
        return (ApothecaryMortarBlockEntity) station;
    }
}
