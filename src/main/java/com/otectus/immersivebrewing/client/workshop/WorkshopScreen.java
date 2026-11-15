package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.StationNotices;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;

/**
 * The working view's input host (handoff 5, 10): a transparent surface over the world that captures the cursor while the
 * camera frames a station. The apparatus is the interface: one subtle outline on the part under the cursor (or the
 * keyboard's selection), its short label, at most one contextual hint near the part it concerns, and a brief exit hint
 * on entry. Numbers, the full control help and diagnostics belong to the Detailed guidance level or the help key.
 *
 * <p>The cursor picks parts by the server's own interaction volumes through the camera the world was drawn with; the
 * keyboard cycles them, the parts the work needs now first. A held tool follows the cursor over its work surface (or
 * the arrow keys and the Work action) and sends bounded samples; bottles and dose cups are lifted, carried and tipped.
 * The hotbar stays visible: it is the supply strip (number keys or the wheel choose the item, a click places one,
 * a shift-click the batch). Health, armour, food and air stay visible too; damage ends the view at once.
 *
 * <p>Nothing here grants anything: every action is an intent the server validates.
 */
public class WorkshopScreen extends Screen {
    /** How long the exit hint stays after the view opens. */
    private static final long ENTRY_HINT_MS = 4500;

    private final BlockPos pos;
    private final WorkshopKind kind;
    private double mouseX = -1;
    private double mouseY = -1;
    private int hovered = -1;
    private int selected = -1;
    private boolean keyboard;
    @Nullable
    private WorkshopTargets.Target target;
    private String narrated = "";
    private int dragPart = -1;
    private double dragStartY;
    private int dragStartValue;
    private int dragLastValue;
    private int lastHurt;
    private boolean leaving;
    private boolean help;
    private boolean pressingDeep;

    public WorkshopScreen(BlockPos pos, WorkshopKind kind) {
        super(Component.translatable("workshop.immersive_brewing.title"));
        this.pos = pos;
        this.kind = kind;
    }

    public BlockPos pos() {
        return pos;
    }

    @Nullable
    public WorkshopTargets.Target target() {
        return target;
    }

    /** Whether the target was chosen with the keyboard (rather than by the cursor). */
    public boolean keyboardSelection() {
        return keyboard;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        // The world is the interface: no dimming, no panel.
    }

    @Nullable
    private BlockEntity station() {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof StationBlockEntity || be instanceof ApothecaryRackBlockEntity ? be : null;
    }

    private WorkshopTargets.View view(BlockEntity be) {
        return WorkshopTargets.view(be, kind);
    }

    // ---- per tick: exits and target refresh ----

