package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.registry.ModMenus;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI's "+" on a Kettle recipe moves one bottle into the rack and one of each reagent into the tray. JEI performs the move
 * on the server through the menu's own slots, so every reservation rule still applies: nothing moves into an open job.
 */
final class KettleTransferInfo implements IRecipeTransferInfo<KettleMenu, InfusionRecipe> {
    @Override
    public Class<? extends KettleMenu> getContainerClass() {
        return KettleMenu.class;
    }

    @Override
    public Optional<MenuType<KettleMenu>> getMenuType() {
        return Optional.of(ModMenus.KETTLE.get());
    }

    @Override
    public RecipeType<InfusionRecipe> getRecipeType() {
        return BrewingCategory.TYPE;
    }

    @Override
    public boolean canHandle(KettleMenu menu, InfusionRecipe recipe) {
        return menu.station() != null && menu.station().ledger() == null && menu.station().view().state() == null;
    }

    @Override
    public List<Slot> getRecipeSlots(KettleMenu menu, InfusionRecipe recipe) {
        List<Slot> out = new ArrayList<>();
        out.add(menu.slots.get(BrewersKettleBlockEntity.RACK));
        for (int i = 0; i < recipe.steps().size() && i < BrewersKettleBlockEntity.TRAY_SIZE; i++) out.add(menu.slots.get(BrewersKettleBlockEntity.TRAY + i));
        return out;
    }

    @Override
    public List<Slot> getInventorySlots(KettleMenu menu, InfusionRecipe recipe) {
        return menu.slots.subList(BrewersKettleBlockEntity.SLOTS, menu.slots.size());
    }

    @Override
    public boolean requireCompleteSets(KettleMenu menu, InfusionRecipe recipe) {
        return false;
    }
}
