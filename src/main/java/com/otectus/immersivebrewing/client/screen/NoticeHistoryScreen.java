package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.client.StationNotices;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Persistent, scrollable text alternative to the timed two-line notice overlay. */
public final class NoticeHistoryScreen extends Screen {
    @Nullable private final Screen parent;
    private final Ui.ScrollArea content = new Ui.ScrollArea();
    private int x, y, w, h;

    public NoticeHistoryScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.immersive_brewing.notice_history"));
        this.parent = parent;
    }

    @Override protected void init() {
        w = Math.min(width - 24, 320); h = Math.min(height - 24, 220); x = (width - w) / 2; y = (height - h) / 2;
        content.bounds(x + 8, y + 24, w - 16, h - 52);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x + w - 86, y + h - 24, 78, 18).build());
    }
    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g); Ui.panel(g, x, y, w, h); Ui.fit(g, title, x + 8, y + 8, w - 16, Ui.palette().text());
        content.begin(g); int cy = content.top();
        var notices = StationNotices.history();
        if (notices.isEmpty()) cy += Ui.wrapped(g, Component.translatable("notice.immersive_brewing.history_empty"), x + 8, cy, content.width(), Ui.palette().muted());
        for (Component notice : notices) cy += Ui.wrapped(g, notice, x + 8, cy, content.width(), Ui.palette().text()) + 4;
        content.end(g, cy); super.render(g, mx, my, partial);
    }
    @Override public boolean mouseScrolled(double mx, double my, double delta) { return content.wheel(mx, my, delta) || super.mouseScrolled(mx, my, delta); }
    @Override public boolean mouseClicked(double mx, double my, int button) { return content.click(mx, my, button) || super.mouseClicked(mx, my, button); }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return content.drag(my) || super.mouseDragged(mx, my, button, dx, dy); }
    @Override public boolean mouseReleased(double mx, double my, int button) { content.release(); return super.mouseReleased(mx, my, button); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return content.key(key) || super.keyPressed(key, scan, modifiers);
    }
}
