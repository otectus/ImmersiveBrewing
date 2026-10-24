package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Shared drawing for the brewing screens: vanilla-style bevelled panels and insets, gauges with a shaded target band and
 * a marker shape (never colour alone), wrapped text, and the configured palette (standard, high contrast, colour-blind
 * safe). Everything is drawn in GUI pixels, so input hit-tests and rendering use one transform.
 */
public final class Ui {
    /** Colours for the light panel; {@code insetText}/{@code insetMuted} are for text on the dark inset wells. */
    public record Palette(int panel, int panelLight, int panelDark, int inset, int text, int muted, int good, int warn, int bad,
                          int band, int marker, int accent, int insetText, int insetMuted) {}

    private static final Palette STANDARD = new Palette(0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF373737, 0xFF404040, 0xFF555555,
            0xFF28632C, 0xFF775000, 0xFF9E2923, 0x6650C878, 0xFFFFFFFF, 0xFF285584, 0xFFE6E6E6, 0xFFBBBBBB);
    private static final Palette HIGH_CONTRAST = new Palette(0xFF101010, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFD0D0D0,
            0xFF00FF66, 0xFFFFD000, 0xFFFF4040, 0x9900FF66, 0xFFFFFFFF, 0xFF66CCFF, 0xFFFFFFFF, 0xFFD0D0D0);
    /** Okabe-Ito: blue / orange / vermillion distinguishable under the common colour-vision deficiencies. */
    private static final Palette COLORBLIND = new Palette(0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF373737, 0xFF202020, 0xFF5A5A5A,
            0xFF005A8D, 0xFF775000, 0xFF9A3D08, 0x660072B2, 0xFFFFFFFF, 0xFF00654D, 0xFFE8E8E8, 0xFFBBBBBB);

    /** Semantic text on the dark wells needs a lighter palette than text on inventory gray. */
    public static int onDark(int color) {
        Palette p = palette();
        if (p == HIGH_CONTRAST) return color;
        if (color == p.good()) return p == COLORBLIND ? 0xFF80C9F4 : 0xFF8ADA80;
        if (color == p.warn()) return 0xFFFFD27F;
        if (color == p.bad()) return 0xFFFF9388;
        return color;
    }

