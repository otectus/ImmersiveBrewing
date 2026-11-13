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
        modBus.addListener(com.otectus.immersivebrewing.client.workshop.WorkshopKeys::register);
        MinecraftForge.EVENT_BUS.addListener(com.otectus.immersivebrewing.client.workshop.ClientWorkshop::tick);
        MinecraftForge.EVENT_BUS.addListener(com.otectus.immersivebrewing.client.workshop.ClientWorkshop::onFov);
        MinecraftForge.EVENT_BUS.addListener(com.otectus.immersivebrewing.client.workshop.ClientWorkshop::renderOutlines);
        MinecraftForge.EVENT_BUS.addListener(com.otectus.immersivebrewing.client.workshop.WorkshopScreen::onOverlay);
        modBus.addListener((RegisterGuiOverlaysEvent event) -> StationNotices.register(event));
        modBus.addListener((RegisterGuiOverlaysEvent event) -> StationInspection.register(event));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.LevelEvent.Unload event) -> {
            if (event.getLevel().isClientSide()) clearPresentation();
        });
        modBus.addListener(ClientSetup::registerRenderers);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLogout);
        MinecraftForge.EVENT_BUS.addListener(Keys::tick);
        MinecraftForge.EVENT_BUS.addListener(com.otectus.immersivebrewing.client.sound.StationSoundManager::tick);
        modBus.addListener((net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> clearPresentation()));
        GuideHooks.installJournalHandler(request -> {
            var mc=Minecraft.getInstance();
            var key=com.otectus.immersivebrewing.recipe.PlanKey.parse(request.plan());
            var focused=com.otectus.immersivebrewing.client.workshop.ClientWorkshop.focus();
            if(mc.player!=null&&mc.player.containerMenu instanceof com.otectus.immersivebrewing.menu.KettleMenu menu&&key!=null) {
                com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.SelectPlanC2S(menu.pos(),key,request.servings()));
            } else if(focused!=null&&key!=null&&com.otectus.immersivebrewing.client.workshop.ClientWorkshop.kind()==com.otectus.immersivebrewing.workshop.WorkshopKind.KETTLE) {
                com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.SelectPlanC2S(focused,key,request.servings()));
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
            com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity.clientRemoved =
                    com.otectus.immersivebrewing.client.render.ApothecaryRackRenderer::remove;
            com.otectus.immersivebrewing.blockentity.StationBlockEntity.clientEffects = station -> {
                com.otectus.immersivebrewing.client.sound.StationSoundManager.observe(station);
                com.otectus.immersivebrewing.client.particle.StationParticles.tick(station);
            };
            com.otectus.immersivebrewing.blockentity.StationBlockEntity.clientRemoved = station -> {
                com.otectus.immersivebrewing.client.presentation.StationVisualCache.remove(station.getLevel().dimension().location().toString(), station.getBlockPos().asLong());
                com.otectus.immersivebrewing.client.sound.StationSoundManager.remove(station.getBlockPos());
                com.otectus.immersivebrewing.client.particle.StationParticles.remove(station.getBlockPos().asLong());
            };
            com.otectus.immersivebrewing.network.packet.StationVisualS2C.clientHandler = packet -> {
                var level = Minecraft.getInstance().level;
                if (level != null && level.hasChunkAt(packet.pos()) && level.getBlockEntity(packet.pos())
                        instanceof com.otectus.immersivebrewing.blockentity.StationBlockEntity station) station.acceptVisual(packet.visual());
            };
            com.otectus.immersivebrewing.network.packet.StationEffectEvent.clientHandler = effectEvent -> {
                com.otectus.immersivebrewing.client.sound.StationSoundManager.accepted(effectEvent);
                com.otectus.immersivebrewing.client.workshop.ClientWorkshop.onEffect(effectEvent);
            };
            com.otectus.immersivebrewing.network.packet.WorkshopFocusS2C.clientHandler = com.otectus.immersivebrewing.client.workshop.ClientWorkshop::onFocus;
            com.otectus.immersivebrewing.network.packet.WorkshopSnapshotS2C.clientHandler = com.otectus.immersivebrewing.client.workshop.ClientWorkshop::onSnapshot;
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
        com.otectus.immersivebrewing.client.workshop.ClientWorkshop.clear();
        ClientGuide.clear();
        BrewingSounds.stop();
        clearPresentation();
    }

    private static void clearPresentation() {
        com.otectus.immersivebrewing.client.sound.StationSoundManager.clear();
        KettleRenderer.clearPresentationCache();
        com.otectus.immersivebrewing.client.render.ApothecaryRackRenderer.clear();
        com.otectus.immersivebrewing.client.particle.StationParticles.clear();
    }

    private ClientSetup() {}
}
