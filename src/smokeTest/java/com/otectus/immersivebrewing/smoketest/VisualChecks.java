package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.client.screen.KettlePlanScreen;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Client-only acceptance checks for overflow, real mouse controls and resize persistence. Never shipped. */
final class VisualChecks {
    static void widgets(Screen screen, Map<String, Object> checks, String name) {
        if (screen == null || !screen.getClass().getPackageName().endsWith("client.screen")) return;
        List<AbstractWidget> widgets = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        boolean contained = widgets.stream().allMatch(w -> w.getX() >= 0 && w.getY() >= 0
                && w.getX() + w.getWidth() <= screen.width && w.getY() + w.getHeight() <= screen.height);
        boolean separate = true;
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            AbstractWidget a = widgets.get(i), b = widgets.get(j);
            if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                    && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) separate = false;
        }
        checks.put("widgets_contained_" + name, contained);
        checks.put("widgets_separate_" + name, separate);
    }

    static boolean click(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        for (var child : screen.children()) if (child instanceof Button b && b.visible && b.active && b.getMessage().getString().equals(label)) {
            return screen.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0);
        }
        return false;
    }

    /** Selects the listed plan with the most steps and returns its step count. */
    @SuppressWarnings("unchecked")
    static int longestPlan(KettlePlanScreen screen) {
        try {
            List<PlanListS2C.Entry> entries = (List<PlanListS2C.Entry>) field(screen, "filtered");
            int best = 0;
            for (int i = 1; i < entries.size(); i++) if (entries.get(i).steps().size() > entries.get(best).steps().size()) best = i;
            var select = KettlePlanScreen.class.getDeclaredMethod("select", int.class);
            select.setAccessible(true);
            select.invoke(screen, best);
            return entries.isEmpty() ? 0 : entries.get(best).steps().size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void scrollEnd(Screen screen, String field, Map<String, Object> checks, String name, boolean mustOverflow) {
        Ui.ScrollArea area = (Ui.ScrollArea) field(screen, field);
        screen.setFocused(null);
        boolean handled = screen.keyPressed(GLFW.GLFW_KEY_END, 0, 0);
        checks.put("scroll_reaches_end_" + name, handled && area.offset() == area.max() && (!mustOverflow || area.max() > 0));
    }

    static void resizeEdits(Screen screen, Map<String, Object> checks, String name) {
        List<EditBox> boxes = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
        if (boxes.size() != 2) throw new IllegalStateException("expected label and note");
        boxes.get(0).setValue("A brewer's long label");
        boxes.get(1).setValue("Notes survive resizing the window.");
        screen.resize(Minecraft.getInstance(), screen.width, screen.height);
        List<EditBox> resized = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
        checks.put("edits_survive_resize_" + name, resized.size() == 2 && resized.get(0).getValue().equals(boxes.get(0).getValue())
                && resized.get(1).getValue().equals(boxes.get(1).getValue()));
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private VisualChecks() {}
}
