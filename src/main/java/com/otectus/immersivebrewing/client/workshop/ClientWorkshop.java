package com.otectus.immersivebrewing.client.workshop;

import com.mojang.blaze3d.vertex.PoseStack;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.StationNotices;
import com.otectus.immersivebrewing.client.presentation.PresentationPolicy;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.interaction.StationOffsets;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.StationEffectEvent;
import com.otectus.immersivebrewing.network.packet.WorkshopAckS2C;
import com.otectus.immersivebrewing.network.packet.WorkshopFocusS2C;
import com.otectus.immersivebrewing.network.packet.WorkshopIntentC2S;
import com.otectus.immersivebrewing.network.packet.WorkshopSnapshotS2C;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The local player's hand at a station (handoff 7): the focused station and its view epoch, the tool held (a server
 * lease), the bottle or dose cup carried (a presentation lease: nothing moves until it is tipped), the predicted tool pose,
 * its posture and the chosen effort. Sends intents with a strictly increasing sequence (the operation id an
 * acknowledgement names), samples a moving tool or carried object at most twenty times a second, and never shows a
 * resource as moved before the server says so: a refused or unanswered tip returns the object to where it belongs.
 *
 * <p>The camera ({@link WorkshopCamera}), the transfers ({@link WorkshopTransfers}) and the picture-agreeing projector
 * ({@link WorkshopProjector}) are separate; this class owns input state and transport only.
 */
public final class ClientWorkshop {
    /** The local hand, as the target table reads it. */
    public record Hand(int tool, int carried) {}

    @Nullable
    private static BlockPos focus;
    @Nullable
    private static WorkshopKind kind;
    private static int epoch = WorkshopIntent.ANY_EPOCH;
    private static int tool = -1;
    private static int carried = -1;
    private static double u;
    private static double v;
    private static double shownU;
    private static double shownV;
    private static boolean contact;
    private static boolean deep;
    private static Effort effort = Effort.NORMAL;
    private static boolean batch;
    private static boolean working;
    private static double workAngle;
    private static int sequence;
    private static long lastSampleTick = Long.MIN_VALUE / 2;
    private static double sentU = Double.NaN;
    private static double sentV = Double.NaN;
    private static boolean sentContact;
    private static boolean sentDeep;
    private static int toolSince;
    /** The carried object's position over the station (normalised block coordinates, -1..1 across the block). */
    private static double carryU;
    private static double carryV;
    private static double sentCarryU = Double.NaN;
    private static double sentCarryV = Double.NaN;
    private static long lastCarrySample = Long.MIN_VALUE / 2;
    @Nullable
    private static BlockPos returnTo;
    @Nullable
    private static WorkshopKind returnKind;
    private static long enteredAt;
    /** When the hand last changed (a tool taken, effort or posture changed): the brief hand line is shown after it. */
    private static long handChangedAt;

    // ---- focus lifecycle ----

