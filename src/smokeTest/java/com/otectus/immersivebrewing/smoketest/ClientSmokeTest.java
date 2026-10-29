package com.otectus.immersivebrewing.smoketest;

import com.google.gson.GsonBuilder;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.screen.DistillationScreen;
import com.otectus.immersivebrewing.client.screen.GameScreen;
import com.otectus.immersivebrewing.client.screen.KettleBrewScreen;
import com.otectus.immersivebrewing.client.screen.KettlePlanScreen;
import com.otectus.immersivebrewing.client.screen.KettleScreen;
import com.otectus.immersivebrewing.client.screen.MortarScreen;
import com.otectus.immersivebrewing.client.screen.NotebookScreen;
import com.otectus.immersivebrewing.client.screen.PreparationScreen;
import com.otectus.immersivebrewing.client.screen.ResultScreen;
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
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.DistillBot;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Drives a dev client from the title screen into a fresh flat world and walks the whole workshop through the real client
 * and server code (spec 27, milestone 1 gate): a preparation at the Mortar, a three-serving Swiftness brew at the Kettle
 * played through the real screens and input packets by the reference bots, sealed halfway and resumed, one potion drunk
 * with its applied duration compared to the tooltip, a purification at the Alembic, and the Notebook. On the way it checks
 * that each game's tutorial holds its count-in, that the help pauses a running brew on the client and the server and a
 * practice game too, and that play continues after a count-in. Saves screenshots to
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

    /**
     * Window size as {@code WxH}, default 1280x960: GUI scales 1 to 4 then give 1280x960 down to exactly 320x240, the
     * smallest GUI Minecraft allows. A tiling window manager may override it (tools/run_smoke_matrix.sh floats the window).
     */
    private static final String WINDOW = System.getProperty("immersive_brewing.smoketest.window");
    // Written from the client and the integrated server thread (server actions record their checks).
    private static final Map<String, Object> checks = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private static final long START = Util.getMillis();
    private static final int STEP_TIMEOUT = 20 * 120;

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
    private static int sealedDelay;
    /** The count-in progress when a tutorial opened, and the step a help pause stopped at (client and server). */
    private static float heldCountIn;
    private static int pausedStep;
    private static final AtomicInteger serverPausedStep = new AtomicInteger(-1);
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

        // ---- Mortar ----
        add("world_view", ticks(20), () -> shot(mc, "world_stations"));
        add("open_mortar", always(), () -> openMenu(mc, mortarPos()));
        add("mortar_menu", () -> mc.screen instanceof MortarScreen, () -> shot(mc, "mortar_menu"));
        add("start_preparation", ticks(10), () -> {
            scripted(sim -> MortarBot.competent().decide(((SimHandles.Preparation) sim).sim()).pack());
            ModNetwork.sendToServer(new StationActionC2S(mortarPos(), StationActionC2S.Action.START_PREPARATION, StationActionC2S.withAssist(2, 1, 1)));
        });
        // Each game's first session opens its tutorial, which holds the count-in until it is closed.
        add("preparation_open", () -> mc.screen instanceof PreparationScreen && ClientSession.active() != null, () -> tutorialOpened(mc, "mortar"));
        add("preparation_help", ticks(30), () -> {
            tutorialHeld("mortar");
            shot(mc, "mortar_help");
        });
        add("preparation_help_closed", ticks(2), () -> closeHelp(mc));
        AtomicReference<MortarBot> mortarBot = new AtomicReference<>(MortarBot.competent());
        add("preparation_running", () -> mc.screen instanceof PreparationScreen && ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {
            MortarBot bot = mortarBot.get();
            scripted(sim -> bot.decide(((SimHandles.Preparation) sim).sim()).pack());
        });
        add("preparation_screen", ticks(60), () -> shot(mc, "mortar_game"));
        add("preparation_result", () -> mc.screen instanceof ResultScreen, () -> {
            ClientSession.scriptedInput = null;
            ClientSession session = ClientSession.active();
            checks.put("preparation_extraction", session != null && session.result() != null ? session.result().getInt("E") : -1);
            if (KEYBOARD) checks.put("keyboard_preparation_fine", session != null && session.result() != null && session.result().getInt("E") >= 70);
            shot(mc, "mortar_result");
        });
        add("preparation_close", ticks(10), () -> mc.setScreen(null));
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
        add("brew_open", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null, () -> tutorialOpened(mc, "kettle"));
        add("brew_help", ticks(30), () -> {
            tutorialHeld("kettle");
            shot(mc, "kettle_help");
        });
        add("brew_help_closed", ticks(2), () -> closeHelp(mc));
        add("brew_running", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {
            KettleBot bot = KettleBot.competent();
            scripted(sim -> bot.decide(((SimHandles.Brewing) sim).sim()).pack());
        });
        add("brew_screen", ticks(160), () -> shot(mc, "kettle_brewing"));
        // Mid-brew the help stops the game on both sides; closing it counts in again before play continues.
        add("brew_pause", ticks(2), () -> openHelp(mc));
        add("brew_paused", ticks(20), () -> {
            pausedStep = ClientSession.active().sim().step();
            server(mc, (level, player) -> serverPausedStep.set(SessionManager.byPlayer(player.getUUID())
                    .map(s -> s.paused() ? s.sim().step() : -1).orElse(-1)));
        });
        add("brew_still", ticks(40), () -> {
            ClientSession session = ClientSession.active();
            checks.put("help_pauses_kettle", session.paused() && session.sim().step() == pausedStep);
            int held = serverPausedStep.get();
            server(mc, (level, player) -> checks.put("help_pauses_server", held >= 0 && SessionManager.byPlayer(player.getUUID())
                    .map(s -> s.paused() && s.sim().step() == held).orElse(false)));
            shot(mc, "kettle_paused");
        });
        add("brew_unpause", ticks(2), () -> closeHelp(mc));
        add("brew_resume_count_in", ticks(5), () -> {
            ClientSession session = ClientSession.active();
            checks.put("help_resume_counts_in", session.countingIn() && session.resuming() && session.sim().step() == pausedStep);
        });
        add("brew_resumed", () -> ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {});
        add("brew_continues", ticks(20), () -> checks.put("help_resume_continues", ClientSession.active().sim().step() > pausedStep));
        add("seal_halfway", ticks(120), () -> {
            ClientSession.scriptedInput = null;
            ((GameScreen) mc.screen).onClose();
        });
        add("sealed_state", () -> view(mc, kettlePos()) != null && view(mc, kettlePos()).view().state() == JobState.SEALED, () -> {
            checks.put("sealed_batch_visible", true);
            shot(mc, "world_sealed");
        });
        add("open_kettle_again", ticks(10), () -> openMenu(mc, kettlePos()));
        add("kettle_menu_sealed", () -> mc.screen instanceof KettleScreen, () -> shot(mc, "kettle_menu_sealed"));
        add("resume", ticks(2), () -> ModNetwork.sendToServer(new StationActionC2S(kettlePos(), StationActionC2S.Action.RESUME, 0)));
        add("resumed_running", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {
            checks.put("resumed", ClientSession.active().resumed);
            KettleBot bot = KettleBot.competent();
            scripted(sim -> bot.decide(((SimHandles.Brewing) sim).sim()).pack());
        });
        add("brew_result", () -> mc.screen instanceof ResultScreen, () -> {
            ClientSession.scriptedInput = null;
            ClientSession session = ClientSession.active();
            if (session != null && session.result() != null) {
                checks.put("brew_quality", session.result().getInt("Q"));
                checks.put("brew_extraction", session.result().getInt("E"));
                checks.put("brew_control", session.result().getInt("C"));
                if (KEYBOARD) checks.put("keyboard_brew_fine", session.result().getInt("Q") >= 70);
                if (KEYBOARD) checks.put("keyboard_key_presses", DRIVER.presses());
            }
            reviewResult = session;
            shot(mc, "kettle_result");
        });
        add("brew_result_resize", ticks(3), () -> VisualChecks.resizeEdits(mc.screen, checks, "result"));
        add("brew_close", ticks(30), () -> mc.setScreen(null));
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

        // ---- Alembic purification ----
        add("open_alembic", ticks(10), () -> openMenu(mc, alembicPos()));
        add("alembic_menu", () -> mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>, () -> shot(mc, "alembic_menu"));
        add("start_purify", ticks(10), () -> ModNetwork.sendToServer(new StationActionC2S(alembicPos(), StationActionC2S.Action.START_PURIFICATION, 0)));
        add("distill_open", () -> mc.screen instanceof DistillationScreen && ClientSession.active() != null, () -> tutorialOpened(mc, "alembic"));
        add("distill_help", ticks(30), () -> tutorialHeld("alembic"));
        add("distill_help_closed", ticks(2), () -> closeHelp(mc));
        add("distill_running", () -> mc.screen instanceof DistillationScreen && ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {
            DistillBot bot = DistillBot.competent();
            scripted(sim -> bot.decide(((SimHandles.Distillation) sim).sim()).pack());
        });
        add("distill_screen", ticks(140), () -> shot(mc, "alembic_game"));
        add("distill_result", () -> mc.screen instanceof ResultScreen, () -> {
            ClientSession.scriptedInput = null;
            ClientSession session = ClientSession.active();
            if (session != null && session.result() != null) {
                checks.put("distillation_score", session.result().getInt("D"));
                checks.put("purified_quality", session.result().getInt("Q"));
                if (KEYBOARD) checks.put("keyboard_distillation_good", session.result().getInt("D") >= 70);
            }
            shot(mc, "alembic_result");
        });
        add("distill_close", ticks(20), () -> mc.setScreen(null));
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

        // ---- Practice: the help pauses a practice game too (no server is involved) ----
        add("practice_open", ticks(5), () -> com.otectus.immersivebrewing.client.ClientPacketHandler.practice(SessionKind.BREWING));
        add("practice_running", () -> mc.screen instanceof KettleBrewScreen && ClientSession.active() != null && ClientSession.active().practice
                && !ClientSession.active().countingIn(), () -> openHelp(mc));
        add("practice_paused", ticks(2), () -> pausedStep = ClientSession.active().sim().step());
        add("practice_still", ticks(30), () -> {
            ClientSession session = ClientSession.active();
            checks.put("help_pauses_practice", session.practice && session.paused() && session.sim().step() == pausedStep);
            shot(mc, "practice_help");
        });
        add("practice_unpause", ticks(2), () -> closeHelp(mc));
        add("practice_resumed", () -> ClientSession.active() != null && !ClientSession.active().countingIn(), () -> {});
        add("practice_continues", ticks(10), () -> {
            checks.put("help_resume_continues_practice", ClientSession.active().sim().step() > pausedStep);
            ((GameScreen) mc.screen).onClose();
        });

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
        add("finishing_prepare", () -> buttonReady(mc, "staging.immersive_brewing.prepare"), () ->
                checks.put("prepare_button", VisualChecks.click(mc.screen, "staging.immersive_brewing.prepare")));
        add("finishing_start", () -> buttonReady(mc, "finishing.immersive_brewing.finish"), () ->
                checks.put("finish_button", VisualChecks.click(mc.screen, "finishing.immersive_brewing.finish")));
        add("finishing_back", ticks(8), () -> mc.screen.onClose());
        add("finishing_progress", () -> mc.screen instanceof KettleScreen && view(mc, kettlePos()).view().operation() == com.otectus.immersivebrewing.batch.Operation.FINISH,
                () -> shot(mc, "finishing_progress"));
        add("finishing_seal", ticks(3), () -> checks.put("finish_seal_button", VisualChecks.click(mc.screen, "screen.immersive_brewing.seal")));
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
        add("finishing_collect", () -> checks.containsKey("finish_exact_outputs"), () ->
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
                VisualChecks.click(mc.screen, "notebook.immersive_brewing.tab.start");
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
            });
            add("plans_shot_" + language, ticks(3), () -> shot(mc, language + "_plans"));
            add("plans_scroll_" + language, ticks(2), () -> VisualChecks.scrollEnd(mc.screen, "detail", checks, language + "_plans", true));
            add("plans_bottom_" + language, ticks(3), () -> shot(mc, language + "_plans_bottom"));
            for (SessionKind kind : SessionKind.values()) {
                add("practice_" + language + "_" + kind, ticks(2), () -> com.otectus.immersivebrewing.client.ClientPacketHandler.practice(kind));
                add("count_in_" + language + "_" + kind, () -> mc.screen instanceof GameScreen && ClientSession.active() != null, () -> {
                    ClientSession.active().setPaused(true);
                    checks.put("initial_count_in_" + language + "_" + kind, ClientSession.active().countingIn() && !ClientSession.active().resuming());
                    VisualChecks.statusText(mc.screen, checks, language + "_" + kind);
                    shot(mc, language + "_" + kind.name().toLowerCase(java.util.Locale.ROOT) + "_count_in");
                });
                add("count_in_continue_" + language + "_" + kind, ticks(2), () -> ClientSession.active().setPaused(false));
                add("practice_ready_" + language + "_" + kind, () -> mc.screen instanceof GameScreen && ClientSession.active() != null
                        && !ClientSession.active().countingIn(), () -> ClientSession.active().setPaused(true));
                add("practice_shot_" + language + "_" + kind, ticks(3), () -> shot(mc, language + "_" + kind.name().toLowerCase(java.util.Locale.ROOT)));
                if (kind == SessionKind.BREWING) for (ClientConfig.Palette palette : ClientConfig.Palette.values())
                        for (String trend : List.of("rising", "falling", "steady")) {
                    String name = language + "_heat_" + trend + "_" + palette.name().toLowerCase(java.util.Locale.ROOT);
                    add("trend_" + name, ticks(2), () -> {
                        ClientConfig.PALETTE.set(palette);
                        ClientConfig.NUMERIC_DISPLAY.set(true);
                        VisualChecks.heatTrend(ClientSession.active(), trend);
                    });
                    add("trend_shot_" + name, ticks(2), () -> shot(mc, name));
                }
                add("presentation_reset_" + language + "_" + kind, ticks(2), () -> {
                    ClientConfig.NUMERIC_DISPLAY.set(false);
                    ClientConfig.PALETTE.set(ClientConfig.Palette.STANDARD);
                });
                add("practice_help_" + language + "_" + kind, ticks(2), () -> openHelp(mc));
                add("practice_help_shot_" + language + "_" + kind, ticks(3), () -> shot(mc, language + "_" + kind.name().toLowerCase(java.util.Locale.ROOT) + "_help"));
                add("practice_help_next_" + language + "_" + kind, ticks(2), () -> {
                    checks.put("help_mouse_next_" + language + "_" + kind, VisualChecks.click(mc.screen, "screen.immersive_brewing.help_next"));
                    checks.put("help_mouse_back_" + language + "_" + kind, VisualChecks.click(mc.screen, "screen.immersive_brewing.help_back"));
                });
                add("practice_resume_" + language + "_" + kind, ticks(2), () -> closeHelp(mc));
                add("practice_resume_shot_" + language + "_" + kind, ticks(2), () -> {
                    checks.put("resume_count_in_" + language + "_" + kind, ClientSession.active().countingIn() && ClientSession.active().resuming());
                    shot(mc, language + "_" + kind.name().toLowerCase(java.util.Locale.ROOT) + "_resume");
                });
                add("practice_close_" + language + "_" + kind, ticks(2), () -> mc.setScreen(null));
            }
            for (var stage : com.otectus.immersivebrewing.client.tutorial.GuidedPractice.Stage.values()) {
                add("guided_" + language + "_" + stage, ticks(2), () -> {
                    var session = com.otectus.immersivebrewing.client.PracticeSetup.createGuided(stage);
                    mc.setScreen(new KettleBrewScreen(session));
                    session.setPaused(true);
                });
                add("guided_shot_" + language + "_" + stage, ticks(2), () -> {
                    Component cue = com.otectus.immersivebrewing.client.tutorial.GuidedPractice.cue(ClientSession.active());
                    checks.put("guided_cue_fits_" + language + "_" + stage,
                            com.otectus.immersivebrewing.client.screen.Ui.lines(cue, 288) <= 2);
                    shot(mc, language + "_guided_" + stage.name().toLowerCase(java.util.Locale.ROOT));
                });
                add("guided_close_" + language + "_" + stage, ticks(2), () -> mc.setScreen(null));
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
        mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(accessible ? 0.0 : 1.0);
        checks.put("variant", VARIANT == null ? "default" : VARIANT);
    }

    /** A game screen just opened with its tutorial: the session is paused before its count-in moved at all. */
    private static void tutorialOpened(Minecraft mc, String game) {
        ClientSession session = ClientSession.active();
        checks.put("tutorial_pauses_" + game, mc.screen instanceof GameScreen screen && screen.helpShowing() && session.paused() && session.countingIn());
        heldCountIn = session.countInProgress();
    }

    /** A while later the tutorial is still open and the count-in has not moved. */
    private static void tutorialHeld(String game) {
        ClientSession session = ClientSession.active();
        checks.put("tutorial_holds_count_in_" + game, session.countingIn() && session.countInProgress() == heldCountIn);
    }

    /** Opens a game screen's help overlay through the real key binding, if it is not showing (it pauses the game). */
    private static void openHelp(Minecraft mc) {
        if (!(mc.screen instanceof GameScreen screen) || screen.helpShowing()) return;
        var key = com.otectus.immersivebrewing.client.Keys.HELP.getKey();
        screen.keyPressed(key.getValue(), 0, 0);
        screen.keyReleased(key.getValue(), 0, 0);
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
