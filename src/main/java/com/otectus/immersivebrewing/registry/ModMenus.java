package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.menu.AlembicMenu;
import com.otectus.immersivebrewing.menu.BatchCarrierMenu;
import com.otectus.immersivebrewing.menu.ApothecaryRackMenu;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.menu.MortarMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<MenuType<MortarMenu>> MORTAR = MENUS.register("apothecary_mortar",
            () -> IForgeMenuType.create(MortarMenu::fromNetwork));
    public static final RegistryObject<MenuType<ApothecaryRackMenu>> APOTHECARY_RACK = MENUS.register("apothecary_rack",
            () -> IForgeMenuType.create(ApothecaryRackMenu::fromNetwork));
    public static final RegistryObject<MenuType<KettleMenu>> KETTLE = MENUS.register("brewers_kettle",
            () -> IForgeMenuType.create(KettleMenu::fromNetwork));
    public static final RegistryObject<MenuType<AlembicMenu>> ALEMBIC = MENUS.register("alembic",
            () -> IForgeMenuType.create(AlembicMenu::fromNetwork));
    public static final RegistryObject<MenuType<BatchCarrierMenu>> BATCH_CARRIER = MENUS.register("batch_carrier",
            () -> IForgeMenuType.create(BatchCarrierMenu::fromNetwork));

    private ModMenus() {}
}
