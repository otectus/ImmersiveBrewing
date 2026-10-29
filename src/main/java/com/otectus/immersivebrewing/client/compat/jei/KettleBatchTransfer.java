package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.registry.ModMenus;
import net.minecraft.world.inventory.MenuType;
import java.util.Optional;

/** JEI enters the same server-resolved batch preview and atomic preparation flow as the native browser. */
final class KettleBatchTransfer implements mezz.jei.api.recipe.transfer.IRecipeTransferHandler<KettleMenu,InfusionRecipe> {
    private final mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper helper;
    KettleBatchTransfer(mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper helper){this.helper=helper;}
    public Class<? extends KettleMenu> getContainerClass(){return KettleMenu.class;}
    public Optional<MenuType<KettleMenu>> getMenuType(){return Optional.of(ModMenus.KETTLE.get());}
    public mezz.jei.api.recipe.RecipeType<InfusionRecipe> getRecipeType(){return BrewingCategory.TYPE;}
    public mezz.jei.api.recipe.transfer.IRecipeTransferError transferRecipe(KettleMenu menu,InfusionRecipe recipe,
            mezz.jei.api.gui.ingredient.IRecipeSlotsView slots,net.minecraft.world.entity.player.Player player,boolean max,boolean transfer) {
        if(menu.station()==null||menu.station().view().state()!=null) return helper.createUserErrorWithTooltip(net.minecraft.network.chat.Component.translatable("notice.immersive_brewing.busy"));
        if(transfer) com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.SelectRecipeC2S(menu.pos(),recipe.getId(),max?6:com.otectus.immersivebrewing.client.ClientGuide.preferredServings()));
        return null;
    }
}
