package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.client.workshop.ClientWorkshop;
import com.otectus.immersivebrewing.client.workshop.WorkshopCamera;
import com.otectus.immersivebrewing.client.workshop.WorkshopKeys;
import com.otectus.immersivebrewing.client.workshop.WorkshopScreen;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets.Local;
import com.otectus.immersivebrewing.client.workshop.WorkshopTargets.Target;
import com.otectus.immersivebrewing.client.workshop.WorkshopTransfers;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.testing.KettleTender;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The real-input journeys (handoff 15.3): a first batch at a fresh kettle, a preparation at the mortar and a
 * purification at the alembic, played only with the window's mouse and keyboard through {@link RealInput}. The cursor
 * glides to where a part is drawn and clicks only once the working view itself reports that part under it, so picking
 * through the rendered camera, target sizes and the visible contact are what is tested; a part that can not be pointed
 * at is a failure, never a fallback. The station is opened with an ordinary right-click in the world, supplies come from
 * the hotbar (the supply strip), and nothing here calls the client hand, a station or the server. Never shipped.
 */
final class RealJourney {
    /** One piece of real input; true once it is finished. */
    private interface Task {
        boolean run(Minecraft mc);
    }

    private final Deque<Task> tasks = new ArrayDeque<>();
    private final Consumer<String> shooter;
    private final Consumer<String> logger;
    private BlockPos pos;
    private WorkshopKind kind;
    private String segment;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> actions = new LinkedHashMap<>();
    private int aims;
    private int ticks;
    private boolean deepHeld;
    private double angle;
    private boolean cameraMoved;
    private double cameraOffset;

    /** The longest a segment may take before it is abandoned as a failure (game ticks). */
    private static final int SEGMENT_TICKS = 20 * 360;

    RealJourney(Consumer<String> shooter, Consumer<String> logger) {
        this.shooter = shooter;
        this.logger = logger;
    }

    // ---- the segments ----

    /** Begins a segment at a station: its script runs until {@link #finished()}. */
    void begin(String name, BlockPos at, WorkshopKind k) {
        segment = name;
        pos = at.immutable();
        kind = k;
        tasks.clear();
        failures.clear();
        actions.clear();
        aims = 0;
        ticks = 0;
        aborted = false;
        visibleShare.clear();
        deepHeld = false;
        cameraMoved = false;
        cameraOffset = 0;
        RealInput.reset();
        switch (k) {
            case KETTLE -> kettle();
            case MORTAR -> mortar();
            case ALEMBIC -> alembic();
            default -> { }
        }
    }

    boolean active() {
        return segment != null && !tasks.isEmpty();
    }

    boolean finished() {
        return segment != null && tasks.isEmpty();
    }

    String segment() {
        return segment;
    }

    List<String> failures() {
        return failures;
    }