    public static void onFocus(WorkshopFocusS2C message) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        WorkshopKind k = WorkshopKind.byId(message.kind());
        if (message.open() && k != null) {
            boolean moved = focus != null && !focus.equals(message.pos());
            if (moved) {
                // Going over to another station: nothing held here travels with the player.
                WorkshopTransfers.abandonLocal();
                resetHand();
            }
            focus = message.pos();
            kind = k;
            epoch = message.epoch();
            returnTo = null;
            enteredAt = net.minecraft.Util.getMillis();
            WorkshopCamera.begin(focus, k);
            if (!(mc.screen instanceof WorkshopScreen screen) || !screen.pos().equals(focus)) {
                mc.setScreen(new WorkshopScreen(focus, k));
            }
            PracticeBench.tryStart();
        } else if (focus != null && focus.equals(message.pos()) && (message.epoch() == epoch || message.epoch() == WorkshopIntent.ANY_EPOCH)) {
            close(message.reason().isEmpty() ? null : Component.translatable(message.reason()));
        }
    }

    /** Ends the working view locally (the server is told by the screen's own close). */
    static void close(@Nullable Component reason) {
        var mc = Minecraft.getInstance();
        PracticeBench.stop(null);
        WorkshopTransfers.abandonLocal();
        resetHand();
        focus = null;
        kind = null;
        WorkshopCamera.end();
        if (mc.screen instanceof WorkshopScreen) mc.setScreen(null);
        if (reason != null && mc.player != null) StationNotices.show(reason, false);
    }

    public static void resetHand() {
        tool = -1;
        carried = -1;
        working = false;
        contact = false;
        deep = false;
        sentU = Double.NaN;
        sentCarryU = Double.NaN;
        batch = false;
    }

    @Nullable
    public static BlockPos focus() {
        return focus;
    }

    @Nullable
    public static WorkshopKind kind() {
        return kind;
    }

    /** When the working view opened (millis), for the short exit hint. */
    public static long enteredAt() {
        return enteredAt;
    }

    /** When the hand last changed (millis). */
    public static long handChangedAt() {
        return handChangedAt;
    }

    private static void handChanged() {
        handChangedAt = net.minecraft.Util.getMillis();
    }

    public static Hand hand() {
        return new Hand(tool, carried);
    }

    public static Effort effort() {
        return effort;
    }

    public static void cycleEffort() {
        effort = effort.next();
        handChanged();
    }

    public static boolean batch() {
        return batch;
    }

    public static void toggleBatch() {
        batch = !batch;
    }

    public static void clearBatch() {
        batch = false;
    }

    public static boolean working() {
        return working;
    }

    public static void setWorking(boolean value) {
        working = value && tool >= 0;
        if (!working) {
            workCentreU = Double.NaN;
            workCentreV = Double.NaN;
        }
    }

    /** The held tool works down at the bottom of the material (handoff 8.3) rather than near its surface. */
    public static boolean deep() {
        return deep;
    }

    public static void setDeep(boolean value) {
        boolean next = value && tool >= 0;
        if (next != deep) handChanged();
        deep = next;
    }

    public static void toggleDeep() {
        setDeep(!deep);
    }

    /** Remembers to return to the working view after the drawer or the book closes. */
    static void returnAfter(BlockPos pos, WorkshopKind k) {
        returnTo = pos;
        returnKind = k;
    }

    /** Whether the drawer or the planner is open over a view that will come back (the camera keeps its framing). */
    static boolean returning() {
        return returnTo != null;
    }

    // ---- intents ----

    public static void send(int part, WorkshopAction action, int argument, boolean withBatch) {
        sendRaw(part, action, argument, (float) u, (float) v, contact, withBatch, false, false, WorkshopIntent.NO_REVISION);
    }

    /**
     * Sends a commit the sender's transfer waits on: the server answers with an acknowledgement naming the returned
     * sequence (or the practice bench answers at once). {@code setup} also accepts the recipe page seen at {@code revision}.
     */
    public static int commit(int part, WorkshopAction action, int argument, boolean withBatch, boolean setup, long revision) {
        return sendRaw(part, action, argument, (float) u, (float) v, contact, withBatch, true, setup, revision);
    }

    private static int sendRaw(int part, WorkshopAction action, int argument, float x, float z, boolean touching, boolean withBatch, boolean ack,
                               boolean setup, long revision) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || focus == null) return -1;
        int op = ++sequence;
        // A practice at this station takes every apparatus action locally; nothing about it reaches the server.
        PracticeBench.Outcome practice = PracticeBench.act(part, action, argument, x, z, touching, deep && part == tool, effort, withBatch);
        if (practice != null) {
            if (ack) WorkshopTransfers.acknowledged(focus, op, practice.accepted(), practice.reason());
            return op;
        }
        BlockEntity be = mc.level.getBlockEntity(focus);
        UUID job = be instanceof StationBlockEntity station && station.workshopView().hasUUID("job")
                ? station.workshopView().getUUID("job") : WorkshopIntent.ANY_JOB;
        int flags = WorkshopIntentC2S.flags(touching, false, withBatch, effort.ordinal()) | (ack ? WorkshopIntentC2S.ACK : 0)
                | (deep && part == tool ? WorkshopIntentC2S.DEEP : 0) | (setup ? WorkshopIntentC2S.SETUP : 0);
        ModNetwork.sendToServer(new WorkshopIntentC2S(mc.level.dimension().location(), focus, job, epoch, op, part & 63, action.ordinal(),
                Math.max(-1, Math.min(4096, argument)), Mth.clamp(x, -1.5F, 1.5F), Mth.clamp(z, -1.5F, 1.5F), flags, revision));
        return op;
    }

    /** The server's answer to one acknowledged commit. */
    public static void onAck(WorkshopAckS2C ack) {
        WorkshopTransfers.acknowledged(ack.pos(), ack.sequence(), ack.accepted(), ack.reason());
    }

    /** Moves the working view to a neighbouring station (validated there by the server like an ordinary use). */
    static void switchTo(BlockPos next) {
        if (focus == null) return;
        int packed = StationOffsets.pack(focus, next);
        if (packed < 0) return;
        putDown();
        dropCarry();
        send(0, WorkshopAction.SWITCH_STATION, packed, false);
    }

    // ---- the hand: tools ----

    /** Takes a tool: the pose starts at its working centre; the server lease is requested at once. */
    public static void takeTool(int part) {
        if (carried >= 0) dropCarry();
        tool = part;
        toolSince = 0;
        u = 0;
        v = 0;
        shownU = 0;
        shownV = 0;
        contact = false;
        deep = false;
        sentU = Double.NaN;
        send(part, WorkshopAction.GRAB, 0, false);
        handChanged();
        WorkshopTutorial.done(WorkshopTutorial.Stage.TOOL_TAKEN);
    }

    public static void putDown() {
        if (tool < 0) return;
        int part = tool;
        tool = -1;
        working = false;
        contact = false;
        deep = false;
        send(part, WorkshopAction.RELEASE, 0, false);
        WorkshopTutorial.done(WorkshopTutorial.Stage.TOOL_PUT_DOWN);
    }

    /** Sets the held tool's target on its work plane (normalised), from the mouse or the keys. */
    public static void moveTool(double nu, double nv, boolean inContact) {
        if (tool < 0) return;
        double dead = ClientConfig.get(ClientConfig.TOOL_DEAD_ZONE);
        if (Math.hypot(nu - u, nv - v) < dead && inContact == contact) return;
        u = Mth.clamp(nu, -1, 1);
        v = Mth.clamp(nv, -1, 1);
        contact = inContact;
    }

    public static void nudgeTool(double du, double dv) {
        if (tool < 0) return;
        double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
        u = Mth.clamp(u + du * s, -1, 1);
        v = Mth.clamp(v + dv * s, -1, 1);
        contact = true;
    }

    public static double toolU() {
        return u;
    }

    public static double toolV() {
        return v;
    }

    /** The smoothed pose the renderers draw (visual weight only; the server receives the unsmoothed target). */
    public static double shownU() {
        return shownU;
    }

    public static double shownV() {
        return shownV;
    }

    public static boolean contact() {
        return contact;
    }

    public static int tool() {
        return tool;
    }

    // ---- the hand: carried bottles and dose cups ----

    /**
     * Lifts a bottle or dose cup (handoff 7.3 lift): the transfer view takes it up from its place at once, and the server
     * is asked for the carry so others see it; a refusal brings it straight back.
     */
    public static void carry(int part) {
        if (focus == null || kind == null) return;
        if (tool >= 0) putDown();
        carried = part;
        double[] home = WorkshopTransfers.home(kind, part);
        carryU = home[0] / 8.0 - 1;
        carryV = home[2] / 8.0 - 1;
        sentCarryU = Double.NaN;
        int op = sendRaw(part, WorkshopAction.GRAB, 0, (float) carryU, (float) carryV, false, false, true, false, WorkshopIntent.NO_REVISION);
        WorkshopTransfers.lift(focus, kind, part, op);
    }

    /** Puts the carried object back where it belongs: nothing was transferred. */
    public static void dropCarry() {
        if (carried < 0) return;
        int part = carried;
        carried = -1;
        if (focus != null) {
            send(part, WorkshopAction.RELEASE, 0, false);
            WorkshopTransfers.putBack(focus, part, null);
        }
    }

    /**
     * Tips the carried dose or bottle over the vessel: one transaction. The tip is shown at once; the stream only flows
     * when the server accepted it, and the object returns to its place either way. {@code setup} starts the batch the
     * recipe page shows ({@code revision}: the page the player read).
     */
    public static void commitCarry(WorkshopAction action, int argument, boolean setup, long revision) {
        int source = carried;
        carried = -1;
        if (source < 0 || focus == null) return;
        int op = sendRaw(source, action, argument, (float) carryU, (float) carryV, false, false, true, setup, revision);
        WorkshopTransfers.tip(focus, source, op);
        if (action == WorkshopAction.DOSE) WorkshopTutorial.done(WorkshopTutorial.Stage.DOSE);
    }

    /** Moves the carried object over the station (model pixels of the north-authored station). */
    public static void moveCarry(double px, double pz) {
        if (carried < 0) return;
        carryU = Mth.clamp(px / 8.0 - 1, -1.45, 1.45);
        carryV = Mth.clamp(pz / 8.0 - 1, -1.45, 1.45);
    }

    public static int carried() {
        return carried;
    }

    /** A refused lift: the hand is empty again (the transfer view brings the object back). */
    static void resetCarryIf(int part) {
        if (carried == part) carried = -1;
    }

    /** The carried object's position in model pixels. */
    public static double carryX() {
        return (carryU + 1) * 8;
    }

    public static double carryZ() {
        return (carryV + 1) * 8;
    }

    // ---- per tick ----

    /** Per client tick: the keyboard work action, sampling, smoothing, reconciliation and the exits. */
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        WorkshopCamera.tick();
        if (mc.player == null || mc.level == null) {
            focus = null;
            return;
        }
        // Return to the working view when the drawer or the book closes and the station is still at hand.
        if (returnTo != null && mc.screen == null) {
            BlockPos back = returnTo;
            WorkshopKind k = returnKind;
            returnTo = null;
            if (k != null && mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(back)) < 7.5) {
                focus = back;
                kind = k;
                sendRaw(0, WorkshopAction.FOCUS, 0, 0, 0, false, false, false, false, WorkshopIntent.NO_REVISION);
            } else {
                WorkshopCamera.end();
            }
        }
        WorkshopTransfers.tick();
        if (focus == null) return;
        PracticeBench.tick();
        long now = mc.level.getGameTime();
        if (tool >= 0) {
            toolSince++;
            if (working) generateWork();
            double smoothing = ClientConfig.get(ClientConfig.TOOL_SMOOTHING);
            double follow = PresentationPolicy.current().decorativeMotion() <= 0 ? 1 : 1 - smoothing;
            shownU += (u - shownU) * follow;
            shownV += (v - shownV) * follow;
            boolean moved = Double.isNaN(sentU) || Math.abs(u - sentU) > 1e-3 || Math.abs(v - sentV) > 1e-3 || contact != sentContact || deep != sentDeep;
            // One sample per tick while moving (20 a second at most); a resting tool sends a keep-alive twice a second.
            if (moved || now - lastSampleTick >= 10) {
                lastSampleTick = now;
                sentU = u;
                sentV = v;
                sentContact = contact;
                sentDeep = deep;
                sendRaw(tool, WorkshopAction.SAMPLE, 0, (float) u, (float) v, contact, false, false, false, WorkshopIntent.NO_REVISION);
            }
            // The server did not grant the lease (someone else holds the tool): let go locally.
            if (toolSince > 20 && !PracticeBench.active(focus) && mc.level.getBlockEntity(focus) instanceof StationBlockEntity station) {
                var view = WorkshopTargets.view(station, kind);
                UUID holder = view.holderId(tool);
                if (holder == null || !holder.equals(mc.player.getUUID())) {
                    tool = -1;
                    working = false;
                    deep = false;
                }
            }
        }
        if (carried >= 0) {
            boolean moved = Double.isNaN(sentCarryU) || Math.abs(carryU - sentCarryU) > 2e-3 || Math.abs(carryV - sentCarryV) > 2e-3;
            if (moved && now - lastCarrySample >= 1 || now - lastCarrySample >= 10) {
                lastCarrySample = now;
                sentCarryU = carryU;
                sentCarryV = carryV;
                sendRaw(carried, WorkshopAction.SAMPLE, 0, (float) carryU, (float) carryV, false, false, false, false, WorkshopIntent.NO_REVISION);
            }
        }
    }

    /** The accessible work action: bounded tool movement through the same samples a mouse produces (handoff 7). */
    private static void generateWork() {
        // Target speeds in model pixels per second, the same range a comfortable mouse stroke produces.
        double px = switch (effort) {
            case GENTLE -> 10;
            case NORMAL -> 17;
            case FIRM -> 27;
        };
        if (kind == WorkshopKind.KETTLE) {
            double radius = 0.55, radiusPx = radius * (WorkshopParts.KETTLE_HX + WorkshopParts.KETTLE_HZ) / 2;
            workAngle += px / radiusPx / 20.0;
            u = Math.cos(workAngle) * radius;
            v = Math.sin(workAngle) * radius;
        } else if (tool == WorkshopParts.M_SCRAPER) {
            double radius = 0.8;
            workAngle += Math.min(px, 14) / (radius * WorkshopParts.MORTAR_R) / 20.0;
            u = Math.cos(workAngle) * radius;
            v = Math.sin(workAngle) * radius;
        } else {
            // Short grinding arcs around wherever the pestle was placed (move it with the arrow keys).
            if (Double.isNaN(workCentreU)) {
                workCentreU = Mth.clamp(u, -0.7, 0.7);
                workCentreV = Mth.clamp(v, -0.7, 0.7);
            }
            double radius = 0.3;
            workAngle += px / (radius * WorkshopParts.MORTAR_R) / 20.0;
            u = Mth.clamp(workCentreU + Math.cos(workAngle) * radius, -1, 1);
            v = Mth.clamp(workCentreV + Math.sin(workAngle) * radius, -1, 1);
        }
        contact = true;
    }

    private static double workCentreU = Double.NaN;
    private static double workCentreV = Double.NaN;

    /** The arrow keys move the centre of a running grinding arc instead of fighting the arc itself. */
    public static void moveWorkCentre(double du, double dv) {
        if (Double.isNaN(workCentreU)) return;
        double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
        workCentreU = Mth.clamp(workCentreU + du * s, -0.75, 0.75);
        workCentreV = Mth.clamp(workCentreV + dv * s, -0.75, 0.75);
    }

    // ---- snapshots and effects ----

    public static void onSnapshot(WorkshopSnapshotS2C message) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.isLoaded(message.pos())) return;
        if (level.getBlockEntity(message.pos()) instanceof StationBlockEntity station) {
            station.acceptWorkshopSnapshot(message.revision(), message.snapshot());
            WorkshopTransfers.observe(station);
        }
    }

    public static void onEffect(StationEffectEvent event) {
        WorkshopTransfers.effect(event);
        if (event.kind() == StationEffectEvent.Kind.VALVE && focus != null && focus.equals(event.pos())) WorkshopTutorial.done(WorkshopTutorial.Stage.BURNER);
    }

    public static void clear() {
        PracticeBench.clear();
        focus = null;
        kind = null;
        returnTo = null;
        epoch = WorkshopIntent.ANY_EPOCH;
        resetHand();
        WorkshopTransfers.clear();
        WorkshopCamera.reset();
        WorkshopProjector.clear();
    }

    // ---- the working view's camera hooks ----

    /** The close-up camera's field of view (the player's own everywhere else). */
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        event.setFOV(WorkshopCamera.fov(event.getFOV()));
    }

    /** No roll of its own while framing a station (handoff 5.1). */
    public static void onAngles(ViewportEvent.ComputeCameraAngles event) {
        if (WorkshopCamera.overriding()) event.setRoll(0);
    }

    // ---- picking (always through the camera the world was drawn with) ----

    /** The world ray under a screen point (GUI scale), from the rendered camera. */
    static Vec3[] ray(double mouseX, double mouseY) {
        return WorkshopProjector.ray(mouseX, mouseY);
    }

    /** A world point to screen coordinates (GUI scale), or null when it is behind the camera. */
    @Nullable
    static double[] project(Vec3 world) {
        return WorkshopProjector.project(world);
    }

    /**
     * The volume the client picks and outlines for a part: tool rests follow the left-handed presentation, everything
     * else (and every part id the server receives) is unchanged.
     */
    /**
     * The volume the cursor picks a part by at this station: as {@link #pickVolume(WorkshopPart)}, and an empty bottle
     * position only by its footing on the rail (a bottle-high empty box would hide the burner and fuel hatch behind it).
     */
    static WorkshopPart pickVolume(WorkshopTargets.View v, WorkshopPart part) {
        WorkshopPart p = pickVolume(part);
        int slot = p.id() - WorkshopParts.K_RACK;
        if (v.kind() == WorkshopKind.KETTLE && slot >= 0 && slot < com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK_SIZE
                && v.be() instanceof com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity kettle
                && kettle.inventory().getStackInSlot(com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK + slot).isEmpty()) {
            return new WorkshopPart(p.id(), p.name(), p.role(), p.x0(), p.y0(), p.z0(), p.x1(), Math.min(p.y1(), p.y0() + 2.2F), p.z1());
        }
        return p;
    }

    static WorkshopPart pickVolume(WorkshopPart part) {
        if (part.role() != WorkshopPart.Role.TOOL && part.role() != WorkshopPart.Role.LID || !ClientConfig.get(ClientConfig.MIRROR_TOOLS)) return part;
        return new WorkshopPart(part.id(), part.name(), part.role(), 16 - part.x1(), part.y0(), part.z0(), 16 - part.x0(), part.y1(), part.z1());
    }

    public static Direction facing(BlockEntity be) {
        return be.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING) ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    // ---- outlines (handoff 10: one subtle highlight distinguishes available from disabled parts) ----

    public static void renderOutlines(RenderLevelStageEvent event) {
        WorkshopProjector.capture(event);
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var mc = Minecraft.getInstance();
        if (focus == null || !(mc.screen instanceof WorkshopScreen screen) || mc.level == null) return;
        BlockEntity be = mc.level.getBlockEntity(focus);
        if (!(be instanceof StationBlockEntity) && !(be instanceof ApothecaryRackBlockEntity)) return;
        WorkshopTargets.Target target = screen.target();
        if (target == null) return;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        AABB box = WorkshopGeometry.worldBox(focus, facing(be), pickVolume(target.part())).inflate(0.002).move(-cam.x, -cam.y, -cam.z);
        var palette = ClientConfig.get(ClientConfig.PALETTE);
        float r, g, b;
        if (palette == ClientConfig.Palette.HIGH_CONTRAST) {
            r = target.available() ? 1F : 0.6F;
            g = target.available() ? 0.95F : 0.6F;
            b = target.available() ? 0.1F : 0.6F;
        } else {
            r = target.available() ? 0.95F : 0.55F;
            g = target.available() ? 0.92F : 0.52F;
            b = target.available() ? 0.78F : 0.50F;
        }
        float alpha = target.available() ? 0.85F : 0.45F;
        var buffers = mc.renderBuffers().bufferSource();
        if (PresentationPolicy.current().markerThickness() > 1) {
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box, r, g, b, alpha);
            LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box.inflate(0.006), r, g, b, alpha);
        } else {
            // Quiet corner brackets identify the selected part without a cage across the liquid or held tool.
            var line = buffers.getBuffer(RenderType.lines());
            double[] lo = {box.minX, box.minY, box.minZ}, hi = {box.maxX, box.maxY, box.maxZ};
            for (int corner = 0; corner < 8; corner++) for (int axis = 0; axis < 3; axis++) {
                double[] from = new double[3];
                for (int n = 0; n < 3; n++) from[n] = (corner & 1 << n) == 0 ? lo[n] : hi[n];
                double[] to = from.clone();
                double sign = (corner & 1 << axis) == 0 ? 1 : -1;
                to[axis] += sign * Math.min(0.09, (hi[axis] - lo[axis]) * 0.24);
                float nx = axis == 0 ? (float) sign : 0, ny = axis == 1 ? (float) sign : 0, nz = axis == 2 ? (float) sign : 0;
                line.vertex(pose.last().pose(), (float) from[0], (float) from[1], (float) from[2]).color(r, g, b, alpha)
                        .normal(pose.last().normal(), nx, ny, nz).endVertex();
                line.vertex(pose.last().pose(), (float) to[0], (float) to[1], (float) to[2]).color(r, g, b, alpha)
                        .normal(pose.last().normal(), nx, ny, nz).endVertex();
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    /**
     * Work-plane conversion: a world ray to normalised coordinates over the vessel or bowl, and whether it is over it.
     * The kettle's plane is the liquid's actual surface, so the paddle's tip, the cursor and the accepted sample agree.
     */
    @Nullable
    static double[] planePoint(BlockEntity be, Vec3 origin, Vec3 dir) {
        Direction facing = facing(be);
        if (kind == WorkshopKind.KETTLE) {
            double level = WorkshopTransfers.liquidHeight(be);
            Vec3 p = WorkshopGeometry.onPlane(focus, facing, origin, dir, level);
            if (p == null) return null;
            double nu = (p.x - WorkshopParts.KETTLE_CX) / WorkshopParts.KETTLE_HX, nv = (p.z - WorkshopParts.KETTLE_CZ) / WorkshopParts.KETTLE_HZ;
            return new double[] {nu, nv, Math.abs(nu) <= 1 && Math.abs(nv) <= 1 ? 1 : 0};
        }
        if (kind == WorkshopKind.MORTAR) {
            Vec3 p = WorkshopGeometry.onPlane(focus, facing, origin, dir, WorkshopParts.MORTAR_FLOOR + 0.8);
            if (p == null) return null;
            double nu = (p.x - WorkshopParts.MORTAR_CX) / WorkshopParts.MORTAR_R, nv = (p.z - WorkshopParts.MORTAR_CZ) / WorkshopParts.MORTAR_R;
            return new double[] {nu, nv, nu * nu + nv * nv <= 1.0 ? 1 : 0};
        }
        return null;
    }

    /** Where the cursor ray meets the carrying height over the station, in model pixels {x, y, z}, or null. */
    @Nullable
    static double[] carryPoint(BlockEntity be, Vec3 origin, Vec3 dir, double height) {
        Vec3 p = WorkshopGeometry.onPlane(be.getBlockPos(), facing(be), origin, dir, height);
        return p == null ? null : new double[] {Mth.clamp(p.x, -2, 18), height, Mth.clamp(p.z, -3.5, 17)};
    }

    private ClientWorkshop() {}
}
