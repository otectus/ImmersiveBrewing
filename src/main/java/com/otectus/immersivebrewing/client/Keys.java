package com.otectus.immersivebrewing.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.settings.IKeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Remappable controls for the three games (spec 23.2). They act only inside the brewing screens (GUI conflict context),
 * which capture input so movement and attack never also fire. Screens show each key's current binding by name.
 */
public final class Keys {
    public static final String CATEGORY = "key.categories.immersive_brewing";

    /**
     * One conflict context per game: bindings only clash with others of the same game, never with movement keys or
     * with the other games' bindings (the Mortar's and the Kettle's W, A, S and D keys do not conflict).
     */
    public enum Game implements IKeyConflictContext {
        KETTLE, MORTAR, ALEMBIC, ANY;

        @Override
        public boolean isActive() {
            return Minecraft.getInstance().screen instanceof com.otectus.immersivebrewing.client.screen.GameScreen game
                    && (this == ANY || game.game() == this);
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this || other == ANY || this == ANY && other instanceof Game;
        }
    }

    /** The Kettle's draft steps one level up or down, like the Alembic's valves (playtest: fewer keys to learn). */
    public static final KeyMapping DRAFT_UP = key("draft_up", GLFW.GLFW_KEY_W, Game.KETTLE);
    public static final KeyMapping DRAFT_DOWN = key("draft_down", GLFW.GLFW_KEY_S, Game.KETTLE);
    public static final KeyMapping STIR_CCW = key("stir_ccw", GLFW.GLFW_KEY_A, Game.KETTLE);
    public static final KeyMapping STIR_CW = key("stir_cw", GLFW.GLFW_KEY_D, Game.KETTLE);
    public static final KeyMapping VIGOROUS = key("vigorous", GLFW.GLFW_KEY_LEFT_SHIFT, Game.KETTLE);
    public static final KeyMapping ADD = key("add", GLFW.GLFW_KEY_SPACE, Game.KETTLE);
    public static final KeyMapping PESTLE_UP = key("pestle_up", GLFW.GLFW_KEY_W, Game.MORTAR);
    public static final KeyMapping PESTLE_DOWN = key("pestle_down", GLFW.GLFW_KEY_S, Game.MORTAR);
    public static final KeyMapping PESTLE_LEFT = key("pestle_left", GLFW.GLFW_KEY_A, Game.MORTAR);
    public static final KeyMapping PESTLE_RIGHT = key("pestle_right", GLFW.GLFW_KEY_D, Game.MORTAR);
    public static final KeyMapping PRESS = key("press", GLFW.GLFW_KEY_SPACE, Game.MORTAR);
    public static final KeyMapping FOLD = key("fold", GLFW.GLFW_KEY_F, Game.MORTAR);
    public static final KeyMapping FINISH = key("finish", GLFW.GLFW_KEY_ENTER, Game.MORTAR);
    public static final KeyMapping HEAT_UP = key("heat_up", GLFW.GLFW_KEY_E, Game.ALEMBIC);
    public static final KeyMapping HEAT_DOWN = key("heat_down", GLFW.GLFW_KEY_Q, Game.ALEMBIC);
    public static final KeyMapping COOL_UP = key("cool_up", GLFW.GLFW_KEY_C, Game.ALEMBIC);
    public static final KeyMapping COOL_DOWN = key("cool_down", GLFW.GLFW_KEY_Z, Game.ALEMBIC);
    public static final KeyMapping RECEIVER = key("receiver", GLFW.GLFW_KEY_R, Game.ALEMBIC);
    public static final KeyMapping HELP = key("help", GLFW.GLFW_KEY_H, Game.ANY);

    public static final KeyMapping EQUIP_CARRIER = new KeyMapping("key.immersive_brewing.equip_carrier", net.minecraftforge.client.settings.KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);

    public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        var mc = Minecraft.getInstance();
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || mc.screen != null || mc.player == null) return;
        while (EQUIP_CARRIER.consumeClick()) {
            var hand = mc.player.getMainHandItem().getItem() instanceof com.otectus.immersivebrewing.item.BatchCarrierItem
                    ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
            com.otectus.immersivebrewing.network.ModNetwork.sendToServer(new com.otectus.immersivebrewing.network.packet.EquipCarrierSelectedC2S(hand));
        }
    }

    public static final List<KeyMapping> ALL = List.of(DRAFT_UP, DRAFT_DOWN, STIR_CCW, STIR_CW, VIGOROUS, ADD, PESTLE_UP,
            PESTLE_DOWN, PESTLE_LEFT, PESTLE_RIGHT, PRESS, FOLD, FINISH, HEAT_UP, HEAT_DOWN, COOL_UP, COOL_DOWN, RECEIVER, HELP);

    private static KeyMapping key(String name, int code, Game game) {
        return new KeyMapping("key.immersive_brewing." + name, game, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
        event.register(EQUIP_CARRIER);
    }

    /** True when the raw key event is this binding (rebinding-aware). */
    public static boolean is(KeyMapping key, int keyCode, int scanCode) {
        return key.matches(keyCode, scanCode);
    }

    /** True when the mouse button is this binding. */
    public static boolean isMouse(KeyMapping key, int button) {
        return key.matchesMouse(button);
    }

    private Keys() {}
}
