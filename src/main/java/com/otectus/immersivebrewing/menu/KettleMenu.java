package com.otectus.immersivebrewing.menu;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModMenus;
import com.otectus.immersivebrewing.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.Items;

/** Loading the Kettle: bottle rack, ingredient tray, fuel, paddle and the by-product shelf. Brewing itself is its own screen. */
public class KettleMenu extends StationMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 206;

    public KettleMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModMenus.KETTLE.get(), id, inventory, pos, BrewersKettleBlockEntity.SLOTS);
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) addStationSlot(BrewersKettleBlockEntity.RACK + i, 35 + i * 18, 18, false);
        for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE; i++) addStationSlot(BrewersKettleBlockEntity.TRAY + i, 35 + i * 18, 42, false);
        addStationSlot(BrewersKettleBlockEntity.FUEL, 8, 42, false);
        addStationSlot(BrewersKettleBlockEntity.PADDLE, 152, 18, false);
        for (int i = 0; i < BrewersKettleBlockEntity.BYPRODUCT_SIZE; i++) addStationSlot(BrewersKettleBlockEntity.BYPRODUCT + i, 116 + i * 18, 66, true);
        addPlayerSlots(inventory, 8, 124);
    }

    public static KettleMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new KettleMenu(id, inventory, buf.readBlockPos());
    }

    @Override
    protected int[] routeFor(ItemStack stack) {
        if (stack.getItem() instanceof PotionItem || stack.is(Items.GLASS_BOTTLE)) return rack();
        if (stack.is(ModTags.BREWING_PADDLES)) return new int[] {BrewersKettleBlockEntity.PADDLE};
        if (stack.is(ModTags.BURNER_FUEL)) {
            int[] tray = tray();
            int[] out = new int[tray.length + 1];
            out[0] = BrewersKettleBlockEntity.FUEL;
            System.arraycopy(tray, 0, out, 1, tray.length);
            return out;
        }
        return tray();
    }

    private static int[] rack() {
        int[] out = new int[BrewersKettleBlockEntity.RACK_SIZE];
        for (int i = 0; i < out.length; i++) out[i] = BrewersKettleBlockEntity.RACK + i;
        return out;
    }

    private static int[] tray() {
        int[] out = new int[BrewersKettleBlockEntity.TRAY_SIZE];
        for (int i = 0; i < out.length; i++) out[i] = BrewersKettleBlockEntity.TRAY + i;
        return out;
    }

    public BrewersKettleBlockEntity kettle() {
        return (BrewersKettleBlockEntity) station;
    }
}
