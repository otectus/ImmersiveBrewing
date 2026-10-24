package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.immersive_brewing"))
            .icon(() -> new ItemStack(ModItems.BREWERS_KETTLE.get()))
            .displayItems((params, output) -> {
                output.accept(ModItems.APOTHECARY_MORTAR.get());
                output.accept(ModItems.BREWERS_KETTLE.get());
                output.accept(ModItems.ALEMBIC.get());
                output.accept(ModItems.WOODEN_BREWING_PADDLE.get());
                output.accept(ModItems.IRON_BREWING_PADDLE.get());
                output.accept(ModItems.BATCH_CARRIER.get());
                output.accept(ModItems.BREWERS_NOTEBOOK.get());
                output.accept(ModItems.SPENT_ALCHEMICAL_SALT.get());
            })
            .build());

    private ModCreativeTabs() {}
}
