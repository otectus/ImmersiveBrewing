package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
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
 * The working view (handoff 4): a transparent surface over the world while the player stands at a station. The world
 * keeps drawing; the apparatus is the interface. The cursor picks parts by the server's own interaction volumes; the
 * keyboard cycles them in a stable order and operates them with single keys. Held tools follow the cursor over their
 * work surface (or the arrow keys and the Work action) and send bounded samples; doses and bottles are lifted and tipped.
 *
 * <p>Nothing here grants anything: every action is an intent the server validates. Escape, the inventory key or a
 * movement key (with empty hands) leaves at once; damage, death, distance and disconnection close it too, and every held
 * input is released, never resumed.
 */
public class WorkshopScreen extends Screen {
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

    private void pick(double mx, double my) {
        BlockEntity be = station();
        if (be == null) return;
        Vec3[] ray = ClientWorkshop.ray(mx, my, width, height);
        WorkshopTargets.View v = view(be);
        List<WorkshopPart> parts = WorkshopTargets.present(v).stream().map(ClientWorkshop::pickVolume).toList();
        Optional<WorkshopGeometry.Hit> hit = WorkshopGeometry.pick(parts, pos, ClientWorkshop.facing(be), ray[0], ray[1], 8.0, p -> true);
        hovered = hit.map(h -> h.part().id()).orElse(-1);
        if (ClientWorkshop.tool() >= 0 && ClientConfig.get(ClientConfig.MOUSE_STIRRING) && dragPart < 0) {
            double[] point = ClientWorkshop.planePoint(be, ray[0], ray[1]);
            if (point != null) {
                double s = ClientConfig.get(ClientConfig.TOOL_SENSITIVITY);
                ClientWorkshop.moveTool(point[0] * s, point[1] * s, point[2] > 0);
            }
        }
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Right click puts the held tool down or returns a carried dose or bottle to its place.
            if (ClientWorkshop.carried() >= 0) ClientWorkshop.dropCarry();
            else ClientWorkshop.putDown();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (target != null && target.local() == WorkshopTargets.Local.CONTROL && target.available()) {
            dragPart = target.part().id();
            dragStartY = my;
            dragStartValue = target.argument();
            dragLastValue = target.argument();
            ClientWorkshop.send(dragPart, WorkshopAction.GRAB, 0, false);
            return true;
        }
        activate(target);
        return true;
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
        if (ClientWorkshop.tool() >= 0) ClientWorkshop.cycleEffort();
        return true;
    }

    private void adjust(WorkshopTargets.Target t, int delta) {
        int max = kind == WorkshopKind.KETTLE ? KettleProcess.BURNER_STEPS : 4;
        int value = Math.max(0, Math.min(max, t.argument() + delta));
        if (value != t.argument()) ClientWorkshop.send(t.part().id(), WorkshopAction.SET_CONTROL, value, false);
    }

    // ---- activation (the same path for mouse and keyboard) ----

    private void activate(@Nullable WorkshopTargets.Target t) {
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
        if (ClientWorkshop.batch() && t.batchAction() != null) {
            ClientWorkshop.send(t.part().id(), t.batchAction(), t.batchArgument(), true);
            ClientWorkshop.toggleBatch();
            return;
        }
        if (!t.available()) {
            // An unavailable part explains itself; nothing is sent and nothing in hand is used.
            com.otectus.immersivebrewing.client.StationNotices.show(t.label(), true);
            return;
        }
        switch (t.local()) {
            case SEND -> {
                ClientWorkshop.send(t.part().id(), t.action(), t.argument(), false);
                if (t.action() == WorkshopAction.BOOK || t.action() == WorkshopAction.LEGACY_RESUME) ClientWorkshop.returnAfter(pos, kind);
            }
            case TAKE_TOOL -> ClientWorkshop.takeTool(t.part().id());
            case PUT_DOWN -> ClientWorkshop.putDown();
            case CARRY -> ClientWorkshop.carry(t.part().id());
            case COMMIT_CARRY -> ClientWorkshop.commitCarry(t.action(), t.argument());
            case TOGGLE -> ClientWorkshop.send(t.part().id(), WorkshopAction.SET_CONTROL, t.argument(), false);
            case CONTROL, NONE -> { }
        }
    }

