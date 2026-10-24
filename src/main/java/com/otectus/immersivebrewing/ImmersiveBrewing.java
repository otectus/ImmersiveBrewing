package com.otectus.immersivebrewing;

import com.mojang.logging.LogUtils;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.config.CommonConfig;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.advancement.ModAdvancements;
import com.otectus.immersivebrewing.api.ImmersiveBrewingAPI;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModCreativeTabs;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModMenus;
import com.otectus.immersivebrewing.registry.ModRecipeSerializers;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import com.otectus.immersivebrewing.registry.ModSounds;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import com.otectus.immersivebrewing.client.ClientSetup;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ImmersiveBrewing.MOD_ID)
public class ImmersiveBrewing {
    public static final String MOD_ID = "immersive_brewing";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ImmersiveBrewing() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModRecipeTypes.RECIPE_TYPES.register(modBus);
        ModRecipeSerializers.RECIPE_SERIALIZERS.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modBus.addListener(com.otectus.immersivebrewing.recipe.ModConditions::onRegister);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::enqueueIMC);
        modBus.addListener(this::processIMC);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSetup.init(modBus));
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModNetwork.register();
            ModAdvancements.register();
        });
    }

    /** Optional sister-mod hooks, each behind a presence check so its classes load only when the mod is there (spec 17). */
    private void enqueueIMC(final net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent event) {
        if (net.minecraftforge.fml.ModList.get().isLoaded(com.otectus.immersivebrewing.integration.smithing.SmithingIntegration.MOD_ID)) {
            com.otectus.immersivebrewing.integration.smithing.SmithingIntegration.enqueueIMC();
        }
    }

    private void processIMC(final InterModProcessEvent event) {
        ImmersiveBrewingAPI.processIMC(event);
        AdapterRegistry.freeze();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
