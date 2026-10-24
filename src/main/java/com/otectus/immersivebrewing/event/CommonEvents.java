package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDataLoader;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.recipe.BrewingTippedArrowRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Server lifecycle wiring on the Forge bus (spec 20.4): the session clock, every interruption that seals a session
 * (logout, death, dimension change, chunk unload, server stop, datapack reload), definition sync, the one-time
 * notebook grant and startup checks. Config changes ({@link ConfigEvents}, mod bus) only rebuild caches.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class CommonEvents {
    public static final ResourceLocation TIPPED_ARROW = new ResourceLocation("minecraft", "tipped_arrow");
    private static volatile boolean tippedArrowOverrideActive = true;

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BrewingDataLoader(event.getConditionContext()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) SessionManager.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        checkTippedArrowOverride(event.getServer());
        if (BrewingStandBlockEntity.FUEL_USES != ServerConfig.fuelChargesPerBlazePowder()) {
            ImmersiveBrewing.LOGGER.info("Immersive Brewing: the brewing stand gives {} brews per blaze powder; the burner is configured for {} charges",
                    BrewingStandBlockEntity.FUEL_USES, ServerConfig.fuelChargesPerBlazePowder());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SessionManager.sealAll(event.getServer(), "session.immersive_brewing.end.server_stopping");
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SessionManager.clear();
        PlanIndex.invalidate();
        BrewingDataLoader.reset();
    }

    /**
     * Fires for one joining player, or for everyone (player null) after {@code /reload}. A reload seals every running
     * session with its old snapshot first (spec 16.6), then the new dataset is synced.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        if (event.getPlayer() == null) {
            SessionManager.sealAll(server, "session.immersive_brewing.end.reload");
            PlanIndex.invalidate();
            checkTippedArrowOverride(server);
            for (ServerPlayer p : event.getPlayerList().getPlayers()) ModNetwork.syncDefinitions(p, BrewingDefinitions.server());
        } else {
            ModNetwork.syncDefinitions(event.getPlayer(), BrewingDefinitions.server());
            PlayerGuide.sync(event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SessionManager.sealPlayer(player, "session.immersive_brewing.end.disconnected");
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SessionManager.sealPlayer(player, "session.immersive_brewing.end.died");
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SessionManager.sealPlayer(player, "session.immersive_brewing.end.dimension");
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SessionManager.sealChunk(level, event.getChunk().getPos());
    }

    /** The Notebook arrives once, the first time a Mortar or Kettle reaches the player's inventory (spec 25.1). */
    @SubscribeEvent
    public static void onPickup(PlayerEvent.ItemPickupEvent event) {
        grantIfStation(event.getEntity(), event.getStack());
    }

    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        grantIfStation(event.getEntity(), event.getCrafting());
    }

    private static void grantIfStation(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (stack.is(ModItems.APOTHECARY_MORTAR.get()) || stack.is(ModItems.BREWERS_KETTLE.get())) PlayerGuide.grantNotebookOnce(sp);
    }

    /**
     * Tipped arrows keep brewing metadata only through this mod's override of {@code minecraft:tipped_arrow}. When
     * another pack replaced it, arrows crafted from graded potions are ordinary; say so once in the log and the report.
     */
    public static void checkTippedArrowOverride(MinecraftServer server) {
        Optional<? extends Recipe<?>> recipe = server.getRecipeManager().byKey(TIPPED_ARROW);
        tippedArrowOverrideActive = recipe.isPresent() && recipe.get() instanceof BrewingTippedArrowRecipe;
        if (!tippedArrowOverrideActive) {
            ImmersiveBrewing.LOGGER.warn("Immersive Brewing: {} is not this mod's recipe ({}); tipped arrows crafted from graded potions will not keep their quality",
                    TIPPED_ARROW, recipe.map(r -> r.getClass().getName()).orElse("missing"));
        }
    }

    public static boolean tippedArrowOverrideActive() {
        return tippedArrowOverrideActive;
    }

    private CommonEvents() {}
}