    // ---- keyboard ----

    private List<WorkshopPart> parts() {
        BlockEntity be = station();
        return be == null ? List.of() : WorkshopTargets.present(view(be));
    }

    private void cycle(int direction) {
        List<WorkshopPart> parts = parts();
        if (parts.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).id() == (keyboard ? selected : hovered)) index = i;
        index = index < 0 ? (direction > 0 ? 0 : parts.size() - 1) : Math.floorMod(index + direction, parts.size());
        selected = parts.get(index).id();
        keyboard = true;
        BlockEntity be = station();
        if (be != null) refreshTarget(be);
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
        BlockEntity be = station();
        if (be != null) refreshTarget(be);
        boolean toggleWork = ClientConfig.get(ClientConfig.TOGGLE_STIR) || ClientConfig.get(ClientConfig.REDUCED_INPUT);
        double step = 0.12;
        if (WorkshopKeys.is(WorkshopKeys.NEXT_TARGET, keyCode, scanCode)) cycle(1);
        else if (WorkshopKeys.is(WorkshopKeys.PREVIOUS_TARGET, keyCode, scanCode)) cycle(-1);
        else if (WorkshopKeys.is(WorkshopKeys.ACTIVATE, keyCode, scanCode)) activate(target);
        else if (WorkshopKeys.is(WorkshopKeys.WORK, keyCode, scanCode)) {
            if (ClientWorkshop.tool() >= 0) ClientWorkshop.setWorking(toggleWork ? !ClientWorkshop.working() : true);
            else activate(target);
        } else if (WorkshopKeys.is(WorkshopKeys.PUT_DOWN, keyCode, scanCode)) {
            if (ClientWorkshop.carried() >= 0) ClientWorkshop.dropCarry();
            else ClientWorkshop.putDown();
        } else if (WorkshopKeys.is(WorkshopKeys.EFFORT, keyCode, scanCode)) ClientWorkshop.cycleEffort();
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