    public static Palette palette() {
        return switch (ClientConfig.get(ClientConfig.PALETTE)) {
            case HIGH_CONTRAST -> HIGH_CONTRAST;
            case COLORBLIND_SAFE -> COLORBLIND;
            default -> STANDARD;
        };
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** A raised vanilla-style panel. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        Palette p = palette();
        g.fill(x, y, x + w, y + h, p.panel());
        g.fill(x, y, x + w - 1, y + 1, p.panelLight());
        g.fill(x, y, x + 1, y + h - 1, p.panelLight());
        g.fill(x + 1, y + h - 1, x + w, y + h, p.panelDark());
        g.fill(x + w - 1, y + 1, x + w, y + h, p.panelDark());
        g.fill(x - 1, y - 1, x + w + 1, y, 0xFF000000);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x - 1, y, x, y + h, 0xFF000000);
        g.fill(x + w, y, x + w + 1, y + h, 0xFF000000);
    }

    /** A sunken area (slot style). */
    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        Palette p = palette();
        g.fill(x, y, x + w, y + h, p.inset());
        g.fill(x, y, x + w, y + 1, p.panelDark());
        g.fill(x, y, x + 1, y + h, p.panelDark());
        g.fill(x, y + h - 1, x + w, y + h, p.panelLight());
        g.fill(x + w - 1, y, x + w, y + h, p.panelLight());
    }

    public static void slot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        g.fill(x, y, x + 17, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + 17, 0xFF373737);
        g.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
    }

    /**
     * A horizontal gauge: the target band shaded and outlined, the value as a triangle marker with a stem. Values and
     * band are 0..1 (or {@code -1..1} when {@code signed}).
     */
    public static void gauge(GuiGraphics g, int x, int y, int w, int h, double value, double low, double high, boolean signed, int markerColor) {
        Palette p = palette();
        inset(g, x, y, w, h);
        int bx0 = x + 1 + (int) Math.round(norm(low, signed) * (w - 3));
        int bx1 = x + 1 + (int) Math.round(norm(high, signed) * (w - 3));
        g.fill(bx0, y + 1, Math.max(bx0 + 1, bx1), y + h - 1, p.band());
        g.fill(bx0, y + 1, bx0 + 1, y + h - 1, p.good());
        g.fill(Math.max(bx0 + 1, bx1) - 1, y + 1, Math.max(bx0 + 1, bx1), y + h - 1, p.good());
        int mx = x + 1 + (int) Math.round(norm(value, signed) * (w - 3));
        boolean large = ClientConfig.get(ClientConfig.LARGE_MARKERS);
        int size = large ? 4 : 3;
        for (int i = 0; i < size; i++) g.fill(mx - (size - 1 - i), y - size + i, mx + (size - i), y - size + i + 1, markerColor);
        g.fill(mx, y, mx + 1, y + h, markerColor);
        if (signed) g.fill(x + w / 2, y + h - 2, x + w / 2 + 1, y + h, p.muted());
    }

    /** A vertical gauge (heat): band shaded, value marker from the right. */
    public static void vgauge(GuiGraphics g, int x, int y, int w, int h, double value, double low, double high, int markerColor) {
        Palette p = palette();
        inset(g, x, y, w, h);
        int by0 = y + h - 1 - (int) Math.round(high * (h - 2));
        int by1 = y + h - 1 - (int) Math.round(low * (h - 2));
        g.fill(x + 1, by0, x + w - 1, Math.max(by0 + 1, by1), p.band());
        g.fill(x + 1, by0, x + w - 1, by0 + 1, p.good());
        g.fill(x + 1, Math.max(by0 + 1, by1) - 1, x + w - 1, Math.max(by0 + 1, by1), p.good());
        int my = y + h - 1 - (int) Math.round(Math.max(0, Math.min(1, value)) * (h - 2));
        int size = ClientConfig.get(ClientConfig.LARGE_MARKERS) ? 4 : 3;
        for (int i = 0; i < size; i++) g.fill(x + w + i, my - (size - 1 - i), x + w + i + 1, my + (size - i), markerColor);
        g.fill(x, my, x + w, my + 1, markerColor);
    }

    private static double norm(double v, boolean signed) {
        double n = signed ? (v + 1) / 2 : v;
        return Math.max(0, Math.min(1, n));
    }

    /** Wrapped text; returns the height used. */
    public static int wrapped(GuiGraphics g, Component text, int x, int y, int width, int color) {
        Font font = font();
        List<FormattedCharSequence> lines = font.split(text, Math.max(20, width));
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * 10, color, false);
        return lines.size() * 10;
    }

    /** A bounded line, retaining the component's styles. The caller can offer the full text as a tooltip. */
    public static void fit(GuiGraphics g, Component text, int x, int y, int width, int color) {
        FormattedText shown = text;
        if (font().width(text) > width) shown = FormattedText.composite(
                font().substrByWidth(text, Math.max(0, width - font().width("…"))), Component.literal("…"));
        g.drawString(font(), Language.getInstance().getVisualOrder(shown), x, y, color, false);
    }

    /** Bounded wrapping for compact status areas; full text remains available from the area's tooltip. */
    public static int wrappedMax(GuiGraphics g, Component text, int x, int y, int width, int maxLines, int color) {
        List<FormattedCharSequence> lines = font().split(text, Math.max(20, width));
        int count = Math.min(lines.size(), Math.max(0, maxLines));
        for (int i = 0; i < count; i++) {
            g.drawString(font(), lines.get(i), x, y + i * 10, color, false);
        }
        if (lines.size() > count && count > 0) {
            int ex = x + width - font().width("…");
            g.fill(ex - 1, y + (count - 1) * 10, x + width, y + count * 10, palette().panel());
            g.drawString(font(), "…", ex, y + (count - 1) * 10, color, false);
        }
        return count * 10;
    }

    public static void message(Button button, Component text) {
        if (!text.equals(button.getMessage())) {
            button.setMessage(text);
            button.setTooltip(Tooltip.create(text));
        }
    }

    /** Pixel scrolling with a vanilla-style scrollbar. The footer stays outside the scissor. */
    public static final class ScrollArea {
        private int x, y, w, h, content, offset;
        private boolean dragging;

        public void bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = Math.max(1, h);
        }

        public int width() { return w - 10; }
        public int top() { return y - offset; }
        public int offset() { return offset; }
        public int max() { return Math.max(0, content - h); }
        public void reset() { offset = 0; }

        public void begin(GuiGraphics g) {
            g.enableScissor(x, y, x + width(), y + h);
        }

        public void end(GuiGraphics g, int bottom) {
            g.disableScissor();
            content = Math.max(0, bottom - top());
            offset = Math.min(offset, max());
            if (max() == 0) return;
            int thumb = Math.max(12, h * h / content);
            int ty = y + (h - thumb) * offset / max();
            g.fill(x + w - 6, y, x + w, y + h, palette().inset());
            g.fill(x + w - 6, ty, x + w, ty + thumb, 0xFF808080);
            g.fill(x + w - 6, ty, x + w - 1, ty + thumb - 1, 0xFFC0C0C0);
        }

        public boolean contains(double mx, double my) { return inside(mx, my, x, y, w, h); }

        public boolean wheel(double mx, double my, double delta) {
            if (!contains(mx, my)) return false;
            offset = Math.max(0, Math.min(max(), offset - (int) Math.signum(delta) * 30));
            return true;
        }

        public boolean click(double mx, double my, int button) {
            dragging = button == 0 && max() > 0 && inside(mx, my, x + w - 6, y, 6, h);
            if (dragging) drag(my);
            return dragging;
        }

        public boolean drag(double my) {
            if (!dragging) return false;
            int thumb = Math.max(12, h * h / content);
            offset = Math.max(0, Math.min(max(), (int) Math.round((my - y - thumb / 2.0) * max() / Math.max(1, h - thumb))));
            return true;
        }

        public void release() { dragging = false; }

        public boolean key(int key) {
            int next = switch (key) {
                case GLFW.GLFW_KEY_PAGE_DOWN -> offset + h - 10;
                case GLFW.GLFW_KEY_PAGE_UP -> offset - h + 10;
                case GLFW.GLFW_KEY_HOME -> 0;
                case GLFW.GLFW_KEY_END -> max();
                default -> -1;
            };
            if (next == -1 && key != GLFW.GLFW_KEY_PAGE_UP) return false;
            offset = Math.max(0, Math.min(max(), next));
            return true;
        }
    }

    /**
     * Gives an edit box a hint that fits it: vanilla draws the hint unclipped, so a long one would run over whatever is
     * beside the box at small GUI sizes.
     */
    public static void hint(net.minecraft.client.gui.components.EditBox box, Component hint) {
        Font font = font();
        String text = hint.getString();
        int max = box.getWidth() - 10;
        if (font.width(text) > max) text = font.plainSubstrByWidth(text, Math.max(0, max - font.width("…"))) + "…";
        box.setHint(Component.literal(text));
    }

    public static int lines(Component text, int width) {
        return font().split(text, Math.max(20, width)).size();
    }

    public static void centered(GuiGraphics g, Component text, int cx, int y, int color) {
        Font font = font();
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    /** A filled progress bar 0..1 with an outline. */
    public static void progress(GuiGraphics g, int x, int y, int w, int h, double value, int color) {
        inset(g, x, y, w, h);
        int fill = (int) Math.round(Math.max(0, Math.min(1, value)) * (w - 2));
        g.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static int withAlpha(int rgb, int alpha) {
        return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
    }

    public static int lerpColor(int a, int b, double t) {
        double c = Math.max(0, Math.min(1, t));
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF, aa = a >>> 24;
        int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF, ba = b >>> 24;
        return (int) (aa + (ba - aa) * c) << 24 | (int) (ar + (br - ar) * c) << 16 | (int) (ag + (bg - ag) * c) << 8 | (int) (ab + (bb - ab) * c);
    }

    private Ui() {}
}
