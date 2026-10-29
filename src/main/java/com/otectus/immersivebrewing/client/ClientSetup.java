package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.client.render.AlembicRenderer;
import com.otectus.immersivebrewing.client.render.KettleRenderer;
import com.otectus.immersivebrewing.client.render.MortarRenderer;
import com.otectus.immersivebrewing.client.screen.AlembicScreen;
import com.otectus.immersivebrewing.client.screen.BatchCarrierScreen;
import com.otectus.immersivebrewing.client.screen.KettleScreen;
import com.otectus.immersivebrewing.client.screen.LabelScreen;
import com.otectus.immersivebrewing.client.screen.MortarScreen;
import com.otectus.immersivebrewing.client.screen.NotebookScreen;
import com.otectus.immersivebrewing.client.sound.BrewingSounds;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.guide.GuideHooks;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client wiring, reached from the mod constructor only through {@code DistExecutor}. */
public final class ClientSetup {

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::onClientSetup);
        modBus.addListener(Keys::register);
        modBus.addListener((RegisterGuiOverlaysEvent event) -> StationNotices.register(event));
        modBus.addListener(ClientSetup::registerRenderers);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLogout);
        MinecraftForge.EVENT_BUS.addListener(Keys::tick);
        GuideHooks.installJournalHandler(request -> {
            var mc=Minecraft.getInstance();
            var key=com.otectus.immersivebrewing.recipe.PlanKey.parse(request.plan());
            if(mc.player!=null&&mc.player.containerMenu instanceof com.otectus.immersivebrewing.menu.KettleMenu menu&&key!=null) {
                com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.SelectPlanC2S(menu.pos(),key,request.servings()));
            } else StationNotices.show(net.minecraft.network.chat.Component.translatable("staging.immersive_brewing.open_station"),true);
        });
        GuideHooks.install((otherHand, hand) -> {
            Minecraft mc = Minecraft.getInstance();
            if (BrewingMetadata.read(otherHand).isPresent()) mc.setScreen(new LabelScreen(otherHand, hand));
            else mc.setScreen(new NotebookScreen());
        });
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.KETTLE.get(), KettleScreen::new);
            MenuScreens.register(ModMenus.MORTAR.get(), MortarScreen::new);
            MenuScreens.register(ModMenus.ALEMBIC.get(), AlembicScreen::new);
            MenuScreens.register(ModMenus.BATCH_CARRIER.get(), BatchCarrierScreen::new);
            MenuScreens.register(ModMenus.APOTHECARY_RACK.get(), com.otectus.immersivebrewing.client.screen.ApothecaryRackScreen::new);
            com.otectus.immersivebrewing.blockentity.StationBlockEntity.clientEffects = com.otectus.immersivebrewing.client.particle.StationParticles::tick;
            net.minecraft.client.renderer.item.ItemProperties.register(com.otectus.immersivebrewing.registry.ModItems.BATCH_CARRIER.get(),
                    com.otectus.immersivebrewing.ImmersiveBrewing.id("strapped"),
                    (stack, level, entity, seed) -> com.otectus.immersivebrewing.item.BatchCarrierItem.strapped(stack) ? 1F : 0F);
        });
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BREWERS_KETTLE.get(), KettleRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.APOTHECARY_MORTAR.get(), MortarRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.ALEMBIC.get(), AlembicRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.APOTHECARY_RACK.get(), com.otectus.immersivebrewing.client.render.ApothecaryRackRenderer::new);
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BrewingDefinitions.resetClient();
        ClientPacketHandler.resetDefinitions();
        ClientSession.clear();
        ClientGuide.clear();
        BrewingSounds.stop();
    }

    private ClientSetup() {}
}
