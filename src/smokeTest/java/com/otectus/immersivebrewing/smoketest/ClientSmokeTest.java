package com.otectus.immersivebrewing.smoketest;

import com.google.gson.GsonBuilder;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.client.screen.GameScreen;
import com.otectus.immersivebrewing.client.screen.KettleBrewScreen;
import com.otectus.immersivebrewing.client.screen.KettlePlanScreen;
import com.otectus.immersivebrewing.client.screen.KettleScreen;
import com.otectus.immersivebrewing.client.screen.NotebookScreen;
import com.otectus.immersivebrewing.client.screen.ResultScreen;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopScreen;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.StartBrewC2S;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Drives a dev client from the title screen into a fresh flat world and walks the whole hands-on workshop through the
 * real client and server code (handoff 21): opening each station's working view with an ordinary use, preparing two
 * sugar kits at the Mortar with the pestle and scraper, setting out a three-serving Swiftness batch from the route book
 * and tending it at the Kettle (pouring, burner, doses, paddle, rest), sealing it with the lid halfway and reopening it,
 * bottling from the spout, drinking one potion and comparing its applied duration with the tooltip, filling the
 * Alembic's coolant from a bucket and purifying, a client-only practice that changes nothing real, container finishing
 * at the fitting, and the migration-only path that finishes a batch saved by 1.1 through its original interface. Every
 * apparatus action goes through {@link WorkshopDriver} and the real intent packets. Saves screenshots to
 * {@code run/screenshots/smoketest_*.png}, writes {@code smoketest-result.json}, quits.
 */