    @Override
    public void tick() {
        var mc = Minecraft.getInstance();
        BlockEntity be = station();
        if (mc.player == null || be == null || !mc.player.isAlive()) {
            leave(Component.translatable("workshop.immersive_brewing.focus.end.gone"));
            return;
        }
        if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 7.5) {
            leave(Component.translatable("workshop.immersive_brewing.focus.end.range"));
            return;
        }
        // Damage immediately releases focus and every held tool (the server does the same).
        if (mc.player.hurtTime > lastHurt && mc.player.hurtTime > 0) {
            leave(Component.translatable("workshop.immersive_brewing.focus.end.hurt"));
            return;
        }
        lastHurt = mc.player.hurtTime;
        // Focus loss can swallow a key or button release: never keep a press or a hold-to-grab tool across it.
        if (!mc.isWindowActive()) {
            ClientWorkshop.setWorking(false);
            if (pressingDeep) {
                pressingDeep = false;
                ClientWorkshop.setDeep(false);
            }
            if (ClientConfig.get(ClientConfig.HOLD_TO_GRAB)) ClientWorkshop.putDown();
            if (dragPart >= 0) {
                ClientWorkshop.send(dragPart, WorkshopAction.RELEASE, 0, false);
                dragPart = -1;
            }
        }
        refreshTarget(be);
    }

    private void refreshTarget(BlockEntity be) {
        WorkshopTargets.View v = view(be);
        List<WorkshopPart> parts = WorkshopTargets.present(v);
        WorkshopPart part = null;
        if (keyboard && selected >= 0) {
            for (WorkshopPart p : parts) if (p.id() == selected) part = p;
        } else if (hovered >= 0) {
            for (WorkshopPart p : parts) if (p.id() == hovered) part = p;
        }
        target = part == null ? null : WorkshopTargets.describe(v, part, ClientWorkshop.hand());
        String spoken = target == null ? "" : target.label().getString();
        if (!spoken.equals(narrated)) {
            narrated = spoken;
            var mc = Minecraft.getInstance();
            if (!spoken.isEmpty() && mc.options.narrator().get() != NarratorStatus.OFF) mc.getNarrator().sayNow(target.label());
        }
    }

    // ---- mouse ----

    /**
     * The part the cursor would pick at a GUI point through the rendered camera, or -1; no side effects (the same query
     * the view's own picking makes, before a carried object's mouth override).
     */
    public int partAt(double mx, double my) {
        BlockEntity be = station();
        if (be == null) return -1;
        Vec3[] ray = ClientWorkshop.ray(mx, my);
        WorkshopTargets.View v = view(be);
        List<WorkshopPart> parts = WorkshopTargets.present(v).stream().map(p -> ClientWorkshop.pickVolume(v, p)).toList();
        Optional<WorkshopGeometry.Hit> hit = WorkshopGeometry.pick(parts, pos, ClientWorkshop.facing(be), ray[0], ray[1], 8.0, p -> true);
        return hit.map(h -> h.part().id()).orElse(-1);
    }

    private void pick(double mx, double my) {
        BlockEntity be = station();
        if (be == null) return;
        Vec3[] ray = ClientWorkshop.ray(mx, my);
        hovered = partAt(mx, my);
        // A carried bottle or cup follows the cursor over the station at carrying height; over the mouth it can be tipped.
        if (ClientWorkshop.carried() >= 0) {
            double[] at = ClientWorkshop.carryPoint(be, ray[0], ray[1], WorkshopTransfers.CARRY_Y);
            if (at != null) {
                ClientWorkshop.moveCarry(at[0], at[2]);
                if (overMouth(at[0], at[2])) hovered = kind == WorkshopKind.KETTLE ? WorkshopParts.K_VESSEL : hovered;
            }
        }
        if (ClientWorkshop.tool() >= 0 && ClientConfig.get(ClientConfig.MOUSE_STIRRING) && dragPart < 0) {
            double[] point = ClientWorkshop.planePoint(be, ray[0], ray[1]);
            if (point != null) {
                double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
                ClientWorkshop.moveTool(point[0] * s, point[1] * s, point[2] > 0);
            }
        }
    }

    /** Whether a carried object at this model-pixel point is over the kettle's open mouth. */
    private static boolean overMouth(double x, double z) {
        return Math.abs(x - WorkshopParts.KETTLE_CX) <= WorkshopParts.KETTLE_HX + 0.6 && Math.abs(z - WorkshopParts.KETTLE_CZ) <= WorkshopParts.KETTLE_HZ + 0.6;
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (Math.abs(mx - mouseX) > 0.5 || Math.abs(my - mouseY) > 0.5) keyboard = false;
        mouseX = mx;
        mouseY = my;
        pick(mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        keyboard = false;
        mouseX = mx;
        mouseY = my;
        pick(mx, my);
        BlockEntity be = station();
        if (be != null) refreshTarget(be);
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            WorkshopCamera.toggleInspect();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Right click puts the held tool down, returns a carried dose or bottle, or does the part's secondary action.
            if (ClientWorkshop.carried() >= 0) ClientWorkshop.dropCarry();
            else if (ClientWorkshop.tool() >= 0) ClientWorkshop.putDown();
            else secondary(target);
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        // With the paddle in hand, pressing the button works it down along the bottom (a toggle in one-key modes). Only a
        // click on the next dose cup or on the paddle's rest acts instead: the cup is lifted (the paddle goes back to its
        // rest first) and the rest puts the paddle down, so no separate click is needed between stirring and dosing.
        boolean handOff = target != null && (target.local() == WorkshopTargets.Local.CARRY && target.available()
                && target.part().id() >= WorkshopParts.K_CUP && target.part().id() < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS
                || target.local() == WorkshopTargets.Local.PUT_DOWN);
        if (ClientWorkshop.tool() == WorkshopParts.K_PADDLE && kind == WorkshopKind.KETTLE && !handOff) {
            if (oneKey()) ClientWorkshop.toggleDeep();
            else {
                pressingDeep = true;
                ClientWorkshop.setDeep(true);
            }
            return true;
        }
        if (target != null && target.local() == WorkshopTargets.Local.CONTROL && target.available()) {
            dragPart = target.part().id();
            dragStartY = my;
            dragStartValue = target.argument();
            dragLastValue = target.argument();
            ClientWorkshop.send(dragPart, WorkshopAction.GRAB, 0, false);
            return true;
        }
        activate(target, hasShiftDown());
        return true;
    }

    private static boolean oneKey() {
        return ClientConfig.get(ClientConfig.TOGGLE_PRESSURE) || ClientConfig.get(ClientConfig.REDUCED_INPUT);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        mouseX = mx;
        mouseY = my;
        if (dragPart >= 0) {
            int max = kind == WorkshopKind.KETTLE ? KettleProcess.BURNER_STEPS : 4;
            int value = Math.max(0, Math.min(max, dragStartValue + (int) Math.round((dragStartY - my) / 10.0)));
            if (value != dragLastValue) {
                dragLastValue = value;
                ClientWorkshop.send(dragPart, WorkshopAction.SET_CONTROL, value, false);
            }
            return true;
        }
        pick(mx, my);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragPart >= 0) {
            ClientWorkshop.send(dragPart, WorkshopAction.RELEASE, 0, false);
            dragPart = -1;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && pressingDeep) {
            pressingDeep = false;
            ClientWorkshop.setDeep(false);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && ClientConfig.get(ClientConfig.HOLD_TO_GRAB) && !ClientConfig.get(ClientConfig.REDUCED_INPUT)
                && ClientWorkshop.tool() >= 0) {
            ClientWorkshop.putDown();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        pick(mx, my);
        BlockEntity be = station();
        if (be != null) refreshTarget(be);
        if (target != null && target.local() == WorkshopTargets.Local.CONTROL && target.available()) {
            adjust(target, amount > 0 ? 1 : -1);
            return true;
        }
        if (ClientWorkshop.tool() >= 0) {
            ClientWorkshop.cycleEffort();
            return true;
        }
        // Otherwise the wheel chooses the supply in hand, as in ordinary play.
        var player = Minecraft.getInstance().player;
        if (player != null) player.getInventory().swapPaint(amount);
        return true;
    }

    private void adjust(WorkshopTargets.Target t, int delta) {
        int max = kind == WorkshopKind.KETTLE ? KettleProcess.BURNER_STEPS : 4;
        int value = Math.max(0, Math.min(max, t.argument() + delta));
        if (value != t.argument()) ClientWorkshop.send(t.part().id(), WorkshopAction.SET_CONTROL, value, false);
    }

    // ---- activation (the same path for mouse and keyboard) ----

    private void activate(@Nullable WorkshopTargets.Target t, boolean batch) {
        int carried = ClientWorkshop.carried();
        if (t == null) {
            if (carried >= 0) ClientWorkshop.dropCarry();
            return;
        }
        if (carried >= 0 && t.local() != WorkshopTargets.Local.COMMIT_CARRY) {
            // Clicking anywhere but the vessel mouth returns the carried dose or bottle: no transfer happened.
            ClientWorkshop.dropCarry();
            return;
        }
        boolean useBatch = (batch || ClientWorkshop.batch()) && t.batchAction() != null;
        ClientWorkshop.clearBatch();
        if (useBatch) {
            // The batch action names its quantity on the label; it is sent once and the server paces the work.
            if (t.setup()) ClientWorkshop.commit(t.part().id(), t.batchAction(), t.batchArgument(), true, true, t.revision());
            else ClientWorkshop.commit(t.part().id(), t.batchAction(), t.batchArgument(), true, false, com.otectus.immersivebrewing.interaction.WorkshopIntent.NO_REVISION);
            return;
        }
        if (!t.available()) {
            // An unavailable part explains itself; nothing is sent and nothing in hand is used.
            StationNotices.show(t.label(), true);
            return;
        }
        switch (t.local()) {
            case SEND -> {
                if (t.action() == WorkshopAction.INSERT || t.action() == WorkshopAction.TAKE || t.action() == WorkshopAction.FILL
                        || t.action() == WorkshopAction.FINISH) {
                    ClientWorkshop.commit(t.part().id(), t.action(), t.argument(), false, false, com.otectus.immersivebrewing.interaction.WorkshopIntent.NO_REVISION);
                } else {
                    ClientWorkshop.send(t.part().id(), t.action(), t.argument(), false);
                }
                if (t.action() == WorkshopAction.BOOK || t.action() == WorkshopAction.LEGACY_RESUME) ClientWorkshop.returnAfter(pos, kind);
            }
            case TAKE_TOOL -> ClientWorkshop.takeTool(t.part().id());
            case PUT_DOWN -> ClientWorkshop.putDown();
            case CARRY -> ClientWorkshop.carry(t.part().id());
            case COMMIT_CARRY -> ClientWorkshop.commitCarry(t.action(), t.argument(), t.setup(), t.revision());
            case TOGGLE -> ClientWorkshop.send(t.part().id(), WorkshopAction.SET_CONTROL, t.argument(), false);
            case CONTROL, NONE -> { }
        }
    }

    /** The part's secondary action (take a staged bottle back, open the full recipe search), with empty hands. */
    private void secondary(@Nullable WorkshopTargets.Target t) {
        if (t == null || t.secondaryAction() == null) return;
        ClientWorkshop.send(t.part().id(), t.secondaryAction(), 0, false);
        if (t.secondaryAction() == WorkshopAction.BOOK) ClientWorkshop.returnAfter(pos, kind);
    }

    // ---- keyboard ----

    private void cycle(int direction) {
        BlockEntity be = station();
        if (be == null) return;
        List<WorkshopPart> parts = WorkshopTargets.ordered(view(be), ClientWorkshop.hand());
        if (parts.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).id() == (keyboard ? selected : hovered)) index = i;
        index = index < 0 ? (direction > 0 ? 0 : parts.size() - 1) : Math.floorMod(index + direction, parts.size());
        selected = parts.get(index).id();
        keyboard = true;
        refreshTarget(be);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        var mc = Minecraft.getInstance();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        boolean handsFree = ClientWorkshop.tool() < 0 && ClientWorkshop.carried() < 0;
        if (handsFree && (mc.options.keyUp.matches(keyCode, scanCode) || mc.options.keyDown.matches(keyCode, scanCode)
                || mc.options.keyLeft.matches(keyCode, scanCode) || mc.options.keyRight.matches(keyCode, scanCode)
                || mc.options.keyJump.matches(keyCode, scanCode) && !WorkshopKeys.is(WorkshopKeys.WORK, keyCode, scanCode))) {
            onClose();
            return true;
        }
        // The hotbar is the supply strip: its keys choose what is in hand, as in ordinary play.
        for (int i = 0; i < 9; i++) {
            if (mc.options.keyHotbarSlots[i].matches(keyCode, scanCode) && mc.player != null) {
                mc.player.getInventory().selected = i;
                return true;
            }
        }
        BlockEntity be = station();
        if (be != null) refreshTarget(be);
        boolean toggleWork = ClientConfig.get(ClientConfig.TOGGLE_STIR) || ClientConfig.get(ClientConfig.REDUCED_INPUT);
        double step = 0.12;
        if (WorkshopKeys.is(WorkshopKeys.NEXT_TARGET, keyCode, scanCode)) cycle(1);
        else if (WorkshopKeys.is(WorkshopKeys.PREVIOUS_TARGET, keyCode, scanCode)) cycle(-1);
        else if (WorkshopKeys.is(WorkshopKeys.ACTIVATE, keyCode, scanCode)) activate(target, false);
        else if (WorkshopKeys.is(WorkshopKeys.WORK, keyCode, scanCode)) {
            if (ClientWorkshop.tool() >= 0) ClientWorkshop.setWorking(toggleWork ? !ClientWorkshop.working() : true);
            else activate(target, false);
        } else if (WorkshopKeys.is(WorkshopKeys.PUT_DOWN, keyCode, scanCode)) {
            if (ClientWorkshop.carried() >= 0) ClientWorkshop.dropCarry();
            else if (ClientWorkshop.tool() >= 0) ClientWorkshop.putDown();
            else secondary(target);
        } else if (WorkshopKeys.is(WorkshopKeys.EFFORT, keyCode, scanCode)) ClientWorkshop.cycleEffort();
        else if (WorkshopKeys.is(WorkshopKeys.POSTURE, keyCode, scanCode)) ClientWorkshop.toggleDeep();
        else if (WorkshopKeys.is(WorkshopKeys.INSPECT, keyCode, scanCode)) WorkshopCamera.toggleInspect();
        else if (WorkshopKeys.is(WorkshopKeys.HELP, keyCode, scanCode)) help = !help;
        else if (WorkshopKeys.is(WorkshopKeys.BATCH, keyCode, scanCode)) ClientWorkshop.toggleBatch();
        else if (WorkshopKeys.is(WorkshopKeys.SEAL, keyCode, scanCode)) ClientWorkshop.send(sealPart(), WorkshopAction.SEAL, 0, false);
        else if (WorkshopKeys.is(WorkshopKeys.WITHDRAW, keyCode, scanCode)) ClientWorkshop.send(sealPart(), WorkshopAction.CANCEL, 0, false);
        else if (WorkshopKeys.is(WorkshopKeys.DRAWER, keyCode, scanCode)) {
            ClientWorkshop.returnAfter(pos, kind);
            ClientWorkshop.send(0, WorkshopAction.DRAWER, 0, false);
        } else if (WorkshopKeys.is(WorkshopKeys.BOOK, keyCode, scanCode) && kind == WorkshopKind.KETTLE) {
            ClientWorkshop.returnAfter(pos, kind);
            ClientWorkshop.send(WorkshopParts.K_BOOK, WorkshopAction.BOOK, 0, false);
        } else if (WorkshopKeys.is(WorkshopKeys.NEXT_STATION, keyCode, scanCode)) nextStation();
        else if (WorkshopKeys.is(WorkshopKeys.PRACTICE, keyCode, scanCode)) practice(be);
        else if (ClientWorkshop.tool() >= 0 && moveKey(keyCode, scanCode, step)) {
            return true;
        } else if (target != null && target.local() == WorkshopTargets.Local.CONTROL && target.available()) {
            if (WorkshopKeys.is(WorkshopKeys.INCREASE, keyCode, scanCode)) adjust(target, 1);
            else if (WorkshopKeys.is(WorkshopKeys.DECREASE, keyCode, scanCode)) adjust(target, -1);
            else return super.keyPressed(keyCode, scanCode, modifiers);
        } else return super.keyPressed(keyCode, scanCode, modifiers);
        return true;
    }

    /** Arrow keys (and Page Up/Down) move a held tool, or the centre of a running grinding arc. */
    private boolean moveKey(int keyCode, int scanCode, double step) {
        double du = 0, dv = 0;
        if (WorkshopKeys.is(WorkshopKeys.TOOL_LEFT, keyCode, scanCode)) du = step;
        else if (WorkshopKeys.is(WorkshopKeys.TOOL_RIGHT, keyCode, scanCode)) du = -step;
        else if (WorkshopKeys.is(WorkshopKeys.INCREASE, keyCode, scanCode) || WorkshopKeys.is(WorkshopKeys.TOOL_FORWARD, keyCode, scanCode)) dv = step;
        else if (WorkshopKeys.is(WorkshopKeys.DECREASE, keyCode, scanCode) || WorkshopKeys.is(WorkshopKeys.TOOL_BACK, keyCode, scanCode)) dv = -step;
        else return false;
        if (ClientConfig.get(ClientConfig.MIRROR_TOOLS)) du = -du;
        if (ClientWorkshop.working()) ClientWorkshop.moveWorkCentre(du, dv);
        else ClientWorkshop.nudgeTool(du, dv);
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        boolean toggleWork = ClientConfig.get(ClientConfig.TOGGLE_STIR) || ClientConfig.get(ClientConfig.REDUCED_INPUT);
        if (!toggleWork && WorkshopKeys.is(WorkshopKeys.WORK, keyCode, scanCode)) ClientWorkshop.setWorking(false);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private int sealPart() {
        return switch (kind) {
            case KETTLE -> WorkshopParts.K_LID;
            case ALEMBIC -> WorkshopParts.A_CLAMP;
            case MORTAR -> WorkshopParts.M_BOWL;
            case RACK -> 0;
        };
    }

    /** Starts or ends a client-only practice at this idle station; the server supplies the setup (and nothing else). */
    private void practice(@Nullable BlockEntity be) {
        if (PracticeBench.active(pos)) {
            PracticeBench.stop(Component.translatable("practice.immersive_brewing.workshop.ended"));
            return;
        }
        if (kind == WorkshopKind.RACK || be == null) return;
        var sessionKind = switch (kind) {
            case KETTLE -> com.otectus.immersivebrewing.session.SessionKind.BREWING;
            case MORTAR -> com.otectus.immersivebrewing.session.SessionKind.PREPARATION;
            default -> com.otectus.immersivebrewing.session.SessionKind.DISTILLATION;
        };
        // At the mortar, practise the reagent in the bowl when it is one that is prepared.
        net.minecraft.world.item.ItemStack hint = kind == WorkshopKind.MORTAR && be instanceof com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity mortar
                ? mortar.inventory().getStackInSlot(com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity.INPUT).copyWithCount(1)
                : net.minecraft.world.item.ItemStack.EMPTY;
        com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S(
                com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S.Action.PRACTICE_KIND, "", hint, sessionKind.ordinal()));
    }

    /**
     * The explicit Next station action (handoff 9.3): the held tool is put down first, then the server moves the view to
     * the nearest other station after checking reach and protection there, and the camera glides over.
     */
    private void nextStation() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, 1, 3))) {
            if (p.equals(pos)) continue;
            BlockEntity be = mc.level.getBlockEntity(p);
            if (!(be instanceof StationBlockEntity) && !(be instanceof ApothecaryRackBlockEntity)) continue;
            double d = p.distSqr(pos);
            if (d < bestDistance) {
                bestDistance = d;
                best = p.immutable();
            }
        }
        if (best == null) {
            StationNotices.show(Component.translatable("workshop.immersive_brewing.next.none"), true);
            return;
        }
        if (PracticeBench.active(pos)) PracticeBench.stop(null);
        ClientWorkshop.switchTo(best);
    }

    // ---- leaving ----

    private void leave(@Nullable Component reason) {
        if (leaving) return;
        leaving = true;
        ClientWorkshop.putDown();
        ClientWorkshop.dropCarry();
        ClientWorkshop.send(0, WorkshopAction.LEAVE, 0, false);
        ClientWorkshop.close(reason);
    }

    @Override
    public void onClose() {
        leave(null);
        if (Minecraft.getInstance().screen == this) super.onClose();
    }

    @Override
    public void removed() {
        // Replaced by the drawer or the book, or closed: never keep a press or a tool in hand across it.
        if (dragPart >= 0) ClientWorkshop.send(dragPart, WorkshopAction.RELEASE, 0, false);
        dragPart = -1;
        if (!leaving) {
            ClientWorkshop.putDown();
            ClientWorkshop.dropCarry();
        }
        ClientWorkshop.setWorking(false);
        ClientWorkshop.setDeep(false);
    }

    // ---- drawing (handoff 10: the interface budget) ----

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        BlockEntity be = station();
        if (be == null) return;
        if (!keyboard) {
            mouseX = mx;
            mouseY = my;
        }
        Ui.Palette p = Ui.palette();
        WorkshopTargets.View v = view(be);
        ClientConfig.Guidance guidance = ClientConfig.guidance();

        int headerY=6;
        // Practice is always named: nothing in it is real.
        if (PracticeBench.active(pos)) {
            Component banner=Component.translatable("practice.immersive_brewing.workshop.banner",be.getBlockState().getBlock().getName(),WorkshopKeys.name(WorkshopKeys.PRACTICE));
            tag(g,banner,6,headerY,0xC0402818,0xFFFFE6B0);
            headerY+=font.split(banner,Math.min(width-12,360)).size()*11+4;
        }
        Component hand=handLine(guidance);
        long handAge=Util.getMillis()-ClientWorkshop.handChangedAt();
        if(hand!=null&&(guidance==ClientConfig.Guidance.DETAILED||handAge<3000||ClientWorkshop.carried()>=0)) {
            centred(g,hand,headerY,0xFFA8D8FF);
            headerY+=font.split(hand,Math.min(width-20,420)).size()*10+6;
        }
        Component reading=guidance==ClientConfig.Guidance.DETAILED||ClientConfig.get(ClientConfig.NUMERIC_DISPLAY)?reading(v):null;
        int readingWidth=Math.min(220,Math.max(80,width/3));
        int headerBottom=headerY;
        if(reading!=null) tag(g,reading,6,headerY,readingWidth,0x90000000,p.insetMuted());
        if(reading!=null) headerBottom=Math.max(headerBottom,headerY+font.split(reading,readingWidth).size()*11);
        if(help||guidance==ClientConfig.Guidance.DETAILED)
            headerBottom=Math.max(headerBottom,controls(g,p,headerY,reading==null?Math.min(width-20,420):Math.min(420,width-readingWidth-24),help));

        WorkshopTutorial.Prompt prompt=guidance==ClientConfig.Guidance.MINIMAL?null:WorkshopTutorial.prompt(v,ClientWorkshop.hand());
        Component hint=guidance==ClientConfig.Guidance.MINIMAL?null:prompt!=null?prompt.text():WorkshopGuidance.hint(v,ClientWorkshop.hand());
        int anchor=prompt!=null?prompt.part():WorkshopGuidance.anchor(v,ClientWorkshop.hand());
        Component urgent=WorkshopGuidance.urgent(v);
        boolean sameHint=target!=null&&hint!=null&&anchor==target.part().id();

        if(target!=null&&ClientConfig.get(ClientConfig.PART_HINTS)&&!sameHint) {
            double[] at=keyboard?ClientWorkshop.project(WorkshopGeometry.center(pos,ClientWorkshop.facing(be),target.part())):new double[]{mouseX,mouseY};
            if(at!=null) label(g,target,(int)at[0]+12,(int)at[1]-16,p,guidance,headerBottom+4);
        }
        // The next action has a reserved band; hover labels never compete with it over the work surface.
        if(urgent!=null) bubble(g,urgent,width/2,true);
        else if(hint!=null&&!StationNotices.visible()) bubble(g,hint,width/2,prompt!=null);

        // A brief exit hint on entry; Detailed keeps its controls reminder beside the readings.
        long since = Util.getMillis() - ClientWorkshop.enteredAt();
        if (since < ENTRY_HINT_MS && !help && hint == null && !StationNotices.visible()) {
            int alpha = since > ENTRY_HINT_MS - 600 ? (int) ((ENTRY_HINT_MS - since) / 600.0 * 255) : 255;
            centred(g, Component.translatable("workshop.immersive_brewing.hint.entry", Component.literal("Esc"), WorkshopKeys.name(WorkshopKeys.HELP)),
                    height - 62, (Math.max(8, alpha) << 24) | 0xE8E4D8);
        }
        super.render(g, mx, my, partialTick);
    }

    /**
     * Where a hint about a part goes: just off the part, never over the material. A large part (the vessel, the bowl)
     * gets it above its projected top edge (or below it when that is off screen); a small one just below its centre.
     */
    @Nullable
    private int[] beside(BlockEntity be, WorkshopPart part) {
        var facing = ClientWorkshop.facing(be);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            double[] p = ClientWorkshop.project(com.otectus.immersivebrewing.workshop.WorkshopGeometry.toWorld(pos, facing,
                    (c & 1) == 0 ? part.x0() : part.x1(), (c & 2) == 0 ? part.y0() : part.y1(), (c & 4) == 0 ? part.z0() : part.z1()));
            if (p == null) continue;
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        if (minX == Double.MAX_VALUE) return null;
        int cx = (int) ((minX + maxX) / 2);
        if (maxY - minY > height * 0.25) {
            int above = (int) minY - 26;
            return above > 24 ? new int[] {cx, above} : new int[] {cx, (int) maxY + 6};
        }
        return new int[] {cx, (int) maxY + 4};
    }

    private void label(GuiGraphics g, WorkshopTargets.Target t, int x, int y, Ui.Palette p, ClientConfig.Guidance guidance,int minY) {
        MutableComponent text = Component.empty().append(t.label());
        if (ClientWorkshop.batch() && t.batchLabel() != null) text = Component.translatable("workshop.immersive_brewing.hint.batch", t.batchLabel());
        int w = Math.min(Math.min(width - 8, 260), font.width(text) + 8);
        x = Math.max(4, Math.min(width - w - 4, x));
        y = Math.max(Math.min(minY,height-80), Math.min(height - 80, y));
        List<FormattedCharSequence> lines = new java.util.ArrayList<>(font.split(text, w - 6));
        if ((guidance == ClientConfig.Guidance.DETAILED || help) && !ClientWorkshop.batch()) {
            // The other ways to use this part, with their quantities: shift-click and right-click.
            if (t.batchLabel() != null) lines.addAll(font.split(Component.translatable("workshop.immersive_brewing.hint.shift", t.batchLabel()), w - 6));
            if (t.secondaryLabel() != null) lines.addAll(font.split(Component.translatable("workshop.immersive_brewing.hint.right", t.secondaryLabel()), w - 6));
        }
        int h = lines.size() * 10;
        g.fill(x - 3, y - 3, x + w, y + h + 1, t.available() ? 0xB0101418 : 0xB0302020);
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? (t.available() ? p.insetText() : p.insetMuted()) : p.insetMuted();
            g.drawString(font, lines.get(i), x + 1, y + i * 10, color, false);
        }
    }

    /** A footer above vanilla's health/food/experience area, clear of the burner controls even at GUI scale 4. */
    private void bubble(GuiGraphics g, Component text, int cx, boolean strong) {
        int w = Math.min(Math.min(280,width-16), Math.max(90, font.width(text) + 10));
        List<FormattedCharSequence> wrapped = font.split(text, w - 8);
        int x = Math.max(4, Math.min(width - w - 4, cx - w / 2));
        int y = Math.max(22,height-52-wrapped.size()*10);
        g.fill(x - 2, y - 2, x + w, y + wrapped.size() * 10 + 2, strong ? 0xC0203040 : 0xA0141820);
        for (int i = 0; i < wrapped.size(); i++) g.drawString(font, wrapped.get(i), x + 2, y + i * 10, strong ? 0xFFFFF0B0 : 0xFFE0E4EC, false);
    }

    private void tag(GuiGraphics g, Component text, int x, int y, int fill, int color) {
        tag(g,text,x,y,Math.min(width-12,360),fill,color);
    }

    private void tag(GuiGraphics g,Component text,int x,int y,int maxWidth,int fill,int color) {
        for (FormattedCharSequence line : font.split(text,maxWidth)) {
            g.fill(x - 3, y - 2, x + font.width(line) + 3, y + 9, fill);
            g.drawString(font, line, x, y, color, false);
            y += 11;
        }
    }

    private void centred(GuiGraphics g, Component text, int y, int argb) {
        for (FormattedCharSequence line : font.split(text, Math.min(width - 20, 420))) {
            int a = argb >>> 24;
            g.fill(width / 2 - font.width(line) / 2 - 4, y - 1, width / 2 + font.width(line) / 2 + 4, y + 9, (Math.min(0x90, a) << 24));
            g.drawString(font, line, width / 2 - font.width(line) / 2, y, argb, false);
            y += 10;
        }
    }

    /** Detailed keeps a short reminder; H expands the full legend and can hide it again. */
    private int controls(GuiGraphics g,Ui.Palette p,int y,int maxWidth,boolean expanded) {
        if (!expanded) {
            Component reminder=Component.translatable("workshop.immersive_brewing.hint.entry",Component.literal("Esc"),WorkshopKeys.name(WorkshopKeys.HELP));
            return controlLines(g,p,y,maxWidth,List.of(reminder));
        }
        MutableComponent keys = Component.translatable("workshop.immersive_brewing.hint.keys", WorkshopKeys.name(WorkshopKeys.NEXT_TARGET),
                WorkshopKeys.name(WorkshopKeys.ACTIVATE), WorkshopKeys.name(WorkshopKeys.WORK), WorkshopKeys.name(WorkshopKeys.EFFORT),
                Component.translatable(ClientWorkshop.effort().key()), WorkshopKeys.name(WorkshopKeys.BATCH),
                Component.translatable(ClientWorkshop.batch() ? "workshop.immersive_brewing.on" : "workshop.immersive_brewing.off"),
                WorkshopKeys.name(WorkshopKeys.SEAL), WorkshopKeys.name(WorkshopKeys.DRAWER));
        MutableComponent more = Component.translatable("workshop.immersive_brewing.hint.keys_more", WorkshopKeys.name(WorkshopKeys.POSTURE),
                WorkshopKeys.name(WorkshopKeys.INSPECT), WorkshopKeys.name(WorkshopKeys.NEXT_STATION), WorkshopKeys.name(WorkshopKeys.BOOK),
                WorkshopKeys.name(WorkshopKeys.PRACTICE), WorkshopKeys.name(WorkshopKeys.HELP));
        return controlLines(g,p,y,maxWidth,List.of(keys,more));
    }

    private int controlLines(GuiGraphics g,Ui.Palette p,int y,int maxWidth,List<Component> lines) {
        for (Component line : lines) {
            for (FormattedCharSequence l : font.split(line,maxWidth)) {
                g.fill(width - font.width(l) - 10, y - 1, width - 2, y + 9, 0x90000000);
                g.drawString(font, l, width - font.width(l) - 6, y, p.insetMuted(), false);
                y += 10;
            }
        }
        return y;
    }

    /**
     * While the working view is open the hotbar stays (it is the supply strip) and so do health, armour, food and air
     * (handoff 10: a calm view must not hide drowning or an attack). Only what the cursor and the apparatus replace is
     * hidden: the crosshair, the experience and jump bars.
     */
    public static void onOverlay(net.minecraftforge.client.event.RenderGuiOverlayEvent.Pre event) {
        if (!(Minecraft.getInstance().screen instanceof WorkshopScreen)) return;
        var id = event.getOverlay().id();
        for (var hidden : HIDDEN_OVERLAYS) {
            if (hidden.id().equals(id)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private static final net.minecraftforge.client.gui.overlay.VanillaGuiOverlay[] HIDDEN_OVERLAYS = {
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CROSSHAIR, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.EXPERIENCE_BAR,
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.JUMP_BAR};

    @Nullable
    private Component handLine(ClientConfig.Guidance guidance) {
        int tool = ClientWorkshop.tool();
        int carried = ClientWorkshop.carried();
        if (carried >= 0) {
            return guidance == ClientConfig.Guidance.DETAILED || help
                    ? Component.translatable("workshop.immersive_brewing.hint.carrying", WorkshopKeys.name(WorkshopKeys.PUT_DOWN)) : null;
        }
        if (tool < 0) return null;
        WorkshopPart part = WorkshopParts.get(kind, tool);
        Component name = part == null ? Component.empty() : Component.translatable(part.key());
        Component posture = Component.translatable(ClientWorkshop.deep() ? "workshop.immersive_brewing.posture.deep" : "workshop.immersive_brewing.posture.surface");
        if (guidance == ClientConfig.Guidance.MINIMAL) return null;
        Component identity=tool==WorkshopParts.K_PADDLE?name.copy().append(" · ").append(posture):name;
        return guidance==ClientConfig.Guidance.DETAILED?identity.copy().append(" · ").append(Component.translatable(ClientWorkshop.effort().key())):identity;
    }

    /** The optional precise readout for players and pack authors who want numbers (handoff 7, 10). */
    @Nullable
    private static Component reading(WorkshopTargets.View v) {
        if (v.kind() == WorkshopKind.KETTLE && v.kettle().contains("temp")) {
            var k = v.kettle();
            return Component.translatable("workshop.immersive_brewing.reading.kettle", k.getByte("temp"), k.getByte("idealLow"), k.getByte("idealHigh"),
                    k.getByte("circ"), k.getByte("progress"), k.getByte("undissolved"), k.getByte("trouble"));
        }
        if (v.kind() == WorkshopKind.MORTAR && v.mortar().contains("processed")) {
            var m = v.mortar();
            return Component.translatable("workshop.immersive_brewing.reading.mortar", m.getByte("processed"), m.getByte("endpointLow"),
                    m.getByte("endpointHigh"), m.getByte("clump"));
        }
        if (v.kind() == WorkshopKind.ALEMBIC && v.alembic().contains("fraction")) {
            var a = v.alembic();
            return Component.translatable("workshop.immersive_brewing.reading.alembic", a.getByte("temp"), a.getByte("coolant"), a.getByte("fraction"),
                    a.getByte("toReceiver"), a.getByte("toWaste"));
        }
        return null;
    }
}