    /** What the player had to do, by kind of action (the experienced-batch count of handoff 15.3). */
    Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        out.put("input", RealInput.counts());
        out.put("clicks", RealInput.clicks);
        out.put("keys", RealInput.keys);
        out.put("scrolls", RealInput.scrolls);
        out.put("pointer_moves", RealInput.moves);
        out.put("pointer_displaced_by_desktop", RealInput.displaced);
        out.put("parts_pointed_at", aims);
        out.put("actions", new LinkedHashMap<>(actions));
        out.put("camera_moved", cameraMoved);
        out.put("camera_offset_blocks", Math.round(cameraOffset * 100) / 100.0);
        Map<String, Double> shares = new LinkedHashMap<>();
        for (var e : visibleShare.entrySet()) {
            WorkshopPart p = WorkshopParts.get(kind, e.getKey());
            shares.put(p == null ? String.valueOf(e.getKey()) : p.name() + "#" + e.getKey(), Math.round(e.getValue() * 100) / 100.0);
        }
        out.put("visible_share_of_box", shares);
        out.put("failures", new ArrayList<>(failures));
        return out;
    }

    void end() {
        releaseDeep();
        segment = null;
        tasks.clear();
    }

    void tick(Minecraft mc) {
        if (segment == null || tasks.isEmpty()) return;
        ticks++;
        if (mc.screen instanceof WorkshopScreen && WorkshopCamera.phase() == WorkshopCamera.Phase.WORKING && mc.player != null) {
            // The close-up camera translates: where the frame was rendered from, against the player's own eyes.
            double offset = com.otectus.immersivebrewing.client.workshop.WorkshopProjector.camera().distanceTo(mc.player.getEyePosition());
            cameraOffset = Math.max(cameraOffset, offset);
            cameraMoved |= offset > 0.25;
        }
        if (ticks > SEGMENT_TICKS && !aborted) {
            fail("the segment took longer than " + SEGMENT_TICKS / 20 + " s");
            return;
        }
        Task t = tasks.pollFirst();
        if (!t.run(mc)) tasks.addFirst(t);
    }

    private boolean aborted;

    // ---- the kettle: three Swiftness from water, with no planner (handoff 3) ----

    private void kettle() {
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(pause(4));
        add(useInWorld());
        add(waitUntil(mc -> mc.screen instanceof WorkshopScreen && WorkshopCamera.phase() == WorkshopCamera.Phase.WORKING, 120, "view_opened"));
        add(pause(6));
        add(shot("open"));
        // Supplies from the hotbar into their physical places: three bottles, wart, sugar, fuel and the paddle.
        int[][] placements = {{GLFW.GLFW_KEY_1, WorkshopParts.K_RACK}, {GLFW.GLFW_KEY_2, WorkshopParts.K_RACK + 1}, {GLFW.GLFW_KEY_3, WorkshopParts.K_RACK + 2},
                {GLFW.GLFW_KEY_4, WorkshopParts.K_TRAY}, {GLFW.GLFW_KEY_5, WorkshopParts.K_TRAY + 1}, {GLFW.GLFW_KEY_6, WorkshopParts.K_FUEL}};
        for (int[] p : placements) {
            add(key(p[0], "choose_supply"));
            add(pause(3));
            add(click(p[1], t -> t.action() == WorkshopAction.INSERT && t.available(), "place"));
            add(pause(4));
        }
        // The paddle stays on its rest between batches: only a kettle without one is given the paddle from the hotbar.
        add(mc -> {
            if (mc.level.getBlockEntity(pos) instanceof BrewersKettleBlockEntity k && k.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE).isEmpty()) {
                insert(key(GLFW.GLFW_KEY_7, "choose_supply"), pause(3),
                        click(WorkshopParts.K_PADDLE, t -> t.action() == WorkshopAction.INSERT && t.available(), "place"), pause(4));
            }
            return true;
        });
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(waitUntil(mc -> view(mc) != null && view(mc).setupReady(), 100, "recipe_page_ready"));
        add(pause(10));
        add(shot("page"));
        // Lift the first bottle, bring it over the mouth and tip it: that accepts the page and pours it.
        add(click(WorkshopParts.K_RACK, t -> t.local() == Local.CARRY && t.setup(), "lift_bottle_to_begin"));
        add(tip("begin"));
        add(waitUntil(mc -> view(mc) != null && view(mc).running(), 60, "batch_started"));
        add(brew());
        add(pause(10));
        add(shot("bottled"));
        add(batchClick(WorkshopParts.K_RACK, t -> t.batchAction() == WorkshopAction.TAKE, "take_all_bottles"));
        add(waitUntil(mc -> rackCount(mc) == 0, 60, "bottles_taken"));
        add(key(GLFW.GLFW_KEY_ESCAPE, "leave"));
        add(waitUntil(mc -> mc.screen == null, 40, "view_closed"));
    }

    private Task brew() {
        return new Task() {
            boolean pourAll, fillAll;
            int cooldown;
            int idle;
            int stirTicks;

            @Override
            public boolean run(Minecraft mc) {
                WorkshopTargets.View v = view(mc);
                if (!(mc.screen instanceof WorkshopScreen) || v == null) {
                    fail("the working view closed during the batch");
                    return true;
                }
                if (!v.running()) {
                    if (rackFinished(mc) == 3 && v.state() == null) {
                        releaseDeep();
                        return true;
                    }
                    if (++idle > 200) {
                        fail("the batch is not running (state " + v.state() + ")");
                        return true;
                    }
                    return false;
                }
                idle = 0;
                CompoundTag k = v.kettle();
                if (!k.contains("stage")) return false;
                if (cooldown > 0) cooldown--;
                KettleProcess.Stage stage = KettleProcess.Stage.values()[Math.max(0, Math.min(5, k.getByte("stage")))];
                int burner = k.getByte("burner");
                int target = (int) Math.round((k.getByte("idealLow") + k.getByte("idealHigh")) / 200.0 * KettleProcess.BURNER_STEPS);
                double temp = k.getByte("temp") / 100.0, workLow = k.getByte("workLow") / 100.0;
                switch (stage) {
                    case CHARGING -> {
                        int slot = firstStaged(k);
                        if (!pourAll && slot >= 0) {
                            pourAll = true;
                            resume(this, batchClick(WorkshopParts.K_RACK + slot, t -> t.batchAction() == WorkshopAction.POUR, "pour_the_rest"),
                                    waitUntil(mc2 -> firstStaged(view(mc2).kettle()) < 0, 200, "all_poured"));
                            return true;
                        }
                        return false;
                    }
                    case READY -> {
                        releaseDeep();
                        if (ClientWorkshop.tool() >= 0) {
                            resume(this, rightClick("put_paddle_down"));
                            return true;
                        }
                        if (!fillAll) {
                            fillAll = true;
                            resume(this, batchClick(WorkshopParts.K_SPOUT, t -> t.batchAction() == WorkshopAction.FILL, "fill_all_bottles"));
                            return true;
                        }
                        return false;
                    }
                    case RESTING -> {
                        releaseDeep();
                        if (ClientWorkshop.tool() >= 0) {
                            resume(this, rightClick("rest_paddle"));
                            return true;
                        }
                        if (burner != 0) {
                            resume(this, scrollTo(WorkshopParts.K_BURNER, mc2 -> kettleByte(mc2, "burner"), 0, "ease_heat"));
                            return true;
                        }
                        return false;
                    }
                    default -> {
                        if (burner != target) {
                            releaseDeep();
                            resume(this, scrollTo(WorkshopParts.K_BURNER, mc2 -> kettleByte(mc2, "burner"), target, "turn_burner"));
                            return true;
                        }
                        if ((stage == KettleProcess.Stage.HEATING || stage == KettleProcess.Stage.WAITING_DOSE) && temp >= workLow && cooldown == 0) {
                            // Straight from stirring to the next cup: clicking it puts the paddle back on its rest.
                            releaseDeep();
                            int dose = k.getByte("doses");
                            cooldown = 10;
                            resume(this, click(WorkshopParts.K_CUP + dose, t -> t.local() == Local.CARRY, "lift_dose"), tip("dose"),
                                    waitUntil(mc2 -> kettleByte(mc2, "doses") > dose, 80, "dose_taken"));
                            return true;
                        }
                        boolean stir = stage == KettleProcess.Stage.REACTING && (k.getByte("undissolved") > 2 || k.getByte("trouble") > 15);
                        if (!stir) {
                            releaseDeep();
                            return false;
                        }
                        if (ClientWorkshop.tool() != WorkshopParts.K_PADDLE) {
                            releaseDeep();
                            resume(this, click(WorkshopParts.K_PADDLE, t -> t.local() == Local.TAKE_TOOL, "take_paddle"));
                            return true;
                        }
                        ReactionFamily family = ReactionFamily.values()[Math.max(0, Math.min(ReactionFamily.values().length - 1, k.getByte("family")))];
                        stir(mc, family);
                        if (++stirTicks == 40) shooter.accept("journey_" + segment + "_stirring");
                        // Working the paddle along the bottom is a held button (where the material asks for it).
                        if (family.posture() > 0 && !deepHeld) {
                            RealInput.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, true);
                            deepHeld = true;
                            count("press_paddle_deep");
                        }
                        return false;
                    }
                }
            }
        };
    }

    /** One tick of stirring: the cursor follows a circle on the liquid's surface at the material's comfortable pace. */
    private void stir(Minecraft mc, ReactionFamily family) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be == null) return;
        double speed = KettleTender.comfortable(family);
        double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
        angle += speed / radiusPx / 20.0;
        double level = WorkshopTransfers.liquidHeight(be);
        double[] at = RealInput.screenOf(pos, WorkshopParts.KETTLE_CX + Math.cos(angle) * radius * WorkshopParts.KETTLE_HX, level,
                WorkshopParts.KETTLE_CZ + Math.sin(angle) * radius * WorkshopParts.KETTLE_HZ);
        if (RealInput.onScreen(at)) RealInput.moveGui(at[0], at[1]);
    }

    private void releaseDeep() {
        if (!deepHeld) return;
        RealInput.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
        deepHeld = false;
    }

    /** Glides a carried bottle or cup over the vessel's mouth and tips it there (a click once the view offers the tip). */
    private Task tip(String what) {
        double[][] offsets = {{0, 0}, {0, -1.5}, {-1, 0}, {1, 0}, {0, 1.5}};
        return new Task() {
            int index;
            int step;
            int steps;
            boolean shotTaken;
            double[] from;
            double[] to;

            @Override
            public boolean run(Minecraft mc) {
                if (!(mc.screen instanceof WorkshopScreen screen)) {
                    fail("tip_" + what + ": the view closed");
                    return true;
                }
                if (ClientWorkshop.carried() < 0) {
                    fail("tip_" + what + ": nothing was carried");
                    return true;
                }
                if (to == null) {
                    to = RealInput.screenOf(pos, WorkshopParts.KETTLE_CX + offsets[index][0], WorkshopTransfers.CARRY_Y,
                            WorkshopParts.KETTLE_CZ + offsets[index][1]);
                    if (!RealInput.onScreen(to)) {
                        fail("tip_" + what + ": the mouth is not on screen");
                        return true;
                    }
                    from = RealInput.cursorGui();
                    // The carry itself travels at a hand's pace, so the bottle is seen to come over the mouth; retries hop.
                    steps = index == 0 ? GLIDE * 2 : 1;
                    step = 0;
                }
                if (step < steps) {
                    step++;
                    double f = ease(step / (double) steps);
                    RealInput.moveGui(from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f);
                    return false;
                }
                Target t = screen.target();
                if (t != null && t.part().id() == WorkshopParts.K_VESSEL && t.local() == Local.COMMIT_CARRY) {
                    if (!shotTaken && actions.getOrDefault("tip_" + what, 0) == 0) {
                        // The carried object over the mouth, as the player sees it before tipping (once per kind).
                        shotTaken = true;
                        shooter.accept("journey_" + segment + "_carry_" + what);
                        return false;
                    }
                    RealInput.click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    count("tip_" + what);
                    return true;
                }
                if (++index >= offsets.length) {
                    fail("tip_" + what + ": the vessel never offered the tip (hovered " + describe(t) + ")");
                    return true;
                }
                to = null;
                return false;
            }
        };
    }

    // ---- the mortar: a preparation with the pestle and scraper, finished at its endpoint ----

    private void mortar() {
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(pause(4));
        add(useInWorld());
        add(waitUntil(mc -> mc.screen instanceof WorkshopScreen && WorkshopCamera.phase() == WorkshopCamera.Phase.WORKING, 120, "view_opened"));
        add(pause(6));
        // The material from the hotbar into the bowl, all of it at once (the batch action names the quantity).
        add(key(GLFW.GLFW_KEY_1, "choose_supply"));
        add(pause(3));
        add(batchClick(WorkshopParts.M_BOWL, t -> t.batchAction() == WorkshopAction.INSERT, "place_all_material"));
        add(pause(4));
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(waitUntil(mc -> view(mc) != null && view(mc).snapshot().getBoolean("preparable"), 60, "material_profiled"));
        add(pause(6));
        add(shot("loaded"));
        add(grind());
        add(waitUntil(mc -> shelf(mc) > 0, 60, "packets_on_shelf"));
        add(pause(8));
        add(shot("packaged"));
        add(click(WorkshopParts.M_SHELF, t -> t.action() == WorkshopAction.TAKE, "take_packets"));
        add(pause(6));
        add(key(GLFW.GLFW_KEY_ESCAPE, "leave"));
        add(waitUntil(mc -> mc.screen == null, 40, "view_closed"));
    }

    private Task grind() {
        double[][] centres = {{0, 0}, {0.7, 0}, {0, 0.7}, {-0.7, 0}, {0, -0.7}};
        return new Task() {
            int folding;
            int beat;

            @Override
            public boolean run(Minecraft mc) {
                WorkshopTargets.View v = view(mc);
                if (!(mc.screen instanceof WorkshopScreen screen) || v == null) return false;
                CompoundTag m = v.mortar();
                if (v.state() == null) {
                    if (shelf(mc) > 0) return true;
                    if (!v.snapshot().getBoolean("preparable")) return false;
                    if (ClientWorkshop.tool() != WorkshopParts.M_PESTLE) {
                        resume(this, click(WorkshopParts.M_PESTLE, t -> t.local() == Local.TAKE_TOOL, "take_pestle"));
                        return true;
                    }
                    // Touching the material with the pestle begins the preparation.
                    bowl(0.1, 0.05);
                    return false;
                }
                if (!v.running() || !m.contains("zones")) return false;
                byte[] zones = m.getByteArray("zones");
                int min = 100, max = 0;
                for (byte z : zones) {
                    min = Math.min(min, z);
                    max = Math.max(max, z);
                }
                int processed = m.getByte("processed"), clump = m.getByte("clump");
                boolean done = m.getBoolean("usable") && processed >= m.getByte("endpointLow") + 3 && max - min <= 10 && clump < 10;
                if (done) {
                    if (ClientWorkshop.tool() >= 0) {
                        resume(this, rightClick("put_tool_down"));
                        return true;
                    }
                    insert(click(WorkshopParts.M_CUP, t -> t.action() == WorkshopAction.FINISH, "finish_at_endpoint"));
                    return true;
                }
                boolean uneven = (max - min > 12 && processed > 20) || clump > 10;
                if (uneven && folding == 0 && ClientWorkshop.tool() == WorkshopParts.M_PESTLE) {
                    folding = 24;
                    resume(this, rightClick("put_pestle_down"), click(WorkshopParts.M_SCRAPER, t -> t.local() == Local.TAKE_TOOL, "take_scraper"));
                    return true;
                }
                if (folding > 0) {
                    if (ClientWorkshop.tool() != WorkshopParts.M_SCRAPER) {
                        resume(this, click(WorkshopParts.M_SCRAPER, t -> t.local() == Local.TAKE_TOOL, "take_scraper"));
                        return true;
                    }
                    angle += 0.2;
                    bowl(Math.cos(angle) * 0.8, Math.sin(angle) * 0.8);
                    if (--folding == 0) {
                        resume(this, rightClick("put_scraper_down"), click(WorkshopParts.M_PESTLE, t -> t.local() == Local.TAKE_TOOL, "take_pestle"));
                        return true;
                    }
                    return false;
                }
                if (ClientWorkshop.tool() != WorkshopParts.M_PESTLE) {
                    resume(this, click(WorkshopParts.M_PESTLE, t -> t.local() == Local.TAKE_TOOL, "take_pestle"));
                    return true;
                }
                beat++;
                double[] c = centres[(beat / 30) % centres.length];
                if (beat % 30 == 1) {
                    bowl(c[0], c[1]);
                } else if (beat % 30 == 2) {
                    // A press is a click on the material under the pestle (the view says so before it is sent).
                    Target t = screen.target();
                    if (t != null && t.part().id() == WorkshopParts.M_BOWL && t.action() == WorkshopAction.PRESS) {
                        RealInput.click(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                        count("press");
                    } else {
                        count("press_not_offered");
                    }
                } else {
                    angle += 1.0;
                    bowl(c[0] + Math.cos(angle) * 0.15, c[1] + Math.sin(angle) * 0.15);
                }
                return false;
            }
        };
    }

    /** Points at the mortar's work surface (normalised bowl coordinates). */
    private void bowl(double nu, double nv) {
        double[] at = RealInput.screenOf(pos, WorkshopParts.MORTAR_CX + nu * WorkshopParts.MORTAR_R, WorkshopParts.MORTAR_FLOOR + 0.8,
                WorkshopParts.MORTAR_CZ + nv * WorkshopParts.MORTAR_R);
        if (RealInput.onScreen(at)) RealInput.moveGui(at[0], at[1]);
    }

    // ---- the alembic: charge, choose the operation at the fitting, control, collect ----

    private void alembic() {
        add(key(GLFW.GLFW_KEY_5, "choose_bucket"));
        add(pause(4));
        // Coolant from a water bucket, poured with an ordinary use in the world.
        add(useInWorld());
        add(waitUntil(mc -> StationReads.coolant(mc, pos) >= 1000, 60, "coolant_filled"));
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(pause(4));
        add(useInWorld());
        add(waitUntil(mc -> mc.screen instanceof WorkshopScreen && WorkshopCamera.phase() == WorkshopCamera.Phase.WORKING, 120, "view_opened"));
        add(pause(6));
        for (int key : new int[] {GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3}) {
            add(key(key, "choose_supply"));
            add(pause(3));
            add(click(WorkshopParts.A_INPUT, t -> t.action() == WorkshopAction.INSERT, "place_potion"));
            add(pause(4));
        }
        add(key(GLFW.GLFW_KEY_4, "choose_supply"));
        add(pause(3));
        add(click(WorkshopParts.A_FUEL, t -> t.action() == WorkshopAction.INSERT, "add_fuel"));
        add(pause(4));
        add(key(GLFW.GLFW_KEY_9, "hands_empty"));
        add(pause(6));
        // The fitting shows the other operation's outcome before anything is used, and turns back.
        add(mc -> {
            WorkshopTargets.View v = view(mc);
            if (v != null && v.snapshot().getBoolean("reclaimAllowed")) {
                insert(click(WorkshopParts.A_MODE, t -> t.local() == Local.TOGGLE, "turn_fitting_to_reclaim"),
                        waitUntil(mc2 -> view(mc2).snapshot().getBoolean("reclaimMode"), 40, "reclaim_selected"),
                        pause(10), shot("reclaim_selected"),
                        click(WorkshopParts.A_MODE, t -> t.local() == Local.TOGGLE, "turn_fitting_to_purify"),
                        waitUntil(mc2 -> !view(mc2).snapshot().getBoolean("reclaimMode"), 40, "purify_selected"));
            }
            return true;
        });
        add(pause(6));
        add(shot("charged"));
        add(click(WorkshopParts.A_CHARGE, t -> t.action() == WorkshopAction.FINISH, "charge"));
        add(waitUntil(mc -> view(mc) != null && view(mc).running(), 60, "still_running"));
        add(still());
        add(pause(10));
        add(shot("collected"));
        add(batchClick(WorkshopParts.A_RECEIVER, t -> t.batchAction() == WorkshopAction.TAKE, "take_all_output"));
        add(pause(8));
        add(key(GLFW.GLFW_KEY_ESCAPE, "leave"));
        add(waitUntil(mc -> mc.screen == null, 40, "view_closed"));
    }

    private Task still() {
        return new Task() {
            @Override
            public boolean run(Minecraft mc) {
                WorkshopTargets.View v = view(mc);
                if (!(mc.screen instanceof WorkshopScreen) || v == null) return false;
                if (!v.running()) return v.state() == null && StationReads.outputs(mc, pos) > 0;
                CompoundTag a = v.alembic();
                if (!a.getBoolean("clamped")) {
                    resume(this, click(WorkshopParts.A_CLAMP, t -> t.local() == Local.TOGGLE, "close_clamp"),
                            waitUntil(mc2 -> view(mc2).alembic().getBoolean("clamped"), 40, "clamped"));
                    return true;
                }
                if (a.getByte("burner") != 3) {
                    resume(this, scrollTo(WorkshopParts.A_BURNER, mc2 -> view(mc2).alembic().getByte("burner"), 3, "turn_burner"));
                    return true;
                }
                if (a.getByte("valve") != 2) {
                    resume(this, scrollTo(WorkshopParts.A_VALVE, mc2 -> view(mc2).alembic().getByte("valve"), 2, "open_coolant"));
                    return true;
                }
                boolean clear = a.getByte("condensate") == AlembicProcess.Condensate.CLEAR.ordinal();
                if (a.getBoolean("receiver") != clear) {
                    resume(this, click(WorkshopParts.A_DIVERTER, t -> t.local() == Local.TOGGLE, clear ? "divert_to_receiver" : "divert_to_waste"),
                            waitUntil(mc2 -> view(mc2).alembic().getBoolean("receiver") == clear || !view(mc2).running(), 40, "diverted"));
                    return true;
                }
                return false;
            }
        };
    }

    // ---- the building blocks ----

    private static final int GLIDE = 6;

    private void add(Task t) {
        tasks.addLast(t);
    }

    /** Runs {@code next} before anything else, then {@code self} again (a reactive task resuming after its sub-steps). */
    private void resume(Task self, Task... next) {
        tasks.addFirst(self);
        insert(next);
    }

    /** Runs {@code next} before anything else. */
    private void insert(Task... next) {
        for (int i = next.length - 1; i >= 0; i--) tasks.addFirst(next[i]);
    }

    private void count(String action) {
        actions.merge(action, 1, Integer::sum);
        logger.accept("journey " + segment + " t" + ticks + ": " + action);
    }

    /** A failed step ends the segment at once (what follows depends on it): the view is left and the failure reported. */
    private void fail(String what) {
        failures.add(segment + ": " + what);
        logger.accept("journey " + segment + " FAILED t" + ticks + ": " + what);
        if (aborted) return;
        aborted = true;
        releaseDeep();
        tasks.clear();
        shooter.accept("journey_" + segment + "_failed");
        tasks.addLast(mc -> {
            if (ClientWorkshop.carried() >= 0 || ClientWorkshop.tool() >= 0) RealInput.click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            return true;
        });
        tasks.addLast(pause(4));
        tasks.addLast(mc -> {
            if (mc.screen != null) RealInput.key(GLFW.GLFW_KEY_ESCAPE);
            return true;
        });
        int[] left = {40};
        tasks.addLast(mc -> mc.screen == null || --left[0] <= 0);
    }

    private Task key(int key, String action) {
        return mc -> {
            RealInput.key(key);
            count(action);
            return true;
        };
    }

    private Task pause(int n) {
        int[] left = {n};
        return mc -> --left[0] <= 0;
    }

    private Task shot(String name) {
        return mc -> {
            shooter.accept("journey_" + segment + "_" + name);
            return true;
        };
    }

    private Task waitUntil(Predicate<Minecraft> condition, int timeout, String what) {
        int[] left = {timeout};
        return mc -> {
            if (condition.test(mc)) return true;
            if (--left[0] <= 0) {
                fail("waited for " + what);
                return true;
            }
            return false;
        };
    }

    /** An ordinary right-click in the world on the station the crosshair rests on (the player was placed facing it). */
    private Task useInWorld() {
        return mc -> {
            if (mc.screen != null) return false;
            if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(pos)) {
                fail("the crosshair is not on the station (" + mc.hitResult + ")");
                return true;
            }
            RealInput.click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            count("use_in_world");
            return true;
        };
    }

    private Task rightClick(String action) {
        return mc -> {
            RealInput.click(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            count(action);
            return true;
        };
    }

    private Task click(int part, Predicate<Target> ok, String action) {
        return new Aim(part, ok, action, false, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** The batch action: the batch key, then a click on the part (the label names the quantity first). */
    private Task batchClick(int part, Predicate<Target> ok, String action) {
        return new Aim(part, ok, action, true, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** Points at a control and turns the wheel one notch at a time until the server reports {@code value}. */
    private Task scrollTo(int part, IntSupplierOf current, int value, String action) {
        return new Aim(part, t -> t.local() == Local.CONTROL, action, false, -1) {
            int wait;
            int notches;

            @Override
            boolean act(Minecraft mc, Target t) {
                int now = current.get(mc);
                if (now == value) return true;
                if (wait > 0) {
                    wait--;
                    return false;
                }
                if (++notches > 24) {
                    fail(action + ": the control stayed at " + now);
                    return true;
                }
                RealInput.scroll(value > now ? 1 : -1);
                count(action);
                wait = 4;
                return false;
            }
        };
    }

    interface IntSupplierOf {
        int get(Minecraft mc);
    }

    /**
     * Glides the cursor to a part and acts only when the working view itself reports that part (with the expected
     * action) under the cursor. Tries a few points across the part's box; a part that can not be pointed at fails.
     */
    private class Aim implements Task {
        private final int part;
        private final Predicate<Target> ok;
        private final String action;
        private final boolean batch;
        private final int button;
        private List<double[]> candidates;
        private int index;
        private int step;
        private int lag;
        private int reaims;
        private boolean acting;
        private double[] from;
        private double[] to;
        private final List<String> seen = new ArrayList<>();

        Aim(int part, Predicate<Target> ok, String action, boolean batch, int button) {
            this.part = part;
            this.ok = ok;
            this.action = action;
            this.batch = batch;
            this.button = button;
        }

        /** After the part is under the cursor: true when finished. The default clicks once. */
        boolean act(Minecraft mc, Target t) {
            if (batch) RealInput.key(WorkshopKeys.BATCH.getKey().getValue());
            RealInput.click(button);
            count(action);
            return true;
        }

        @Override
        public boolean run(Minecraft mc) {
            if (!(mc.screen instanceof WorkshopScreen screen)) {
                fail(action + ": the view closed");
                return true;
            }
            if (acting) {
                // The desktop's own pointer can reach the window (a refocus, a stray movement): put the cursor back on
                // the verified point and act only once the view reports the part under it again.
                if (RealInput.displaced() || screen.target() == null || screen.target().part().id() != part) {
                    RealInput.moveGui(to[0], to[1]);
                    if (++reaims > 40) {
                        fail(action + ": the cursor kept leaving part " + part);
                        return true;
                    }
                    return false;
                }
                return act(mc, screen.target());
            }
            if (candidates == null) {
                candidates = candidates(part);
                if (candidates.isEmpty()) {
                    fail(action + ": part " + part + " is not visible anywhere from the camera");
                    return true;
                }
                from = RealInput.cursorGui();
                to = candidates.get(0);
                step = 0;
            }
            if (step < GLIDE) {
                step++;
                double f = ease(step / (double) GLIDE);
                RealInput.moveGui(from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f);
                return false;
            }
            Target t = screen.target();
            if (t != null && t.part().id() == part) {
                if (ok == null || ok.test(t)) {
                    aims++;
                    acting = true;
                    return act(mc, t);
                }
                // The right part, but the view does not offer the action yet: the state may lag a snapshot behind.
                if (++lag < 12) return false;
            }
            seen.add(t == null ? "-" : t.part().name() + (t.part().id() == part ? "(" + t.local() + "/" + t.action() + ")" : ""));
            if (++index >= candidates.size()) {
                fail(action + ": part " + part + " was never offered at " + candidates.size() + " points (hovered " + seen + "; last "
                        + describe(t) + "; holding " + Minecraft.getInstance().player.getMainHandItem() + ")");
                return true;
            }
            from = to;
            to = candidates.get(index);
            step = GLIDE - 1;
            lag = 0;
            return false;
        }
    }

    /**
     * Where a part can be pointed at: screen points across its projected box at which the working view itself would pick
     * it (as a player looks for the part's visible side), nearest the middle of that visible area first. Empty when the
     * part is entirely hidden from the camera, which is a composition failure, not something to work around.
     */
    private List<double[]> candidates(int part) {
        WorkshopPart p = WorkshopParts.get(kind, part);
        List<double[]> out = new ArrayList<>();
        if (p == null || !(Minecraft.getInstance().screen instanceof WorkshopScreen screen)) return out;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            double[] at = RealInput.screenOf(pos, (c & 1) == 0 ? p.x0() : p.x1(), (c & 2) == 0 ? p.y0() : p.y1(), (c & 4) == 0 ? p.z0() : p.z1());
            if (at == null) continue;
            minX = Math.min(minX, at[0]);
            maxX = Math.max(maxX, at[0]);
            minY = Math.min(minY, at[1]);
            maxY = Math.max(maxY, at[1]);
        }
        if (minX > maxX) return out;
        int n = 13;
        double sumX = 0, sumY = 0;
        List<double[]> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double[] at = {minX + (maxX - minX) * (i + 0.5) / n, minY + (maxY - minY) * (j + 0.5) / n};
                if (!RealInput.onScreen(at) || screen.partAt(at[0], at[1]) != part) continue;
                hits.add(at);
                sumX += at[0];
                sumY += at[1];
            }
        }
        if (hits.isEmpty()) return out;
        double cx = sumX / hits.size(), cy = sumY / hits.size();
        hits.sort(java.util.Comparator.comparingDouble(h -> (h[0] - cx) * (h[0] - cx) + (h[1] - cy) * (h[1] - cy)));
        for (int i = 0; i < Math.min(6, hits.size()); i++) out.add(hits.get(i));
        visibleShare.merge(part, hits.size() / (double) (n * n), Math::min);
        return out;
    }

    /** The smallest share of its projected box each part was visible in when pointed at (the target-size evidence). */
    private final Map<Integer, Double> visibleShare = new LinkedHashMap<>();

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    private static String describe(@Nullable Target t) {
        return t == null ? "nothing" : t.part().name() + "/" + t.local() + "/" + t.action() + " \"" + t.label().getString() + "\"";
    }

    @Nullable
    private WorkshopTargets.View view(Minecraft mc) {
        if (mc.level == null) return null;
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be == null ? null : WorkshopTargets.view(be, kind);
    }

    private int kettleByte(Minecraft mc, String key) {
        WorkshopTargets.View v = view(mc);
        return v == null ? -1 : v.kettle().getByte(key);
    }

    private static int firstStaged(CompoundTag k) {
        byte[] slots = k.getByteArray("slots"), portions = k.getByteArray("portions");
        for (int i = 0; i < Math.min(slots.length, portions.length); i++) {
            if (portions[i] == KettleProcess.Portion.STAGED.ordinal()) return slots[i];
        }
        return -1;
    }

    private int rackFinished(Minecraft mc) {
        if (!(mc.level.getBlockEntity(pos) instanceof BrewersKettleBlockEntity kettle)) return 0;
        int n = 0;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.is(Items.POTION) && BrewingMetadata.read(s).isPresent()) n++;
        }
        return n;
    }

    private int rackCount(Minecraft mc) {
        if (!(mc.level.getBlockEntity(pos) instanceof BrewersKettleBlockEntity kettle)) return -1;
        int n = 0;
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) n += kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i).getCount();
        return n;
    }

    private int shelf(Minecraft mc) {
        if (!(mc.level.getBlockEntity(pos) instanceof ApothecaryMortarBlockEntity mortar)) return 0;
        int n = 0;
        for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) n += mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i).getCount();
        return n;
    }

    /** Station reads the alembic steps need (kept apart so the journey reads like the player's actions). */
    static final class StationReads {
        static int coolant(Minecraft mc, BlockPos pos) {
            return mc.level.getBlockEntity(pos) instanceof AlembicBlockEntity a ? a.workshopView().getInt("coolantMb") : 0;
        }

        static int outputs(Minecraft mc, BlockPos pos) {
            if (!(mc.level.getBlockEntity(pos) instanceof AlembicBlockEntity alembic)) return 0;
            int n = 0;
            for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) n += alembic.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i).getCount();
            return n;
        }

        private StationReads() {}
    }
}
