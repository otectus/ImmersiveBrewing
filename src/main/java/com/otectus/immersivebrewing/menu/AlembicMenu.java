package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.registry.ModMenus;
import com.otectus.immersivebrewing.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Loading the Alembic: six input bottles, fuel and the output shelf. */
public class AlembicMenu extends StationMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 206;

    public AlembicMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.ALEMBIC.get(), id, inventory, pos, AlembicBlockEntity.SLOTS);
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) addStationSlot(AlembicBlockEntity.INPUT + i, 35 + i * 18, 18, false);
        addStationSlot(AlembicBlockEntity.FUEL, 8, 18, false);
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) addStationSlot(AlembicBlockEntity.OUTPUT + i, 35 + i * 18, 62, true);
        addPlayerSlots(inventory, 8, 124);
    }

    public static AlembicMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new AlembicMenu(id, inventory, buf.readBlockPos());
    }

    @Override
    protected int[] routeFor(ItemStack stack) {
        if (stack.is(ModTags.BURNER_FUEL)) return new int[] {AlembicBlockEntity.FUEL};
        int[] out = new int[AlembicBlockEntity.INPUT_SIZE];
        for (int i = 0; i < out.length; i++) out[i] = AlembicBlockEntity.INPUT + i;
        return out;
    }

    public AlembicBlockEntity alembic() {
        return (AlembicBlockEntity) station;
    }
}