@Mod.EventBusSubscriber(modid = SmokeTestAgent.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final String OUTPUT = System.getProperty(SmokeTestAgent.OUTPUT_PROPERTY);
    private static final String GUI_SCALE = System.getProperty("immersive_brewing.smoketest.guiScale");
    /**
     * Trial variant: {@code keyboard} plays every game through the key bindings only; {@code accessible} (high contrast,
     * reduced motion, numbers, large markers, master volume 0) and {@code colorblind} change only the presentation.
     * Without a variant every one of those options is reset to its default.
     */
    private static final String VARIANT = System.getProperty("immersive_brewing.smoketest.variant");
    private static final boolean KEYBOARD = "keyboard".equals(VARIANT);
    private static final KeyboardDriver DRIVER = new KeyboardDriver();
    private static final WorkshopDriver WORKSHOP = new WorkshopDriver(KEYBOARD);
    private static final java.util.concurrent.atomic.AtomicReference<net.minecraft.nbt.CompoundTag> SEALED_PROCESS = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<String> PRACTICE_BEFORE = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Window size as {@code WxH}, default 1280x960: GUI scales 1 to 4 then give 1280x960 down to exactly 320x240, the
     * smallest GUI Minecraft allows. A tiling window manager may override it (tools/run_smoke_matrix.sh floats the window).
     */
    private static final String WINDOW = System.getProperty("immersive_brewing.smoketest.window");
    // Written from the client and the integrated server thread (server actions record their checks).
    private static final Map<String, Object> checks = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private static final long START = Util.getMillis();
    private static final int STEP_TIMEOUT = 20 * 120;
    private static final FrameSampler DENSE_FRAMES = new FrameSampler();
    private static volatile long denseUntilMillis;
    private static final FrameSampler DENSE_INTERVALS = new FrameSampler(), DENSE_SERVER = new FrameSampler();
    private static long previousDenseFrame;
    private static long denseBytes, allocationStart;
    private static int denseParticles, denseEmissions, denseVoices, denseCache;
    private static final com.sun.management.ThreadMXBean ALLOCATIONS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static boolean denseMeasuring() { return denseUntilMillis != 0 && denseUntilMillis != Long.MAX_VALUE; }

    private record Step(String name, BooleanSupplier ready, Runnable action) {}

    private static final List<Step> steps = new ArrayList<>();
    private static int stepIndex;
    private static int stepTicks;
    private static int titleTicks;
    private static int worldTicks;
    private static boolean worldRequested;
    private static boolean finished;
    private static BlockPos base;
    private static PlanListS2C.Entry chosen;
    private static PlanListS2C reviewPlans;
    private static ClientSession reviewResult;
    private static java.util.concurrent.CompletableFuture<Void> languageReload;
    /** A requested screenshot, taken after the next rendered frames so it shows the screen that was just opened. */
    private static String pendingShot;
    private static int pendingFrames;
    private static int pendingTicks;

    private static BlockPos mortarPos() {
        return base.offset(-2, 0, 0);
    }

    private static BlockPos kettlePos() {
        return base;
    }

    private static BlockPos alembicPos() {
        return base.offset(2, 0, 0);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (OUTPUT == null || finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.player == null || mc.level == null) {
                if (mc.screen instanceof TitleScreen && ++titleTicks == 40 && !worldRequested) {
                    worldRequested = true;
                    applyVariant(mc);
                    int[] size = windowSize();
                    mc.getWindow().setWindowed(size[0], size[1]);
                    checks.put("window", size[0] + "x" + size[1]);
                    // Matrix runs pick their scale; every other run uses 2 (GUI 640x480 in the default window), never a
                    // scale left in options.txt by an earlier run.
                    mc.options.guiScale().set(GUI_SCALE != null ? Integer.parseInt(GUI_SCALE) : 2);
                    mc.resizeDisplay();
                    checks.put("title_ms", Util.getMillis() - START);
                    createWorld(mc);
                }
                return;
            }
            if (++worldTicks < 60) return;
            if (GUI_SCALE != null) {
                int[] size = windowSize();
                if (mc.getWindow().getWidth() != size[0] || mc.getWindow().getHeight() != size[1]) {
                    mc.getWindow().setWindowed(size[0], size[1]);
                    return; // Let the real framebuffer resize before checking/capturing the minimum GUI.
                }
            }
            if (!checks.containsKey("framebuffer")) {
                checks.put("framebuffer", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                checks.put("gui_scaled", mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
            }
            if (pendingShot != null) {
                // Hold every step until the frame is captured, so no screen changes underneath it.
                if (++pendingTicks > 100) finish(mc, false, "screenshot never rendered: " + pendingShot);
                return;
            }
            if (KEYBOARD && mc.screen instanceof GameScreen game && ClientSession.active() != null && !ClientSession.active().ended()) {
                if (!mc.isWindowActive()) {
                    checks.merge("keyboard_unfocused_ticks", 1, (a, b) -> (Integer) a + (Integer) b);
                    DRIVER.paused();
                    org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
                }
                if (ClientSession.active().paused()) DRIVER.paused();
                else if (!ClientSession.active().countingIn()) DRIVER.tick(game, ClientSession.active().sim());
            }
            if (KEYBOARD && mc.screen instanceof WorkshopScreen && WORKSHOP.enabled() && !mc.isWindowActive()) {
                // The working view drops held work on focus loss (a swallowed key release must not stick): refocus.
                checks.merge("keyboard_unfocused_ticks", 1, (a, b) -> (Integer) a + (Integer) b);
                org.lwjgl.glfw.GLFW.glfwFocusWindow(mc.getWindow().getWindow());
            }
            WORKSHOP.tick(mc);
            if (steps.isEmpty()) plan(mc);
            if (stepIndex >= steps.size()) {
                finish(mc, allPassed(), "complete");
                return;
            }
            Step step = steps.get(stepIndex);
            if (step.ready().getAsBoolean()) {
                log("step " + step.name());
                step.action().run();
                stepIndex++;
                stepTicks = 0;
            } else if (++stepTicks > STEP_TIMEOUT) {
                checks.put("timeout_" + step.name(), true);
                finish(mc, false, "timed out waiting for " + step.name());
            }
        } catch (Exception e) {
            ImmersiveBrewing.LOGGER.error("SMOKETEST failed", e);
            checks.put("agent_exception", e.toString());
            finish(mc, false, "agent exception");
        }
    }

    @SubscribeEvent
    public static void denseFrame(RenderLevelStageEvent event) {
        if (!"dense".equals(VARIANT) || !denseMeasuring()) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) DENSE_FRAMES.begin(System.nanoTime());
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) DENSE_FRAMES.end(System.nanoTime());
    }

    @SubscribeEvent
    public static void denseFrameInterval(TickEvent.RenderTickEvent event) {
        if (!"dense".equals(VARIANT) || !denseMeasuring() || event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        if (previousDenseFrame != 0) { DENSE_INTERVALS.begin(previousDenseFrame); DENSE_INTERVALS.end(now); }
        previousDenseFrame = now;
        denseParticles = Math.max(denseParticles, com.otectus.immersivebrewing.client.particle.StationParticles.liveCount());
        denseEmissions = Math.max(denseEmissions, com.otectus.immersivebrewing.client.particle.StationParticles.emittedThisSecond());
        denseVoices = Math.max(denseVoices, com.otectus.immersivebrewing.client.sound.StationSoundManager.loopVoices());
        denseCache = Math.max(denseCache, com.otectus.immersivebrewing.client.presentation.StationVisualCache.size());
    }

    @SubscribeEvent
    public static void denseServerTick(TickEvent.ServerTickEvent event) {
        if (!"dense".equals(VARIANT) || !denseMeasuring()) return;
        if (event.phase == TickEvent.Phase.START) DENSE_SERVER.begin(System.nanoTime());
        else DENSE_SERVER.end(System.nanoTime());
    }

    private static void plan(Minecraft mc) {
        checks.put("world_ms", Util.getMillis() - START);
        checks.put("client_reagent_profiles", BrewingDefinitions.client().reagentProfiles().size());
        checks.put("client_definitions_synced", !BrewingDefinitions.client().reagentProfiles().isEmpty());
        base = mc.player.blockPosition().offset(0, 0, -3);
        server(mc, (level, player) -> {
            // The connection teleport carries the rotation to the client camera; setYRot on the server does not.
            player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180F, 30F);
            for (BlockPos p : List.of(mortarPos(), kettlePos(), alembicPos())) level.setBlock(p.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(mortarPos(), ModBlocks.APOTHECARY_MORTAR.get().defaultBlockState().setValue(StationBlock.FACING, Direction.SOUTH), 3);
            level.setBlock(kettlePos(), ModBlocks.BREWERS_KETTLE.get().defaultBlockState().setValue(StationBlock.FACING, Direction.SOUTH), 3);
            level.setBlock(alembicPos(), ModBlocks.ALEMBIC.get().defaultBlockState().setValue(StationBlock.FACING, Direction.SOUTH), 3);
            ApothecaryMortarBlockEntity mortar = (ApothecaryMortarBlockEntity) level.getBlockEntity(mortarPos());
            mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.SUGAR, 2));
            BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
            for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY, new ItemStack(Items.NETHER_WART));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
            AlembicBlockEntity alembic = (AlembicBlockEntity) level.getBlockEntity(alembicPos());
            for (int i = 0; i < 3; i++) alembic.inventory().setStackInSlot(AlembicBlockEntity.INPUT + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH));
            alembic.inventory().setStackInSlot(AlembicBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER));
        });

        // ---- JEI: every category received recipes from the synced data ----
        add("jei_ready", () -> !com.otectus.immersivebrewing.client.compat.jei.JeiStatus.counts().isEmpty(), () -> {
            var counts = com.otectus.immersivebrewing.client.compat.jei.JeiStatus.counts();
            checks.put("jei_counts", counts);
            checks.put("jei_all_categories_filled", counts.size() == 4 && counts.values().stream().allMatch(n -> n > 0));
        });

        probe(mc, "start");
        // ---- Mortar: an ordinary use opens the working view; the pestle and scraper do the work ----
        add("world_view", ticks(20), () -> shot(mc, "world_stations"));
        add("open_mortar", always(), () -> {
            // First use: tutorial progress is a saved client preference, and an interrupted earlier run may have saved some.
            ClientConfig.TUTORIAL_STAGE.set(0);
            use(mc, mortarPos());
        });
        // The view opens at once; the station's first snapshot (what is in the bowl) follows within a tick or two.
        add("mortar_view", () -> mc.screen instanceof WorkshopScreen && workshop(mc, mortarPos()).getBoolean("preparable"), () -> {
            checks.put("mortar_use_opens_working_view", true);
            checks.put("mortar_no_minigame", !(mc.screen instanceof GameScreen));
            checks.put("tutorial_points_at_tool", tutorialPrompt(mc) != null);
            checks.put("tutorial_state", "stage " + ClientConfig.get(ClientConfig.TUTORIAL_STAGE) + " show " + ClientConfig.get(ClientConfig.SHOW_TUTORIAL)
                    + " kind " + ClientWorkshop.kind() + " tool " + ClientWorkshop.tool() + " preparable "
                    + workshop(mc, mortarPos()).getBoolean("preparable") + " running " + workshop(mc, mortarPos()).contains("state"));
            shot(mc, "mortar_workshop");
            WORKSHOP.enable();
        });
        add("mortar_working", () -> workshop(mc, mortarPos()).contains("mortar"), () -> checks.put("mortar_contact_begins", true));
        add("mortar_worked", ticks(60), () -> {
            checks.put("mortar_material_changes", workshop(mc, mortarPos()).getCompound("mortar").getByte("processed") > 0);
            shot(mc, "mortar_working");
        });
        add("mortar_packaged", () -> kitsOnShelf(mc) > 0, () -> {
            WORKSHOP.disable();
            int extraction = kitExtraction(mc);
            checks.put("preparation_extraction", extraction);
            checks.put("mortar_presses", WORKSHOP.presses());
            if (KEYBOARD) checks.put("keyboard_preparation_fine", extraction >= 70);
            shot(mc, "mortar_packaged");
        });
        add("mortar_leave", ticks(10), () -> {
            if (mc.screen != null) mc.screen.onClose();
        });
        add("kits_made", ticks(10), () -> server(mc, (level, player) -> {
            ApothecaryMortarBlockEntity mortar = (ApothecaryMortarBlockEntity) level.getBlockEntity(mortarPos());
            ItemStack kits = ItemStack.EMPTY;
            for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
                ItemStack s = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
                if (!s.isEmpty()) {
                    kits = s.copy();
                    mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i, ItemStack.EMPTY);
                }
            }
            checks.put("kits_made", kits.getCount() == 2 && KitData.read(kits).isPresent());
            BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY + 1, kits.copyWithCount(1));
            player.getInventory().add(kits.copyWithCount(Math.max(0, kits.getCount() - 1)));
        }));

        // ---- Kettle: plan, brew, seal halfway, resume ----
        add("open_kettle", ticks(10), () -> openMenu(mc, kettlePos()));
        add("kettle_menu", () -> mc.screen instanceof KettleScreen, () -> shot(mc, "kettle_menu"));
        add("request_plans", ticks(10), () -> ModNetwork.sendToServer(new StationActionC2S(kettlePos(), StationActionC2S.Action.REQUEST_PLANS, 0)));
        add("plan_screen", () -> mc.screen instanceof KettlePlanScreen, () -> shot(mc, "kettle_plans"));
        add("plan_long_query", ticks(3), () -> ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.CatalogQueryC2S(
                kettlePos(), 1, "long_swiftness", List.of(), 0, 0, false, 3)));
        add("plan_long", () -> mc.screen instanceof KettlePlanScreen p && p.plans().query().equals("long_swiftness")
                && p.plans().entries().stream().anyMatch(e -> e.output().is(Items.LINGERING_POTION)), () -> {
            reviewPlans = ((KettlePlanScreen) mc.screen).plans();
            checks.put("plans_long_steps", VisualChecks.longestPlan((KettlePlanScreen) mc.screen));
            if (mc.screen.width < 480 || mc.screen.height < 300) VisualChecks.click(mc.screen, "screen.immersive_brewing.plan.show_route");
        });
        add("plan_long_shot", ticks(3), () -> shot(mc, "plans_long"));
        add("plan_long_scroll", ticks(3), () -> VisualChecks.scrollEnd(mc.screen, "detail", checks, "plans_long", true));
        add("plan_long_bottom", ticks(3), () -> shot(mc, "plans_long_bottom"));
        add("plan_loaded_query", ticks(3), () -> ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.CatalogQueryC2S(
                kettlePos(), 2, "minecraft:swiftness", List.of(), 1, 0, false, 3)));
        add("start_brew", () -> mc.screen instanceof KettlePlanScreen p && p.plans().request() == 2 && PlanAccess.swiftness(p) != null, () -> {
            KettlePlanScreen screen = (KettlePlanScreen) mc.screen;
            chosen = PlanAccess.swiftness(screen);
            checks.put("plan_found", chosen != null);
            checks.put("plan_uses_kit", chosen != null && chosen.costs().stream().anyMatch(c -> c.kitGroups() > 0));
            checks.put("plan_fuel_charges", chosen == null ? -1 : chosen.fuelNeeded());
            if (chosen != null) ModNetwork.sendToServer(new StartBrewC2S(kettlePos(), chosen.key(), 1F, 1F));
        });
        add("brew_view", () -> mc.screen instanceof WorkshopScreen && workshop(mc, kettlePos()).contains("kettle"), () -> {
            checks.put("set_out_opens_working_view", true);
            checks.put("kettle_no_minigame", !(mc.screen instanceof GameScreen));
            shot(mc, "kettle_set_out");
            WORKSHOP.enable();
        });
        add("brew_charged", () -> stage(mc, kettlePos()) >= com.otectus.immersivebrewing.workshop.KettleProcess.Stage.HEATING.ordinal(), () -> {
            checks.put("bottles_poured", true);
            shot(mc, "kettle_charged");
        });
        add("brew_reacting", () -> stage(mc, kettlePos()) == com.otectus.immersivebrewing.workshop.KettleProcess.Stage.REACTING.ordinal()
                && ClientWorkshop.tool() == com.otectus.immersivebrewing.workshop.WorkshopParts.K_PADDLE, () -> {
            checks.put("dose_tipped", workshop(mc, kettlePos()).getCompound("kettle").getByte("doses") >= 1);
            checks.put("paddle_in_hand", true);
        });
        add("brew_stirring", ticks(30), () -> {
            var held = workshop(mc, kettlePos()).getList("held", net.minecraft.nbt.Tag.TAG_COMPOUND);
            checks.put("server_paddle_lease_synced", held.stream().anyMatch(t -> ((net.minecraft.nbt.CompoundTag) t).getByte("part")
                    == com.otectus.immersivebrewing.workshop.WorkshopParts.K_PADDLE));
            checks.put("liquid_circulates", workshop(mc, kettlePos()).getCompound("kettle").getByte("circ") > 0);
            shot(mc, "kettle_stirring");
        });
        add("seal_halfway", ticks(40), () -> {
            WORKSHOP.disable();
            ClientWorkshop.send(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.SEAL, 0, false);
        });
        add("sealed_state", () -> view(mc, kettlePos()) != null && view(mc, kettlePos()).view().state() == JobState.SEALED, () -> {
            checks.put("sealed_batch_visible", true);
            server(mc, (level, player) -> SEALED_PROCESS.set(((BrewersKettleBlockEntity) level.getBlockEntity(kettlePos())).liveProcess().save()));
            shot(mc, "kettle_sealed");
        });
        add("sealed_still", ticks(40), () -> server(mc, (level, player) -> checks.put("sealed_batch_frozen",
                SEALED_PROCESS.get() != null && SEALED_PROCESS.get().equals(((BrewersKettleBlockEntity) level.getBlockEntity(kettlePos())).liveProcess().save()))));
        add("unseal", ticks(2), () -> {
            ClientWorkshop.send(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.UNSEAL, 0, false);
            WORKSHOP.enable();
        });
        add("unsealed", () -> view(mc, kettlePos()) != null && view(mc, kettlePos()).view().state() == JobState.RUNNING, () -> checks.put("resumed", true));
        add("brew_ready", () -> stage(mc, kettlePos()) == com.otectus.immersivebrewing.workshop.KettleProcess.Stage.READY.ordinal(),
                () -> shot(mc, "kettle_ready"));
        add("brew_bottled", () -> !workshop(mc, kettlePos()).contains("state") && rackFinished(mc, kettlePos()) == 3, () -> {
            WORKSHOP.disable();
            shot(mc, "kettle_bottled");
            if (KEYBOARD) checks.put("keyboard_key_presses", WORKSHOP.keyPresses());
            server(mc, (level, player) -> {
                var kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
                var result = kettle.lastResult();
                checks.put("brew_quality", result == null ? -1 : result.quality());
                checks.put("brew_extraction", result == null ? -1 : result.extraction());
                checks.put("brew_control", result == null ? -1 : result.control());
                checks.put("brew_tip", result == null ? "" : result.tipKey());
                if (KEYBOARD) checks.put("keyboard_brew_fine", result != null && result.quality() >= 70);
                checks.put("journal_recorded", com.otectus.immersivebrewing.guide.PlayerGuide.data(player).getList("history", 10).size() >= 1);
            });
        });
        add("brew_leave", ticks(5), () -> {
            if (mc.screen != null) mc.screen.onClose();
        });
        add("notice_history", ticks(5), () -> mc.setScreen(new com.otectus.immersivebrewing.client.screen.NoticeHistoryScreen(null)));
        add("notice_history_shot", ticks(5), () -> shot(mc, "notice_history"));
        add("notice_history_close", ticks(3), () -> mc.setScreen(null));
        add("brew_outputs", ticks(10), () -> server(mc, (level, player) -> {
            BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
            int graded = 0;
            for (int i = 0; i < 3; i++) {
                ItemStack s = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
                if (PotionUtils.getPotion(s) == Potions.SWIFTNESS && BrewingMetadata.read(s).isPresent()) graded++;
            }
            checks.put("three_graded_swiftness", graded == 3);
            checks.put("fuel_after_brew", kettle.fuelCharges());
            checks.put("fuel_charged_two", kettle.fuelCharges() == 18);
            checks.put("no_extra_bottle", kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + 3).isEmpty());
        }));
        add("world_finished", ticks(10), () -> shot(mc, "world_finished"));

        probe(mc, "after_kettle");
        // ---- Drink one: applied duration equals the tooltip's ----
        add("drink", ticks(10), () -> {
            BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) mc.level.getBlockEntity(kettlePos());
            ItemStack potion = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK).copy();
            MobEffectInstance effective = PotionContentsService.effectiveEffects(potion, BrewingDefinitions.client()).get(0);
            String expected = MobEffectUtil.formatDuration(effective, 1.0F).getString();
            List<Component> tooltip = potion.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL);
            boolean shown = tooltip.stream().anyMatch(c -> c.getString().contains(expected));
            checks.put("tooltip_duration", expected);
            checks.put("tooltip_shows_effective_duration", shown);
            mc.setScreen(new TooltipSheet(potion));
            server(mc, (level, player) -> {
                BrewersKettleBlockEntity serverKettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
                ItemStack drink = serverKettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK).copy();
                serverKettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK, ItemStack.EMPTY);
                player.removeEffect(MobEffects.MOVEMENT_SPEED);
                Items.POTION.finishUsingItem(drink, level, player);
                MobEffectInstance applied = player.getEffect(MobEffects.MOVEMENT_SPEED);
                checks.put("applied_duration_ticks", applied == null ? -1 : applied.getDuration());
                checks.put("applied_equals_resolved", applied != null && applied.getDuration() == effective.getDuration());
            });
        });
        add("tooltip_shot", ticks(10), () -> shot(mc, "tooltip"));
        add("tooltip_close", ticks(5), () -> mc.setScreen(null));

        // ---- Alembic: a bucket fills the coolant jacket; the clamp, valves and diverter do the work ----
        add("coolant_bucket", ticks(10), () -> server(mc, (level, player) -> {
            player.removeAllEffects(); // the Swiftness just drunk would swirl particles in front of the camera
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        }));
        add("coolant_fill", () -> mc.player.getMainHandItem().is(Items.WATER_BUCKET), () -> use(mc, alembicPos()));
        // The smoke world is creative: as in vanilla, the bucket stays full in hand; the jacket's synced level is the proof.
        add("coolant_filled", () -> workshop(mc, alembicPos()).getInt("coolantMb") >= 1000, () -> server(mc, (level, player) -> {
            checks.put("coolant_from_bucket", ((AlembicBlockEntity) level.getBlockEntity(alembicPos())).coolantMb() == 1000);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }));
        add("open_alembic", () -> mc.player.getMainHandItem().isEmpty(), () -> use(mc, alembicPos()));
        add("alembic_view", () -> mc.screen instanceof WorkshopScreen, () -> {
            checks.put("charge_preview_shown", workshop(mc, alembicPos()).contains("previewOut"));
            shot(mc, "alembic_workshop");
            ClientWorkshop.send(com.otectus.immersivebrewing.workshop.WorkshopParts.A_CHARGE, com.otectus.immersivebrewing.workshop.WorkshopAction.FINISH, 0, false);
        });
        add("alembic_charged", () -> workshop(mc, alembicPos()).contains("alembic"), () -> WORKSHOP.enable());
        add("alembic_collecting", () -> workshop(mc, alembicPos()).getCompound("alembic").getBoolean("receiver"), () -> {
            checks.put("diverter_to_receiver", true);
            shot(mc, "alembic_collecting");
        });
        add("alembic_done", () -> !workshop(mc, alembicPos()).contains("state") && outputs(mc, alembicPos()) > 0, () -> {
            WORKSHOP.disable();
            shot(mc, "alembic_done");
            server(mc, (level, player) -> {
                var result = ((AlembicBlockEntity) level.getBlockEntity(alembicPos())).lastResult();
                checks.put("distillation_score", result == null ? -1 : result.control());
                checks.put("purified_quality", result == null ? -1 : result.quality());
                if (KEYBOARD) checks.put("keyboard_distillation_good", result != null && result.control() >= 70);
            });
        });
        add("alembic_leave", ticks(5), () -> {
            if (mc.screen != null) mc.screen.onClose();
        });
        add("purified_outputs", ticks(10), () -> server(mc, (level, player) -> {
            AlembicBlockEntity alembic = (AlembicBlockEntity) level.getBlockEntity(alembicPos());
            int potions = 0;
            int bottles = 0;
            for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) {
                ItemStack s = alembic.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i);
                if (s.is(Items.POTION)) potions += s.getCount();
                if (s.is(Items.GLASS_BOTTLE)) bottles += s.getCount();
            }
            checks.put("purify_three_to_two", potions == 2 && bottles == 1);
        }));

        // ---- Notebook ----
        add("notebook", ticks(10), () -> {
            // Getting started draws the Kettle's crafting grid from the recipe the client received.
            checks.put("notebook_kettle_recipe", mc.level.getRecipeManager().byKey(ImmersiveBrewing.id("brewers_kettle")).isPresent());
            mc.setScreen(new NotebookScreen());
        });
        add("notebook_shot", ticks(10), () -> shot(mc, "notebook"));
        add("notebook_close", ticks(5), () -> mc.setScreen(null));

        // ---- Practice: client-only at the idle kettle with the same tools; nothing real changes ----
        add("practice_snapshot", ticks(5), () -> server(mc, (level, player) -> PRACTICE_BEFORE.set(ownership(level, player))));
        add("practice_request", () -> PRACTICE_BEFORE.get() != null, () -> com.otectus.immersivebrewing.client.ClientPacketHandler.practice(SessionKind.BREWING));
        add("practice_open", ticks(10), () -> use(mc, kettlePos()));
        add("practice_running", () -> com.otectus.immersivebrewing.client.workshop.PracticeBench.active(kettlePos()), () -> {
            checks.put("practice_starts_at_idle_kettle", true);
            WORKSHOP.enable();
        });
        add("practice_working", ticks(80), () -> shot(mc, "practice_kettle"));
        add("practice_done", () -> !com.otectus.immersivebrewing.client.workshop.PracticeBench.any(), () -> {
            WORKSHOP.disable();
            server(mc, (level, player) -> checks.put("practice_changes_nothing", ownership(level, player).equals(PRACTICE_BEFORE.get())));
            shot(mc, "practice_done");
        });
        add("practice_leave", ticks(5), () -> {
            if (mc.screen != null) mc.screen.onClose();
        });

        // ---- Migration only: a batch saved by 1.1 finishes through its original interface ----
        BlockPos legacyPos = base.offset(-4, 0, 0);
        add("legacy_setup", ticks(5), () -> server(mc, (level, player) -> {
            level.setBlock(legacyPos.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(legacyPos, ModBlocks.BREWERS_KETTLE.get().defaultBlockState().setValue(StationBlock.FACING, Direction.SOUTH), 3);
            var kettle = (BrewersKettleBlockEntity) level.getBlockEntity(legacyPos);
            for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY, new ItemStack(Items.NETHER_WART));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY + 1, new ItemStack(Items.SUGAR));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
            ItemStack swiftness = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
            var plan = com.otectus.immersivebrewing.recipe.PlanIndex.get(level.getServer().getRecipeManager()).plansFrom(kettle.rack().input()).stream()
                    .filter(candidate -> ItemStack.isSameItemSameTags(candidate.output(), swiftness))
                    .min(java.util.Comparator.comparingInt(candidate -> candidate.steps().size())).orElseThrow();
            var ledger = com.otectus.immersivebrewing.testing.LegacyBatches.brew(kettle, player, plan, 40);
            kettle.load(com.otectus.immersivebrewing.testing.LegacyBatches.withLedger(kettle, ledger));
            kettle.setChanged();
            level.sendBlockUpdated(legacyPos, kettle.getBlockState(), kettle.getBlockState(), 3);
            checks.put("legacy_batch_loaded", kettle.ledger() != null && kettle.ledger().legacy());
        }));
        // A 1.1 batch loaded into a live station refreshes its presentation inside the next chunk broadcast; block
        // changes nearby must keep reaching the client (WorkshopRegressionGameTests.buildingAnUpdatePacketNeverMarksTheChunk).
        probe(mc, "after_legacy_load");
        add("legacy_open", () -> checks.containsKey("legacy_batch_loaded"), () -> use(mc, legacyPos));
        add("legacy_view", () -> mc.screen instanceof WorkshopScreen && workshop(mc, legacyPos).getBoolean("legacy"), () -> {
            shot(mc, "legacy_workshop");
            ClientWorkshop.send(com.otectus.immersivebrewing.workshop.WorkshopParts.K_BOOK, com.otectus.immersivebrewing.workshop.WorkshopAction.LEGACY_RESUME, 0, false);
        });
        add("legacy_screen", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null, () -> {
            checks.put("legacy_resume_opens_original_interface", ClientSession.active().resumed);
            closeHelp(mc);
        });
        add("legacy_running", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {
            KettleBot bot = KettleBot.competent();
            scripted(sim -> bot.decide(((SimHandles.Brewing) sim).sim()).pack());
        });
        add("legacy_result", () -> mc.screen instanceof ResultScreen, () -> {
            ClientSession.scriptedInput = null;
            reviewResult = ClientSession.active();
            checks.put("legacy_batch_finished", reviewResult != null && reviewResult.result() != null && reviewResult.result().getInt("Q") > 0);
            shot(mc, "legacy_result");
        });
        add("legacy_close", ticks(10), () -> mc.setScreen(null));
        add("legacy_outputs", ticks(10), () -> server(mc, (level, player) -> {
            var kettle = (BrewersKettleBlockEntity) level.getBlockEntity(legacyPos);
            int graded = 0;
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
                if (PotionUtils.getPotion(out) == Potions.SWIFTNESS && BrewingMetadata.read(out).isPresent()) graded++;
            }
            checks.put("legacy_three_graded_swiftness", graded == 3);
        }));

        probe(mc, "after_legacy");
        // ---- The operator report, from the real command in this clean world (no test datapack loaded) ----
        add("report", ticks(5), () -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                int ok = server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "immersivebrewing report");
                Path report = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("immersive_brewing")
                        .resolve("coverage-report.txt");
                try {
                    Files.createDirectories(Path.of(OUTPUT));
                    Files.copy(report, Path.of(OUTPUT, "coverage-report.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    checks.put("report_written", Files.size(report) > 0);
                } catch (IOException e) {
                    checks.put("report_written", false);
                    checks.put("report_error", e.toString() + " (command result " + ok + ")");
                }
            });
        });
        add("report_done", () -> checks.containsKey("report_written"), () -> {});

        probe(mc, "after_report");
        // ---- Station gallery (spec 26.6): the remaining states, every facing, day and night ----
        add("gallery_build", ticks(5), () -> server(mc, (level, player) -> StationGallery.build(level, base)));
        add("gallery_settle", ticks(90), () -> server(mc, (level, player) -> StationGallery.settle(level)));
        add("gallery_view", ticks(20), () -> server(mc, (level, player) -> {
            player.removeAllEffects(); // the Swiftness drunk earlier would swirl particles in front of the camera
            level.setDayTime(6000);
            BlockPos row = StationGallery.row;
            player.connection.teleport(row.getX() + 0.5, row.getY(), row.getZ() + 5.5, 180F, 20F);
        }));
        add("gallery_day", ticks(30), () -> {
            checks.put("gallery_empty", view(mc, StationGallery.at(-4)) != null && view(mc, StationGallery.at(-4)).view().state() == null);
            checks.put("gallery_active", view(mc, StationGallery.at(-2)) != null && view(mc, StationGallery.at(-2)).view().state() == JobState.RUNNING);
            checks.put("gallery_sealed", view(mc, StationGallery.at(0)) != null && view(mc, StationGallery.at(0)).view().state() == JobState.SEALED);
            checks.put("gallery_held", view(mc, StationGallery.at(2)) != null && view(mc, StationGallery.at(2)).view().state() == JobState.RECOVERY);
            checks.put("gallery_reclaiming", view(mc, StationGallery.at(4)) != null && view(mc, StationGallery.at(4)).view().state() == JobState.RUNNING);
            checks.put("gallery_four_facings", VisualChecks.fourFacings(mc, new BlockPos[] {StationGallery.at(-4), StationGallery.at(-2), StationGallery.at(0), StationGallery.at(2)}));
            shot(mc, "gallery_day");
        });
        add("gallery_night_time", ticks(2), () -> server(mc, (level, player) -> level.setDayTime(18000)));
        add("gallery_night", ticks(30), () -> shot(mc, "gallery_night"));
        add("gallery_recovery_view", ticks(2), () -> server(mc, (level, player) -> {
            level.setDayTime(6000);
            BlockPos held = StationGallery.at(2);
            player.connection.teleport(held.getX() + 0.5, held.getY(), held.getZ() + 2.5, 180F, 30F);
        }));
        add("gallery_recovery", ticks(30), () -> shot(mc, "gallery_recovery"));

        // ---- A refused plan explains itself: the finished bottles' plans list what is missing ----
        add("refused_return", ticks(2), () -> server(mc, (level, player) ->
                player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180F, 30F)));
        add("refused_open", ticks(10), () -> openMenu(mc, kettlePos()));
        add("refused_request", () -> mc.screen instanceof KettleScreen, () ->
                ModNetwork.sendToServer(new StationActionC2S(kettlePos(), StationActionC2S.Action.REQUEST_PLANS, 0)));
        add("refused_plans", () -> mc.screen instanceof KettlePlanScreen, () -> shot(mc, "plans_collect_first"));
        add("refused_close", ticks(5), () -> mc.setScreen(null));
        // ... and plans whose reagents are not loaded say exactly what is missing (the gallery's empty Kettle).
        add("missing_load", ticks(5), () -> server(mc, (level, player) -> {
            BlockPos empty = StationGallery.at(-4);
            BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) level.getBlockEntity(empty);
            for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
            player.connection.teleport(empty.getX() + 0.5, empty.getY(), empty.getZ() + 2.5, 180F, 30F);
        }));
        add("missing_open", ticks(10), () -> openMenu(mc, StationGallery.at(-4)));
        add("missing_request", () -> mc.screen instanceof KettleScreen, () ->
                ModNetwork.sendToServer(new StationActionC2S(StationGallery.at(-4), StationActionC2S.Action.REQUEST_PLANS, 0)));
        add("missing_plans", () -> mc.screen instanceof KettlePlanScreen, () -> shot(mc, "plans_missing"));
        add("missing_close", ticks(5), () -> mc.setScreen(null));
        workflow110(mc);
        if ("visual".equals(VARIANT)) visualMatrix(mc);
        if ("dense".equals(VARIANT)) denseMatrix(mc);
    }

    private static void denseMatrix(Minecraft mc) {
        add("dense_instrumentation", ticks(1), () -> {
            var visualHandler = com.otectus.immersivebrewing.network.packet.StationVisualS2C.clientHandler;
            com.otectus.immersivebrewing.network.packet.StationVisualS2C.clientHandler = packet -> {
                if (denseMeasuring()) {
                    var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    try { packet.encode(buffer); denseBytes += buffer.readableBytes(); } finally { buffer.release(); }
                }
                visualHandler.accept(packet);
            };
            checks.put("dense_jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
            checks.put("dense_os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            checks.put("dense_cpu", com.mojang.blaze3d.platform.GlUtil.getCpuInfo());
            checks.put("dense_gpu", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            checks.put("dense_driver", org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            checks.put("dense_heap_max_bytes", Runtime.getRuntime().maxMemory());
            checks.put("dense_graphics", mc.options.graphicsMode().get().toString());
            checks.put("dense_particles", mc.options.particles().get().toString());
            checks.put("dense_view_distance", mc.options.renderDistance().get());
            checks.put("dense_resource_packs", mc.getResourcePackRepository().getSelectedIds());
            checks.put("dense_fps_limit", mc.options.framerateLimit().get());
            checks.put("dense_vsync", mc.options.enableVsync().get());
            checks.put("dense_frame_note", "Frame interval includes frame cap and GPU wait; level CPU sample is AFTER_SKY to AFTER_LEVEL. Visual bytes exclude transport/full BE baselines; allocations are render thread only. Integrated-server tick includes all loaded work.");
        });
        for (int count : new int[] {0, 1, 8, 32, -128}) {
            String name = count < 0 ? "racks_128" : "kettles_" + count;
            add("dense_setup_" + name, ticks(5), () -> server(mc, (level, player) -> {
                int actual = count < 0 ? StationGallery.denseRacks(level, base, -count) : StationGallery.denseKettles(level, base, count);
                checks.put("dense_" + name + "_configured", actual);
                BlockPos arena = StationGallery.denseBase;
                player.getAbilities().flying = true; player.onUpdateAbilities();
                player.connection.teleport(arena.getX()+7.5, arena.getY()+6, arena.getZ()+12, 180, 22);
            }));
            add("dense_warmup_" + name, ticks(200), () -> shot(mc, "dense_" + name));
            add("dense_sample_start_" + name, ticks(1), () -> {
                DENSE_FRAMES.clear(); DENSE_INTERVALS.clear(); DENSE_SERVER.clear(); previousDenseFrame = 0;
                denseBytes = 0; denseParticles = denseEmissions = denseVoices = denseCache = 0;
                StationGallery.denseWorkingMin = Integer.MAX_VALUE; StationGallery.denseWorkingMax = 0;
                allocationStart = ALLOCATIONS.isThreadAllocatedMemorySupported() ? ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId()) : -1;
                denseUntilMillis = Util.getMillis()+60_000L;
            });
            add("dense_sample_" + name, () -> Util.getMillis() >= denseUntilMillis, () -> {
                denseUntilMillis = Long.MAX_VALUE;
                String key = "dense_" + name + "_";
                checks.put(key+"samples", DENSE_FRAMES.samples());
                checks.put(key+"frame_p50_ms", DENSE_INTERVALS.medianMs());
                checks.put(key+"frame_p95_ms", DENSE_INTERVALS.p95Ms());
                checks.put(key+"frame_p99_ms", DENSE_INTERVALS.p99Ms());
                checks.put(key+"level_cpu_p50_ms", DENSE_FRAMES.medianMs());
                checks.put(key+"level_cpu_p95_ms", DENSE_FRAMES.p95Ms());
                checks.put(key+"level_cpu_p99_ms", DENSE_FRAMES.p99Ms());
                checks.put(key+"server_tick_p50_ms", DENSE_SERVER.medianMs());
                checks.put(key+"server_tick_p95_ms", DENSE_SERVER.p95Ms());
                checks.put(key+"server_tick_p99_ms", DENSE_SERVER.p99Ms());
                checks.put(key+"working_min", StationGallery.denseWorkingMin == Integer.MAX_VALUE ? 0 : StationGallery.denseWorkingMin);
                checks.put(key+"working_max", StationGallery.denseWorkingMax);
                checks.put(key+"restarts", StationGallery.denseRestarts);
                checks.put(key+"particles_live_max", denseParticles);
                checks.put(key+"particles_per_second_max", denseEmissions);
                checks.put(key+"loop_voices_max", denseVoices);
                checks.put(key+"visual_cache_max", denseCache);
                checks.put(key+"visual_payload_bytes", denseBytes);
                checks.put(key+"render_thread_allocated_bytes", allocationStart < 0 ? -1 : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocationStart);
                checks.put(key+"window", mc.getWindow().getWidth()+"x"+mc.getWindow().getHeight());
                checks.put(key+"gui_scale", mc.getWindow().getGuiScale());
                checks.put(key+"camera", mc.player.position().toString());
                checks.put(key+"measured", DENSE_FRAMES.samples() > 100 && (count <= 0 || StationGallery.denseWorkingMax == count));
            });
        }
        add("dense_cleanup", ticks(1), () -> server(mc, (level, player) -> StationGallery.clearDense(level)));
    }


    private static int probes;

    /** A marker block placed on the server must reach the client within half a second (chunk change broadcast). */
    private static void probe(Minecraft mc, String name) {
        int n = probes++;
        BlockPos[] at = new BlockPos[1];
        add("probe_set_" + name, ticks(2), () -> server(mc, (level, player) -> {
            at[0] = base.offset(-6 + n, 4, -2);
            level.setBlock(at[0], net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), 3);
        }));
        add("probe_get_" + name, ticks(10), () -> {
            checks.put("block_sync_" + name, at[0] != null && mc.level.getBlockState(at[0]).is(net.minecraft.world.level.block.Blocks.GLASS));
            server(mc, (level, player) -> level.setBlock(at[0], net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3));
        });
    }

    private static boolean buttonReady(Minecraft mc, String key) {
        String label = Component.translatable(key).getString();
        return mc.screen != null && mc.screen.children().stream().anyMatch(child -> child instanceof net.minecraft.client.gui.components.Button b
                && b.visible && b.active && b.getMessage().getString().equals(label));
    }

    /** New actions run through their actual menus and packets, with exact inventory state checked by the server. */
    private static void workflow110(Minecraft mc) {
        add("finishing_setup", ticks(2), () -> server(mc, (level, player) -> {
            player.connection.teleport(base.getX() + .5, base.getY(), base.getZ() + 3.5, 180, 30);
            player.getInventory().clearContent();
            var kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
            for (int i = 0; i < kettle.inventory().getSlots(); i++) kettle.inventory().setStackInSlot(i, ItemStack.EMPTY);
            ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
            BrewingMetadata.write(potion, BrewingMetadata.brewed(100, 100, 100, 110L, player.getUUID(), "Smoke"));
            potion.getOrCreateTag().putString("fixture_foreign", "preserve");
            for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(i, potion.copy());
            player.getInventory().setItem(10, new ItemStack(Items.GUNPOWDER));
            player.getInventory().setItem(11, new ItemStack(Items.BLAZE_POWDER));
        }));
        add("finishing_menu", ticks(10), () -> openMenu(mc, kettlePos()));
        add("finishing_query", () -> mc.screen instanceof KettleScreen, () -> ModNetwork.sendToServer(
                new com.otectus.immersivebrewing.network.packet.CatalogQueryC2S(kettlePos(), 20, "minecraft:splash_potion", List.of(), 0, 0, false, 3)));
        // The compact layout (GUI 427x320 and smaller) shows Prepare and Finish only on the route page.
        add("finishing_route", () -> mc.screen instanceof KettlePlanScreen p && p.plans().request() == 20 && !p.plans().entries().isEmpty(), () -> {
            if (mc.screen.width < 480 || mc.screen.height < 300) VisualChecks.click(mc.screen, "screen.immersive_brewing.plan.show_route");
        });
        add("finishing_prepare", () -> buttonReady(mc, "staging.immersive_brewing.prepare"), () ->
                checks.put("prepare_button", VisualChecks.click(mc.screen, "staging.immersive_brewing.prepare")));
        add("finishing_start", () -> buttonReady(mc, "finishing.immersive_brewing.finish"), () ->
                checks.put("finish_button", VisualChecks.click(mc.screen, "finishing.immersive_brewing.finish")));
        add("finishing_view", () -> mc.screen instanceof WorkshopScreen && workshop(mc, kettlePos()).contains("finishing"), () -> {
            checks.put("finishing_opens_fitting", true);
            WORKSHOP.enable();
        });
        add("finishing_closed", () -> workshop(mc, kettlePos()).getCompound("finishing").getBoolean("closed"), () -> {
            WORKSHOP.disable();
            shot(mc, "finishing_progress");
            mc.screen.onClose();
        });
        add("finishing_menu", ticks(5), () -> openMenu(mc, kettlePos()));
        add("finishing_seal", () -> mc.screen instanceof KettleScreen && buttonReady(mc, "screen.immersive_brewing.seal"),
                () -> checks.put("finish_seal_button", VisualChecks.click(mc.screen, "screen.immersive_brewing.seal")));
        add("finishing_resume", () -> view(mc, kettlePos()).view().state() == JobState.SEALED,
                () -> checks.put("finish_resume_button", VisualChecks.click(mc.screen, "screen.immersive_brewing.resume")));
        add("finishing_done", () -> view(mc, kettlePos()).view().state() == null, () -> server(mc, (level, player) -> {
            var kettle = (BrewersKettleBlockEntity) level.getBlockEntity(kettlePos());
            boolean exact = true;
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(i);
                exact &= out.is(Items.SPLASH_POTION) && BrewingMetadata.read(out).orElseThrow().quality() == 100
                        && out.getTag().getString("fixture_foreign").equals("preserve");
            }
            checks.put("finish_exact_outputs", exact);
        }));
        add("finishing_collect_menu", () -> checks.containsKey("finish_exact_outputs"), () -> {
            if (mc.screen != null) mc.screen.onClose();
            openMenu(mc, kettlePos());
        });
        add("finishing_collect", () -> mc.screen instanceof KettleScreen && buttonReady(mc, "staging.immersive_brewing.collect"), () ->
                checks.put("collect_button", VisualChecks.click(mc.screen, "staging.immersive_brewing.collect")));
        add("finishing_close", ticks(5), () -> mc.setScreen(null));
        rack110(mc);
    }

    private static void rack110(Minecraft mc) {
        BlockPos pos = base.offset(4, 0, 0);
        add("rack_setup", ticks(3), () -> {
            String label = Component.translatable("smoketest.immersive_brewing.loadout").getString();
            server(mc, (level, player) -> {
                level.setBlockAndUpdate(pos, ModBlocks.APOTHECARY_RACK.get().defaultBlockState());
                var rack = (com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity) level.getBlockEntity(pos);
                var potions = List.of(Potions.SWIFTNESS, Potions.STRENGTH, Potions.FIRE_RESISTANCE, Potions.NIGHT_VISION, Potions.WATER_BREATHING, Potions.SLOW_FALLING);
                for (int i = 0; i < 6; i++) {
                    ItemStack bottle = PotionUtils.setPotion(new ItemStack(i % 2 == 0 ? Items.POTION : Items.SPLASH_POTION), potions.get(i));
                    BrewingMetadata.write(bottle, BrewingMetadata.brewed(80, 80, 80, 110L + i, player.getUUID(), "Smoke"));
                    rack.inventory().setStackInSlot(i, bottle);
                }
                rack.setLabel(0, label);
                player.getInventory().selected = 0;
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BATCH_CARRIER.get()));
                player.connection.teleport(pos.getX() + .5, pos.getY(), pos.getZ() + 2.5, 180, 20);
            });
        });
        add("rack_world", ticks(15), () -> shot(mc, "apothecary_rack_world"));
        add("rack_open", ticks(2), () -> openMenu(mc, pos));
        add("rack_menu", () -> mc.screen instanceof com.otectus.immersivebrewing.client.screen.ApothecaryRackScreen,
                () -> shot(mc, "apothecary_rack_menu"));
        add("rack_pack", ticks(2), () -> checks.put("rack_pack_button", VisualChecks.click(mc.screen, "screen.immersive_brewing.rack.pack")));
        add("rack_packed", ticks(8), () -> server(mc, (level, player) -> {
            checks.put("rack_packed_six", com.otectus.immersivebrewing.item.CarrierContents.count(player.getMainHandItem()) == 6);
        }));
        add("rack_close", () -> checks.containsKey("rack_packed_six"), () -> mc.setScreen(null));
        add("carrier_open_110", ticks(3), () -> server(mc, (level, player) -> ModItems.BATCH_CARRIER.get().use(level, player, InteractionHand.MAIN_HAND)));
        add("carrier_selected_110", () -> mc.screen instanceof com.otectus.immersivebrewing.client.screen.BatchCarrierScreen, () -> {
            var buttons = mc.screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                    .map(net.minecraft.client.gui.components.Button.class::cast).toList();
            mc.screen.setFocused(buttons.get(2));
            mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        });
        add("carrier_shot_110", ticks(5), () -> shot(mc, "batch_carrier_selected"));
        add("carrier_close_110", ticks(2), () -> mc.screen.onClose());
        add("carrier_equip_110", ticks(3), () -> ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.EquipCarrierSelectedC2S(InteractionHand.MAIN_HAND)));
        add("carrier_equipped_110", ticks(8), () -> server(mc, (level, player) -> {
            checks.put("carrier_equips_without_drinking", player.getMainHandItem().is(Items.POTION)
                    && PotionUtils.getPotion(player.getMainHandItem()) == Potions.FIRE_RESISTANCE
                    && !player.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE));
            player.getMainHandItem().getOrCreateTag().putBoolean("IBSmokeDrink", true);
        }));
        add("drink_real_start", () -> mc.player.getMainHandItem().hasTag()
                && mc.player.getMainHandItem().getTag().getBoolean("IBSmokeDrink"), () -> {
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            int expected = PotionContentsService.effectiveUseTicks(mc.player.getMainHandItem(), 24);
            checks.put("drink_animation_composes_modifier", com.otectus.immersivebrewing.client.ClientDrinkTiming
                    .duration(mc.player.getMainHandItem()) == expected);
            checks.put("drink_expected_ticks", expected);
        });
        add("drink_real_animation", ticks(8), () -> shot(mc, "composed_drinking_animation"));
        add("drink_real_finish", () -> mc.player.hasEffect(MobEffects.FIRE_RESISTANCE), () -> {
            mc.options.keyUse.setDown(false);
            checks.put("drink_real_use_completed", true);
            checks.put("drink_server_composes_modifier", DrinkTimingFixture.serverDuration == (Integer) checks.get("drink_expected_ticks"));
        });
    }

    /** Every page and JEI at 320x240, in each shipped language. */
    private static void visualMatrix(Minecraft mc) {
        for (String language : List.of("en_us", "es_es", "pt_br", "ru_ru", "zh_cn")) {
            add("language_" + language, ticks(2), () -> {
                mc.getLanguageManager().setSelected(language);
                mc.options.languageCode = language;
                languageReload = mc.reloadResourcePacks();
            });
            add("language_ready_" + language, () -> languageReload != null && languageReload.isDone() && mc.getOverlay() == null, () -> {
                languageReload.join();
                String label = Component.translatable("smoketest.immersive_brewing.loadout").getString();
                server(mc, (level, player) -> ((com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity)
                        level.getBlockEntity(base.offset(4, 0, 0))).setLabel(0, label));
            });
            for (String tab : List.of("start", "stations", "controls", "quality", "recovery", "recipes", "reagents", "compatibility", "favorites", "journal", "practice")) {
                add("notebook_" + language + "_" + tab, ticks(3), () -> {
                    mc.setScreen(new NotebookScreen());
                    checks.put("tab_" + language + "_" + tab, VisualChecks.notebookTab(mc.screen, tab));
                });
                add("notebook_shot_" + language + "_" + tab, ticks(3), () -> shot(mc, language + "_notebook_" + tab));
            }
            add("notebook_scroll_" + language, ticks(2), () -> {
                VisualChecks.notebookTab(mc.screen, "start");
            });
            add("notebook_scroll_end_" + language, ticks(3), () -> VisualChecks.scrollEnd(mc.screen, "content", checks, language + "_notebook", true));
            add("notebook_bottom_" + language, ticks(3), () -> shot(mc, language + "_notebook_bottom"));
            add("label_" + language, ticks(2), () -> {
                ItemStack potion = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.LONG_FIRE_RESISTANCE);
                mc.setScreen(new com.otectus.immersivebrewing.client.screen.LabelScreen(potion, InteractionHand.MAIN_HAND));
                VisualChecks.resizeEdits(mc.screen, checks, language + "_label");
            });
            add("label_shot_" + language, ticks(3), () -> shot(mc, language + "_label"));
            add("result_" + language, ticks(2), () -> mc.setScreen(new ResultScreen(reviewResult)));
            add("result_shot_" + language, ticks(3), () -> shot(mc, language + "_result"));
            add("result_scroll_" + language, ticks(2), () -> VisualChecks.scrollEnd(mc.screen, "content", checks, language + "_result", false));
            add("result_bottom_" + language, ticks(3), () -> shot(mc, language + "_result_bottom"));
            add("plans_" + language, ticks(2), () -> {
                mc.setScreen(new KettlePlanScreen(null, reviewPlans));
                VisualChecks.longestPlan((KettlePlanScreen) mc.screen);
                // The compact layout keeps the route on its own page.
                if (mc.screen.width < 480 || mc.screen.height < 300) VisualChecks.click(mc.screen, "screen.immersive_brewing.plan.show_route");
            });
            add("plans_shot_" + language, ticks(3), () -> shot(mc, language + "_plans"));
            add("plans_scroll_" + language, ticks(2), () -> VisualChecks.scrollEnd(mc.screen, "detail", checks, language + "_plans", true));
            add("plans_bottom_" + language, ticks(3), () -> shot(mc, language + "_plans_bottom"));
            for (String station : List.of("mortar", "kettle", "alembic", "rack")) {
                BlockPos at = station.equals("mortar") ? mortarPos() : station.equals("kettle") ? kettlePos()
                        : station.equals("rack") ? base.offset(4, 0, 0) : alembicPos();
                add("workshop_place_" + language + "_" + station, ticks(3), () -> server(mc, (level, player) -> {
                    // An empty hand opens the working view (holding a carrier or a potion would use it on the station).
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180F, 30F);
                }));
                add("workshop_open_" + language + "_" + station, ticks(5), () -> use(mc, at));
                add("workshop_shot_" + language + "_" + station, () -> mc.screen instanceof WorkshopScreen,
                        () -> shot(mc, language + "_" + station + "_workshop"));
                add("workshop_close_" + language + "_" + station, ticks(2), () -> {
                    if (mc.screen != null) mc.screen.onClose();
                });
            }
            for (String station : List.of("mortar", "kettle", "alembic", "rack")) {
                add("station_open_" + language + "_" + station, ticks(3), () -> server(mc, (level, player) ->
                        player.connection.teleport(base.getX() + 0.5, base.getY(), base.getZ() + 3.5, 180F, 30F)));
                add("station_menu_" + language + "_" + station, ticks(3), () -> openMenu(mc,
                        station.equals("mortar") ? mortarPos() : station.equals("kettle") ? kettlePos()
                                : station.equals("rack") ? base.offset(4, 0, 0) : alembicPos()));
                add("station_shot_" + language + "_" + station, ticks(6), () -> shot(mc, language + "_" + station + "_menu"));
                add("station_close_" + language + "_" + station, ticks(2), () -> mc.setScreen(null));
            }
            add("carrier_setup_" + language, ticks(2), () -> {
                String label = Component.translatable("smoketest.immersive_brewing.loadout").getString();
                server(mc, (level, player) -> {
                    ItemStack carrier = new ItemStack(ModItems.BATCH_CARRIER.get());
                    CarrierContents.Meta meta = CarrierContents.meta(carrier);
                    CarrierContents.writeMeta(carrier, new CarrierContents.Meta(0, label, meta.labelColor(), meta.emblem(), false, meta.templates()));
                    CarrierContents.write(carrier, List.of(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS)));
                    player.setItemInHand(InteractionHand.MAIN_HAND, carrier);
                });
            });
            // The client constructs the menu from its held stack; wait for inventory sync before opening it.
            add("carrier_open_" + language, () -> Component.translatable("smoketest.immersive_brewing.loadout").getString()
                    .equals(CarrierContents.meta(mc.player.getMainHandItem()).loadoutName()), () -> server(mc, (level, player) ->
                    ModItems.BATCH_CARRIER.get().use(level, player, InteractionHand.MAIN_HAND)));
            add("carrier_shot_" + language, () -> mc.screen instanceof com.otectus.immersivebrewing.client.screen.BatchCarrierScreen,
                    () -> {
                        String label = Component.translatable("smoketest.immersive_brewing.loadout").getString();
                        checks.put("carrier_label_localized_" + language, mc.screen.children().stream()
                                .filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                                .map(net.minecraft.client.gui.components.EditBox.class::cast).anyMatch(box -> box.getValue().equals(label)));
                        shot(mc, language + "_carrier");
                    });
            add("carrier_close_" + language, ticks(2), () -> mc.setScreen(null));
            for (String category : List.of("brewing", "preparation", "distillation", "reclamation")) {
                add("jei_" + language + "_" + category, ticks(2), () -> VisualJeiPlugin.show(category));
                add("jei_shot_" + language + "_" + category, ticks(6), () -> shot(mc, language + "_jei_" + category));
                add("jei_close_" + language + "_" + category, ticks(2), () -> mc.setScreen(null));
            }
        }
        add("restore_language", ticks(2), () -> {
            mc.getLanguageManager().setSelected("en_us");
            mc.options.languageCode = "en_us";
            languageReload = mc.reloadResourcePacks();
        });
        add("restore_language_ready", () -> languageReload.isDone() && mc.getOverlay() == null, () -> languageReload.join());
    }

    private static void add(String name, BooleanSupplier ready, Runnable action) {
        steps.add(new Step(name, ready, action));
    }

    private static BooleanSupplier always() {
        return () -> true;
    }

    /** Ready after {@code n} ticks of waiting at this step. */
    private static BooleanSupplier ticks(int n) {
        return () -> stepTicks >= n;
    }

    private static StationBlockEntity view(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockEntity(pos) instanceof StationBlockEntity be ? be : null;
    }

    /** An ordinary right-click on the station's top with whatever is in hand (the real use path). */
    private static void use(Minecraft mc, BlockPos pos) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos).add(0, 0.45, 0), Direction.UP, pos, false));
    }

    /** The synced workshop snapshot of a station (empty when there is none). */
    private static net.minecraft.nbt.CompoundTag workshop(Minecraft mc, BlockPos pos) {
        StationBlockEntity be = view(mc, pos);
        return be == null ? new net.minecraft.nbt.CompoundTag() : be.workshopView();
    }

    private static int stage(Minecraft mc, BlockPos pos) {
        var k = workshop(mc, pos).getCompound("kettle");
        return k.contains("stage") ? k.getByte("stage") : -1;
    }

    @org.jetbrains.annotations.Nullable
    private static Object tutorialPrompt(Minecraft mc) {
        if (!(mc.screen instanceof WorkshopScreen screen) || ClientWorkshop.kind() == null) return null;
        var be = mc.level.getBlockEntity(screen.pos());
        return be == null ? null : com.otectus.immersivebrewing.client.workshop.WorkshopTutorial.prompt(
                com.otectus.immersivebrewing.client.workshop.WorkshopTargets.view(be, ClientWorkshop.kind()), ClientWorkshop.hand());
    }

    private static int kitsOnShelf(Minecraft mc) {
        if (!(mc.level.getBlockEntity(mortarPos()) instanceof ApothecaryMortarBlockEntity mortar)) return 0;
        int n = 0;
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) n += mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i).getCount();
        return n;
    }

    private static int kitExtraction(Minecraft mc) {
        if (!(mc.level.getBlockEntity(mortarPos()) instanceof ApothecaryMortarBlockEntity mortar)) return -1;
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
            var kit = KitData.read(mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i));
            if (kit.isPresent()) return kit.get().extraction();
        }
        return -1;
    }

    private static int rackFinished(Minecraft mc, BlockPos pos) {
        if (!(mc.level.getBlockEntity(pos) instanceof BrewersKettleBlockEntity kettle)) return 0;
        int n = 0;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.is(Items.POTION) && BrewingMetadata.read(s).isPresent()) n++;
        }
        return n;
    }

    private static int outputs(Minecraft mc, BlockPos pos) {
        if (!(mc.level.getBlockEntity(pos) instanceof AlembicBlockEntity alembic)) return 0;
        int n = 0;
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) n += alembic.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i).getCount();
        return n;
    }

    /** Every item the player and the main stations own, and their jobs (practice must leave all of it unchanged). */
    private static String ownership(ServerLevel level, ServerPlayer player) {
        StringBuilder out = new StringBuilder();
        for (BlockPos pos : List.of(mortarPos(), kettlePos(), alembicPos())) {
            if (!(level.getBlockEntity(pos) instanceof StationBlockEntity station)) continue;
            for (int i = 0; i < station.inventory().getSlots(); i++) out.append(station.inventory().getStackInSlot(i).save(new net.minecraft.nbt.CompoundTag())).append('|');
            out.append(station.ledger() == null ? "no-job" : station.ledger().jobId().toString()).append(';').append(station.fuelCharges()).append('\n');
        }
        for (int i = 0; i < 36; i++) out.append(player.getInventory().getItem(i).save(new net.minecraft.nbt.CompoundTag())).append('|');
        out.append(player.totalExperience).append('|').append(com.otectus.immersivebrewing.guide.PlayerGuide.data(player).getList("history", 10).size());
        return out.toString();
    }

    private static int[] windowSize() {
        if (WINDOW != null) {
            String[] parts = WINDOW.toLowerCase(java.util.Locale.ROOT).split("x");
            if (parts.length == 2) return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        }
        return new int[] {1280, 960};
    }

    /** The bot's frames go straight into the session, except in the keyboard trial, where the keys carry them. */
    private static void scripted(java.util.function.ToIntFunction<com.otectus.immersivebrewing.session.SimHandle> bot) {
        ClientSession.scriptedInput = KEYBOARD ? null : bot;
    }

    private static void applyVariant(Minecraft mc) {
        boolean accessible = "accessible".equals(VARIANT);
        com.otectus.immersivebrewing.config.ClientConfig.PALETTE.set("colorblind".equals(VARIANT)
                ? com.otectus.immersivebrewing.config.ClientConfig.Palette.COLORBLIND_SAFE
                : accessible ? com.otectus.immersivebrewing.config.ClientConfig.Palette.HIGH_CONTRAST
                : com.otectus.immersivebrewing.config.ClientConfig.Palette.STANDARD);
        com.otectus.immersivebrewing.config.ClientConfig.REDUCED_MOTION.set(accessible);
        com.otectus.immersivebrewing.config.ClientConfig.NUMERIC_DISPLAY.set(accessible);
        com.otectus.immersivebrewing.config.ClientConfig.LARGE_MARKERS.set(accessible);
        com.otectus.immersivebrewing.config.ClientConfig.SHOW_TUTORIAL.set(true);
        com.otectus.immersivebrewing.config.ClientConfig.REQUEST_ASSIST.set(false);
        com.otectus.immersivebrewing.config.ClientConfig.TOGGLE_STIR.set(false);
        com.otectus.immersivebrewing.config.ClientConfig.TOGGLE_PRESSURE.set(false);
        com.otectus.immersivebrewing.config.ClientConfig.REDUCED_INPUT.set(accessible);
        // Hands-on options start from their defaults each run (the tutorial stage persists in the client config).
        com.otectus.immersivebrewing.config.ClientConfig.TUTORIAL_STAGE.set(0);
        com.otectus.immersivebrewing.config.ClientConfig.CAMERA_MODE.set(com.otectus.immersivebrewing.config.ClientConfig.CameraMode.FOCUS);
        com.otectus.immersivebrewing.config.ClientConfig.HOLD_TO_GRAB.set(false);
        com.otectus.immersivebrewing.config.ClientConfig.MIRROR_TOOLS.set(false);
        com.otectus.immersivebrewing.config.ClientConfig.PART_HINTS.set(true);
        // Written through, so a config-file reload (the previous run saved its tutorial progress) can not undo the reset.
        com.otectus.immersivebrewing.config.ClientConfig.SPEC.save();
        mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(accessible ? 0.0 : 1.0);
        checks.put("variant", VARIANT == null ? "default" : VARIANT);
    }

    /** Closes a game screen's tutorial overlay through the real key binding, if it is showing. */
    private static void closeHelp(Minecraft mc) {
        if (!(mc.screen instanceof GameScreen screen) || !screen.helpShowing()) return;
        var key = com.otectus.immersivebrewing.client.Keys.HELP.getKey();
        screen.keyPressed(key.getValue(), 0, 0);
        screen.keyReleased(key.getValue(), 0, 0);
        checks.put("help_closed_" + screen.game().name().toLowerCase(java.util.Locale.ROOT), !screen.helpShowing());
    }

    private static void openMenu(Minecraft mc, BlockPos pos) {
        server(mc, (level, player) -> {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider menu) NetworkHooks.openScreen(player, menu, buf -> buf.writeBlockPos(pos));
        });
    }

    private interface ServerAction {
        void run(ServerLevel level, ServerPlayer player);
    }

    private static void server(Minecraft mc, ServerAction action) {
        var server = mc.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("no integrated server");
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            action.run(player.serverLevel(), player);
        });
    }

    private static void createWorld(Minecraft mc) {
        String name = "BrewingSmokeTest" + System.currentTimeMillis();
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261010L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private static void shot(Minecraft mc, String name) {
        mc.getToasts().clear();
        VisualChecks.widgets(mc.screen, checks, name);
        checks.put("shot_size_" + name, mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
        if ("visual".equals(VARIANT)) checks.put("minimum_gui_" + name,
                mc.getWindow().getGuiScaledWidth() == 320 && mc.getWindow().getGuiScaledHeight() == 240);
        pendingShot = name;
        pendingFrames = 3;
        pendingTicks = 0;
        checks.put("shot_" + name, false);
    }

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent event) {
        if (OUTPUT == null || pendingShot == null || event.phase != TickEvent.Phase.END || --pendingFrames > 0) return;
        Minecraft mc = Minecraft.getInstance();
        String name = pendingShot;
        String file = "smoketest_" + (VARIANT == null ? "" : VARIANT + "_") + (WINDOW == null ? "" : WINDOW + "_")
                + (GUI_SCALE == null ? "" : "gui" + GUI_SCALE + "_") + name;
        pendingShot = null;
        Screenshot.grab(mc.gameDirectory, file + ".png", mc.getMainRenderTarget(), msg -> log("screenshot " + file));
        checks.put("shot_" + name, true);
    }

    private static boolean allPassed() {
        synchronized (checks) {
            for (Map.Entry<String, Object> e : checks.entrySet()) {
                if (e.getValue() instanceof Boolean b && !b) return false;
            }
        }
        return true;
    }

    private static void finish(Minecraft mc, boolean pass, String reason) {
        finished = true;
        ClientSession.scriptedInput = null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pass", pass);
        result.put("reason", reason);
        synchronized (checks) {
            result.put("checks", new LinkedHashMap<>(checks));
        }
        try {
            Files.createDirectories(Path.of(OUTPUT));
            Files.writeString(Path.of(OUTPUT, "smoketest-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ImmersiveBrewing.LOGGER.error("SMOKETEST could not write result", e);
        }
        log("finished: pass=" + pass + " (" + reason + ")");
        mc.stop();
    }

    private static void log(String message) {
        ImmersiveBrewing.LOGGER.info("SMOKETEST {}", message);
    }

    /** One graded potion's tooltip, for the screenshot. */
    private static final class TooltipSheet extends Screen {
        private final ItemStack stack;

        TooltipSheet(ItemStack stack) {
            super(Component.literal("Tooltip"));
            this.stack = stack;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g);
            g.renderTooltip(font, stack, width / 2 - 60, height / 2 - 30);
        }
    }

    private ClientSmokeTest() {}
}