    /** The explicit Next station action: leaves this view and turns toward the nearest other station. */
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
            com.otectus.immersivebrewing.client.StationNotices.show(Component.translatable("workshop.immersive_brewing.next.none"), true);
            return;
        }
        BlockPos next = best;
        onClose();
        ClientWorkshop.lookAt(next);
        com.otectus.immersivebrewing.client.StationNotices.show(Component.translatable("workshop.immersive_brewing.next.turned",
                mc.level.getBlockState(next).getBlock().getName()), false);
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
    }

    // ---- drawing ----

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

        // Top: what the apparatus shows now, in words (handoff 10: observable guidance, colour never the only cue).
        Component title = PracticeBench.active(pos)
                ? Component.translatable("practice.immersive_brewing.workshop.banner", be.getBlockState().getBlock().getName(), WorkshopKeys.name(WorkshopKeys.PRACTICE))
                : be.getBlockState().getBlock().getName();
        Component cue = cue(v);
        int top = 6;
        List<FormattedCharSequence> lines = font.split(cue == null ? title : Component.empty().append(title).append(" — ").append(cue), Math.min(width - 20, 420));
        int boxW = 0;
        for (FormattedCharSequence line : lines) boxW = Math.max(boxW, font.width(line));
        g.fill(width / 2 - boxW / 2 - 5, top - 3, width / 2 + boxW / 2 + 5, top + lines.size() * 10 + 1, 0x90000000);
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, width / 2 - font.width(line) / 2, top, p.insetText(), false);
            top += 10;
        }
        if (v.state() == null && kind != WorkshopKind.RACK && !PracticeBench.active(pos)) {
            top = centredLines(g, Component.translatable("workshop.immersive_brewing.hint.idle", WorkshopKeys.name(WorkshopKeys.PRACTICE)), top, p.insetMuted());
        }
        if (ClientConfig.get(ClientConfig.NUMERIC_DISPLAY)) {
            Component reading = reading(v);
            if (reading != null) top = centredLines(g, reading, top, p.insetMuted());
        }

        // The selected or hovered part's short label, beside it.
        WorkshopTutorial.Prompt prompt = WorkshopTutorial.prompt(v, ClientWorkshop.hand());
        if (target != null && ClientConfig.get(ClientConfig.PART_HINTS)) {
            double[] at = keyboard ? ClientWorkshop.project(WorkshopGeometry.center(pos, ClientWorkshop.facing(be), target.part()), width, height)
                    : new double[] {mouseX, mouseY};
            if (at != null) label(g, target, (int) at[0] + 10, (int) at[1] - 14, p);
        }
        if (prompt != null) {
            WorkshopPart part = WorkshopParts.get(kind, prompt.part());
            double[] at = part == null ? null : ClientWorkshop.project(WorkshopGeometry.center(pos, ClientWorkshop.facing(be), part), width, height);
            if (at != null) {
                int x = (int) Math.max(4, Math.min(width - 160, at[0] - 80)), y = (int) Math.max(24, Math.min(height - 70, at[1] + 14));
                int w = Math.min(220, Math.max(120, font.width(prompt.text()) + 10));
                List<FormattedCharSequence> wrapped = font.split(prompt.text(), w - 8);
                g.fill(x - 2, y - 2, x + w, y + wrapped.size() * 10 + 2, 0xC0203040);
                for (int i = 0; i < wrapped.size(); i++) g.drawString(font, wrapped.get(i), x + 2, y + i * 10, 0xFFFFF0B0, false);
            }
        }

        // Bottom: the keys, by their current names (handoff 11: rebindable, no chords).
        MutableComponent keys = Component.translatable("workshop.immersive_brewing.hint.keys", WorkshopKeys.name(WorkshopKeys.NEXT_TARGET),
                WorkshopKeys.name(WorkshopKeys.ACTIVATE), WorkshopKeys.name(WorkshopKeys.WORK), WorkshopKeys.name(WorkshopKeys.EFFORT),
                Component.translatable(ClientWorkshop.effort().key()), WorkshopKeys.name(WorkshopKeys.BATCH),
                Component.translatable(ClientWorkshop.batch() ? "workshop.immersive_brewing.on" : "workshop.immersive_brewing.off"),
                WorkshopKeys.name(WorkshopKeys.SEAL), WorkshopKeys.name(WorkshopKeys.DRAWER));
        // The hotbar and status bars are hidden while the view is open (onOverlay), so the bottom edge is ours.
        List<FormattedCharSequence> keyLines = font.split(keys, width - 20);
        int ky = height - 6 - keyLines.size() * 10;
        Component hand = handLine();
        if (hand != null) {
            List<FormattedCharSequence> handLines = font.split(hand, width - 20);
            int hy = ky - 4 - handLines.size() * 10;
            for (FormattedCharSequence line : handLines) {
                g.fill(width / 2 - font.width(line) / 2 - 4, hy - 1, width / 2 + font.width(line) / 2 + 4, hy + 9, 0x90000000);
                g.drawString(font, line, width / 2 - font.width(line) / 2, hy, accentOnDark(p), false);
                hy += 10;
            }
        }
        for (FormattedCharSequence line : keyLines) {
            g.fill(width / 2 - font.width(line) / 2 - 4, ky - 1, width / 2 + font.width(line) / 2 + 4, ky + 9, 0x90000000);
            g.drawString(font, line, width / 2 - font.width(line) / 2, ky, p.insetMuted(), false);
            ky += 10;
        }
        super.render(g, mx, my, partialTick);
    }

    private void label(GuiGraphics g, WorkshopTargets.Target t, int x, int y, Ui.Palette p) {
        MutableComponent text = Component.empty().append(t.label());
        if (ClientWorkshop.batch() && t.batchLabel() != null) text = Component.translatable("workshop.immersive_brewing.hint.batch", t.batchLabel());
        int w = Math.min(width - 8, font.width(text) + 8);
        x = Math.max(4, Math.min(width - w - 4, x));
        y = Math.max(20, Math.min(height - 40, y));
        List<FormattedCharSequence> lines = font.split(text, w - 6);
        g.fill(x - 3, y - 3, x + w, y + lines.size() * 10 + 1, t.available() ? 0xB0101418 : 0xB0302020);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 1, y + i * 10, t.available() ? p.insetText() : p.insetMuted(), false);
    }

    /** Draws {@code text} centred and wrapped to the screen on dark fills; returns the next free line's top. */
    private int centredLines(GuiGraphics g, Component text, int top, int color) {
        for (FormattedCharSequence line : font.split(text, Math.min(width - 20, 420))) {
            g.fill(width / 2 - font.width(line) / 2 - 4, top, width / 2 + font.width(line) / 2 + 4, top + 11, 0x90000000);
            g.drawString(font, line, width / 2 - font.width(line) / 2, top + 1, color, false);
            top += 11;
        }
        return top + 1;
    }

    /** The accent for text on the overlay's dark fills (the panel accent is tuned for light panels). */
    private static int accentOnDark(Ui.Palette p) {
        return switch (ClientConfig.get(ClientConfig.PALETTE)) {
            case HIGH_CONTRAST -> p.accent();
            case COLORBLIND_SAFE -> 0xFF56B4E9;
            default -> 0xFFA8D8FF;
        };
    }

    /**
     * While the working view is open the hands belong to the apparatus (handoff 5): the hotbar, its status bars and the
     * crosshair would only duplicate or contradict the view, so they are not drawn. Notices still show.
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
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.HOTBAR, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CROSSHAIR,
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.PLAYER_HEALTH, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.ARMOR_LEVEL,
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.FOOD_LEVEL, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.AIR_LEVEL,
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.MOUNT_HEALTH, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.EXPERIENCE_BAR,
            net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.JUMP_BAR, net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.ITEM_NAME};

    @Nullable
    private Component handLine() {
        int tool = ClientWorkshop.tool();
        int carried = ClientWorkshop.carried();
        if (carried >= 0) return Component.translatable("workshop.immersive_brewing.hint.carrying", WorkshopKeys.name(WorkshopKeys.PUT_DOWN));
        if (tool < 0) return null;
        WorkshopPart part = WorkshopParts.get(kind, tool);
        Component name = part == null ? Component.empty() : Component.translatable(part.key());
        return Component.translatable(ClientWorkshop.working() ? "workshop.immersive_brewing.hint.working" : "workshop.immersive_brewing.hint.holding",
                name, Component.translatable(ClientWorkshop.effort().key()), WorkshopKeys.name(WorkshopKeys.PUT_DOWN), WorkshopKeys.name(WorkshopKeys.WORK),
                WorkshopKeys.name(WorkshopKeys.TOOL_FORWARD), WorkshopKeys.name(WorkshopKeys.TOOL_LEFT), WorkshopKeys.name(WorkshopKeys.TOOL_BACK),
                WorkshopKeys.name(WorkshopKeys.TOOL_RIGHT));
    }

    @Nullable
    private static Component cue(WorkshopTargets.View v) {
        String key = switch (v.kind()) {
            case KETTLE -> v.kettle().contains("cue") ? v.kettle().getString("cue") : v.finishing().getString("cue");
            case MORTAR -> v.mortar().getString("cue");
            case ALEMBIC -> v.alembic().getString("cue");
            case RACK -> "";
        };
        if (v.sealed()) return Component.translatable("cue.immersive_brewing.sealed");
        if (v.state() == com.otectus.immersivebrewing.batch.JobState.RECOVERY) return Component.translatable("cue.immersive_brewing.held");
        if (v.legacy()) return Component.translatable("cue.immersive_brewing.legacy");
        if (key.isEmpty()) {
            if (v.be() instanceof StationBlockEntity station) return station.view().status();
            return null;
        }
        return Component.translatable(key);
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
