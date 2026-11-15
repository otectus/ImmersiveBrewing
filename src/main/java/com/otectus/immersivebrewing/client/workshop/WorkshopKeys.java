package com.otectus.immersivebrewing.client.workshop;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.IKeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Rebindable controls of the working view (handoff 4, 11). Every action has a single-key binding: nothing requires a
 * chord, and the batch modifier is a toggle. They act only while the working view is open, so they never clash with
 * movement or another mod's keys in ordinary play.
 */
public final class WorkshopKeys {
    public static final String CATEGORY = "key.categories.immersive_brewing.workshop";

    public enum Context implements IKeyConflictContext {
        WORKSHOP;

        @Override
        public boolean isActive() {
            return Minecraft.getInstance().screen instanceof WorkshopScreen;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    }

    public static final KeyMapping NEXT_TARGET = key("next_target", GLFW.GLFW_KEY_TAB);
    public static final KeyMapping PREVIOUS_TARGET = key("previous_target", GLFW.GLFW_KEY_BACKSPACE);
    public static final KeyMapping ACTIVATE = key("activate", GLFW.GLFW_KEY_ENTER);
    public static final KeyMapping WORK = key("work", GLFW.GLFW_KEY_SPACE);
    public static final KeyMapping PUT_DOWN = key("put_down", GLFW.GLFW_KEY_X);
    public static final KeyMapping EFFORT = key("effort", GLFW.GLFW_KEY_F);
    public static final KeyMapping INCREASE = key("increase", GLFW.GLFW_KEY_UP);
    public static final KeyMapping DECREASE = key("decrease", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping TOOL_LEFT = key("tool_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping TOOL_RIGHT = key("tool_right", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping TOOL_FORWARD = key("tool_forward", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping TOOL_BACK = key("tool_back", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping BATCH = key("batch", GLFW.GLFW_KEY_C);
    public static final KeyMapping SEAL = key("seal", GLFW.GLFW_KEY_L);
    public static final KeyMapping DRAWER = key("drawer", GLFW.GLFW_KEY_I);
    public static final KeyMapping BOOK = key("book", GLFW.GLFW_KEY_K);
    public static final KeyMapping NEXT_STATION = key("next_station", GLFW.GLFW_KEY_N);
    public static final KeyMapping WITHDRAW = key("withdraw", GLFW.GLFW_KEY_DELETE);
    public static final KeyMapping PRACTICE = key("practice", GLFW.GLFW_KEY_P);
    /** The held tool's posture: worked near the surface, or pressed down to sweep the bottom (a toggle, no chord). */
    public static final KeyMapping POSTURE = key("posture", GLFW.GLFW_KEY_V);
    /** The camera's closer look at the material, and back. */
    public static final KeyMapping INSPECT = key("inspect", GLFW.GLFW_KEY_Z);
    /** Shows or hides the full control help (Detailed guidance shows it always). */
    public static final KeyMapping HELP = key("help", GLFW.GLFW_KEY_H);

    public static final List<KeyMapping> ALL = List.of(NEXT_TARGET, PREVIOUS_TARGET, ACTIVATE, WORK, PUT_DOWN, EFFORT, INCREASE, DECREASE,
            TOOL_LEFT, TOOL_RIGHT, TOOL_FORWARD, TOOL_BACK, BATCH, SEAL, DRAWER, BOOK, NEXT_STATION, WITHDRAW, PRACTICE, POSTURE, INSPECT, HELP);

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.immersive_brewing.workshop." + name, Context.WORKSHOP, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
    }

    /** Rebinding-aware key match for a raw key event. */
    public static boolean is(KeyMapping key, int keyCode, int scanCode) {
        return key.matches(keyCode, scanCode);
    }

    /** The binding's current name, for hint text. */
    public static String name(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private WorkshopKeys() {}
}
