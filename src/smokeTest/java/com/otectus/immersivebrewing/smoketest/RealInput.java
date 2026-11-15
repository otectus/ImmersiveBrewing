package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.client.workshop.WorkshopProjector;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

/**
 * Real input for the smoke test's journeys (handoff 15.3): mouse moves, button presses, wheel turns and keys enter
 * Minecraft's own {@code MouseHandler} and {@code KeyboardHandler} exactly where GLFW's callbacks deliver them, in window
 * coordinates. Everything after that (the screen's picking through the rendered camera, the targets, the intents, the
 * server) is the game's own path; nothing here calls a station, the client hand or the server. Where a part is on screen
 * comes from the projector, the matrices of the frame that was actually rendered, so a click lands where the part is
 * drawn. Never shipped.
 */
final class RealInput {
    static int moves;
    /** Times the cursor was found somewhere this driver did not put it (the desktop's own pointer, a refocus). */
    static int displaced;
    private static double lastX = Double.NaN, lastY = Double.NaN;
    static int clicks;
    static int scrolls;
    static int keys;
    private static Method onMove;
    private static Method onPress;
    private static Method onScroll;

    private static void init() {
        if (onMove != null) return;
        try {
            Class<?> handler = Minecraft.getInstance().mouseHandler.getClass();
            onMove = handler.getDeclaredMethod("onMove", long.class, double.class, double.class);
            onPress = handler.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
            onScroll = handler.getDeclaredMethod("onScroll", long.class, double.class, double.class);
            onMove.setAccessible(true);
            onPress.setAccessible(true);
            onScroll.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the mouse handler's callbacks are not where the dev mappings put them", e);
        }
    }

    private static long window() {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    /** Moves the cursor to a GUI-scaled screen point (delivered as the OS would, in window coordinates). */
    static void moveGui(double guiX, double guiY) {
        init();
        var w = Minecraft.getInstance().getWindow();
        double x = guiX * w.getScreenWidth() / Math.max(1, w.getGuiScaledWidth());
        double y = guiY * w.getScreenHeight() / Math.max(1, w.getGuiScaledHeight());
        try {
            onMove.invoke(Minecraft.getInstance().mouseHandler, window(), x, y);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        lastX = x;
        lastY = y;
        moves++;
    }

    /**
     * Whether the cursor is no longer where this driver last put it (an event from the desktop pointer reached the
     * window); counted, and the caller puts it back before acting.
     */
    static boolean displaced() {
        if (Double.isNaN(lastX)) return false;
        var mouse = Minecraft.getInstance().mouseHandler;
        boolean moved = Math.abs(mouse.xpos() - lastX) > 1 || Math.abs(mouse.ypos() - lastY) > 1;
        if (moved) {
            displaced++;
            lastX = mouse.xpos();
            lastY = mouse.ypos();
        }
        return moved;
    }

    /** Where the cursor is now, in GUI coordinates. */
    static double[] cursorGui() {
        var mc = Minecraft.getInstance();
        var w = mc.getWindow();
        return new double[] {mc.mouseHandler.xpos() * w.getGuiScaledWidth() / Math.max(1, w.getScreenWidth()),
                mc.mouseHandler.ypos() * w.getGuiScaledHeight() / Math.max(1, w.getScreenHeight())};
    }

    static boolean onScreen(@Nullable double[] gui) {
        if (gui == null) return false;
        var w = Minecraft.getInstance().getWindow();
        return gui[0] >= 1 && gui[1] >= 1 && gui[0] <= w.getGuiScaledWidth() - 1 && gui[1] <= w.getGuiScaledHeight() - 1;
    }

    static void button(int button, boolean down) {
        init();
        try {
            onPress.invoke(Minecraft.getInstance().mouseHandler, window(), button, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (down) clicks++;
    }

    static void click(int button) {
        button(button, true);
        button(button, false);
    }

    static void scroll(double amount) {
        init();
        try {
            onScroll.invoke(Minecraft.getInstance().mouseHandler, window(), 0.0, amount);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        scrolls++;
    }

    static void key(int key) {
        var keyboard = Minecraft.getInstance().keyboardHandler;
        int scan = GLFW.glfwGetKeyScancode(key);
        keyboard.keyPress(window(), key, scan, GLFW.GLFW_PRESS, 0);
        keyboard.keyPress(window(), key, scan, GLFW.GLFW_RELEASE, 0);
        keys++;
    }

    // ---- where things are on screen ----

    static Direction facing(BlockPos pos) {
        var state = Minecraft.getInstance().level.getBlockState(pos);
        return state.hasProperty(HorizontalDirectionalBlock.FACING) ? state.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    /** A model-pixel point of a station on screen (GUI coordinates), or null when it is behind the camera. */
    @Nullable
    static double[] screenOf(BlockPos pos, double px, double py, double pz) {
        Vec3 world = WorkshopGeometry.toWorld(pos, facing(pos), px, py, pz);
        return WorkshopProjector.project(world);
    }

    /** A part's centre on screen, or null. */
    @Nullable
    static double[] screenOf(BlockPos pos, WorkshopKind kind, int part) {
        WorkshopPart p = WorkshopParts.get(kind, part);
        return p == null ? null : screenOf(pos, p.cx(), p.cy(), p.cz());
    }

    static String counts() {
        return "moves " + moves + ", clicks " + clicks + ", scrolls " + scrolls + ", keys " + keys;
    }

    static void reset() {
        moves = clicks = scrolls = keys = displaced = 0;
        lastX = lastY = Double.NaN;
    }

    private RealInput() {}
}
