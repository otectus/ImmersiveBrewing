package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import net.minecraft.world.item.ItemStack;
import java.util.Set;

/**
 * Base of the three game screens (spec 24.2, 24.3). It owns the input contract:
 * <ul>
 * <li>Keys are matched through their bindings, so rebinding works and labels follow. Bound game actions win; unmatched
 * Tab/Enter reach vanilla focus navigation, while other keys remain consumed so movement and attack never also fire.</li>
 * <li>Actions happen on key-down only; a key held when the screen opens does not count (no first addition on open).</li>
 * <li>Releasing a key, losing window focus, sealing or the session ending resets held input.</li>
 * <li>Escape always seals (or, during the count-in of a new job, releases the reservation) and says so.</li>
 * <li>The help overlay pauses the game, in practice too: nothing moves while it is open, no key or click reaches the
 * game, and closing it mid-run counts in again before play continues. It shows one short page at a time.</li>
 * </ul>
 * The session itself ticks from the screen; when the server ends it the screen hands over to the result screen.
 */
public abstract class GameScreen extends Screen {
    protected final ClientSession session;
    private final Set<KeyMapping> held = new HashSet<>();
    protected boolean help;
    private boolean settings;
    /** The help page on show; kept when the help closes early, so reopening it continues there. */
    private int page;
    /** The help key is down: key repeat must not flicker the overlay. */
    private boolean helpKeyDown;
    private boolean ready;
    private final List<Button> gameButtons = new ArrayList<>();
    private final List<Button> settingsButtons = new ArrayList<>();
    private Button helpBack, helpNext;
    private Component hoveredText;
    private int mouseX, mouseY;

    protected GameScreen(Component title, ClientSession session) {
        super(title);
        this.session = session;
        this.help = ClientConfig.get(ClientConfig.SHOW_TUTORIAL) && !TutorialState.seen(game());
    }

    public abstract Keys.Game game();

    /** The packed input for this tick (called once per tick by the session); clears one-shot actions. */
    protected abstract int frame();

    protected abstract void renderGame(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    /** The help overlay's pages, one short instruction each, in reading order. */
    protected abstract Component[] helpLines();

    @Override
    protected void init() {
        gameButtons.clear();
        session.bindInput(this::frame);
        // A tutorial that opens with the screen holds the count-in until it is closed.
        session.setPaused(help || settings);
        // Top left: vanilla toasts stack in the top-right corner and would cover the buttons there.
        int w = buttonsWidth();
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.help"), b -> setHelp(!help))
                .bounds(4, 4, w / 3 - 3, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.accessibility"), b -> setSettings(!settings))
                .bounds(4 + w / 3, 4, w / 3 - 3, 16).build());
        addRenderableWidget(Button.builder(Component.translatable(session.practice ? "screen.immersive_brewing.leave_practice" : "screen.immersive_brewing.seal"),
                b -> leave()).bounds(4 + w * 2 / 3, 4, w - w * 2 / 3, 16).build());
        helpBack = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.help_back"), b -> turnPage(-1)).bounds(0, 0, 80, 20).build());
        helpNext = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.help_next"), b -> {
            if (page < helpLines().length - 1) turnPage(1);
            else setHelp(false);
        }).bounds(0, 0, 80, 20).build());
        updateHelpButtons();
        buildSettingsButtons();
    }

    protected Button gameButton(Component label, int x, int y, int w, int h, Button.OnPress press) {
        Button button = addRenderableWidget(Button.builder(label, press).bounds(x, y, w, h).build());
        gameButtons.add(button);
        button.visible = !help && !settings;
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label));
        return button;
    }

    /** Long names stay in their own column, with the complete text on hover. */
    protected void fitted(GuiGraphics g, Component text, int x, int y, int w, int color) {
        Ui.fit(g, text, x, y, w, color);
        if (font.width(text) > w && Ui.inside(mouseX, mouseY, x, y, w, 10)) hoveredText = text;
    }

    protected void textTip(Component text, int x, int y, int w, int h) {
        if (Ui.inside(mouseX, mouseY, x, y, w, h)) hoveredText = text;
    }

    protected void cueText(GuiGraphics g, Component text, int x, int y, int w, int lines, int color) {
        Ui.wrappedMax(g, text, x, y, w, lines, color);
        if (Ui.lines(text, w) > lines && Ui.inside(mouseX, mouseY, x, y, w, lines * 10)) hoveredText = text;
    }

    protected void header(GuiGraphics g, ItemStack item, Component name, Component status, int x, int y, int w) {
        // Count-in belongs to the inset status row, with clearance above the progress bar.
        // Drawing it over the apparatus obscured both the countdown and the controls beneath it.
        if (session.countingIn() && !controlsBlocked()) {
            status = Component.translatable(session.resuming() ? "screen.immersive_brewing.resuming" : "screen.immersive_brewing.count_in",
                    session.countInSeconds());
        }
        Ui.inset(g, x + 6, y + 6, w - 12, 28);
        if (!item.isEmpty()) g.renderItem(item, x + 9, y + 12);
        fitted(g, name, x + 29, y + 9, w - 40, Ui.palette().insetText());
        fitted(g, status, x + 29, y + 21, w - 40, Ui.onDark(Ui.palette().warn()));
    }

    /** Whether the tutorial overlay is open. */
    public boolean helpShowing() {
        return help;
    }

    /** True while an overlay owns input; game-specific renderers must not make controls visible again. */
    protected boolean controlsBlocked() {
        return help || settings;
    }

    private int buttonsWidth() {
        return Math.min(width - 8, 210);
    }

    private void setHelp(boolean show) {
        if (show == help) return;
        if (show && settings) setSettings(false);
        help = show;
        if (show) {
            // Like losing focus: whatever was held stops, and has to be pressed again once play continues.
            releaseAll();
        } else {
            TutorialState.markSeen(game());
            if (page >= helpLines().length - 1) page = 0;
        }
        session.setPaused(show);
        updateHelpButtons();
    }

    private void setSettings(boolean show) {
        if (show == settings) return;
        settings = show;
        if (show) {
            if (help) {
                help = false;
                updateHelpButtons();
            }
            releaseAll();
        }
        session.setPaused(show);
        updateSettingsButtons();
    }

    private void buildSettingsButtons() {
        settingsButtons.clear();
        int boxW = Math.min(width - 40, 310);
        int x = (width - boxW) / 2 + 8;
        int y = Math.max(28, (height - 190) / 2) + 24;
        settingsButton(x, y, boxW - 16, () -> toggle(ClientConfig.TOGGLE_STIR), "toggle_stir");
        settingsButton(x, y + 22, boxW - 16, () -> toggle(ClientConfig.TOGGLE_PRESSURE), "toggle_pressure");
        settingsButton(x, y + 44, boxW - 16, () -> toggle(ClientConfig.REDUCED_INPUT), "reduced_input");
        settingsButton(x, y + 66, boxW - 16, () -> toggle(ClientConfig.REDUCED_MOTION), "reduced_motion");
        settingsButton(x, y + 88, boxW - 16, () -> toggle(ClientConfig.WRITTEN_CUES), "written_cues");
        settingsButton(x, y + 110, boxW - 16, () -> cyclePalette(), "palette");
        settingsButton(x, y + 132, boxW - 16, () -> toggle(ClientConfig.REQUEST_ASSIST), "assistance");
        updateSettingsButtons();
    }

    private void settingsButton(int x, int y, int w, Runnable action, String option) {
        Button button = addRenderableWidget(Button.builder(Component.empty(), b -> {
            action.run();
            updateSettingsButtons();
        }).bounds(x, y, w, 20).build());
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.settings." + option + ".tip")));
        settingsButtons.add(button);
    }

    private static void toggle(net.minecraftforge.common.ForgeConfigSpec.BooleanValue value) {
        value.set(!ClientConfig.get(value));
    }

    private static void cyclePalette() {
        ClientConfig.Palette[] values = ClientConfig.Palette.values();
        ClientConfig.Palette current = ClientConfig.get(ClientConfig.PALETTE);
        ClientConfig.PALETTE.set(values[(current.ordinal() + 1) % values.length]);
    }

    private void updateSettingsButtons() {
        for (Button button : settingsButtons) button.visible = settings;
        if (settingsButtons.size() < 7) return;
        settingMessage(0, "toggle_stir", ClientConfig.get(ClientConfig.TOGGLE_STIR));
        settingMessage(1, "toggle_pressure", ClientConfig.get(ClientConfig.TOGGLE_PRESSURE));
        settingMessage(2, "reduced_input", ClientConfig.get(ClientConfig.REDUCED_INPUT));
        settingMessage(3, "reduced_motion", ClientConfig.get(ClientConfig.REDUCED_MOTION));
        settingMessage(4, "written_cues", ClientConfig.get(ClientConfig.WRITTEN_CUES));
        settingsButtons.get(5).setMessage(Component.translatable("screen.immersive_brewing.settings.palette",
                Component.translatable("screen.immersive_brewing.settings.palette." + ClientConfig.get(ClientConfig.PALETTE).name().toLowerCase(java.util.Locale.ROOT))));
        settingMessage(6, "assistance", ClientConfig.get(ClientConfig.REQUEST_ASSIST));
    }

    private void settingMessage(int index, String key, boolean enabled) {
        settingsButtons.get(index).setMessage(Component.translatable("screen.immersive_brewing.settings." + key,
                Component.translatable(enabled ? "options.on" : "options.off")));
    }

    private void turnPage(int delta) {
        page = Math.max(0, Math.min(helpLines().length - 1, page + delta));
        updateHelpButtons();
    }

    protected void leave() {
        session.leave();
        onClose();
    }

    @Override
    public void tick() {
        if (!ready) ready = true;
        if (minecraft != null && !minecraft.isWindowActive()) {
            releaseAll();
            helpKeyDown = false; // its release may never arrive
        }
        session.tick();
        if (session.ended() && minecraft != null) {
            releaseAll();
            minecraft.getNarrator().sayNow(Component.translatable(session.practice
                    ? "narration.immersive_brewing.practice_complete" : "narration.immersive_brewing.session_complete"));
            minecraft.setScreen(new ResultScreen(session));
        }
    }

    protected boolean down(KeyMapping key) {
        return held.contains(key);
    }

    protected void releaseAll() {
        held.clear();
        onReleaseAll();
    }

    protected void onReleaseAll() {}

    /** A binding was pressed (key-down edge). Return true when handled. */
    protected abstract boolean pressed(KeyMapping key);

    protected void released(KeyMapping key) {}

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settings) {
                setSettings(false);
                return true;
            }
            leave();
            return true;
        }
        if (Keys.is(Keys.HELP, keyCode, scanCode)) {
            if (settings) setSettings(false);
            if (ready && !helpKeyDown) setHelp(!help);
            helpKeyDown = true;
            return true;
        }
        if (!ready) return true;
        if (help || settings) {
            // The game is paused under the help: the arrow keys turn its pages and nothing reaches the game.
            if (help && keyCode == GLFW.GLFW_KEY_RIGHT) turnPage(1);
            else if (help && keyCode == GLFW.GLFW_KEY_LEFT) turnPage(-1);
            else super.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        boolean matched = false;
        for (KeyMapping key : Keys.ALL) {
            if (!Keys.is(key, keyCode, scanCode) || key.getKeyConflictContext() instanceof Keys.Game g && g != game() && g != Keys.Game.ANY) continue;
            matched = true;
            if (held.add(key)) pressed(key);
        }
        if (matched) return true;
        if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            super.keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (Keys.is(Keys.HELP, keyCode, scanCode)) helpKeyDown = false;
        for (KeyMapping key : Keys.ALL) {
            if (Keys.is(key, keyCode, scanCode) && held.remove(key)) released(key);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (!help && !settings) return false;
        return true;
    }

    @Override
    public void removed() {
        releaseAll();
        session.bindInput(null);
        if (!session.ended()) session.leave();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        hoveredText = null;
        updateHelpButtons();
        g.fill(0, 0, width, height, 0x88000000);
        renderGame(g, mouseX, mouseY, partialTick);
        // The game stands still under the help: dim it so the text stands out, but keep it in view to refer to.
        if (help || settings) g.fill(0, 0, width, height, 0x99000000);
        if (session.practice) {
            fitted(g, Component.translatable("notebook.immersive_brewing.tab.practice"), buttonsWidth() + 12, 8, width - buttonsWidth() - 16, 0xFFFFD27F);
            textTip(Component.translatable("screen.immersive_brewing.practice_badge"), buttonsWidth() + 12, 4, width - buttonsWidth() - 16, 16);
        }
        float assistTime = session.display.contains("assistTime") ? session.display.getFloat("assistTime") : 1F;
        float assistBand = session.display.contains("assistBand") ? session.display.getFloat("assistBand") : 1F;
        if (session.display.getBoolean("assisted") || assistTime > 1F || assistBand > 1F) {
            fitted(g, Component.translatable("screen.immersive_brewing.assistance_approved", assistTime, assistBand),
                    buttonsWidth() + 12, 18, width - buttonsWidth() - 16, 0xFF9FE5FF);
        }
        if (help) renderHelp(g, mouseX, mouseY);
        if (settings) renderSettings(g);
        super.render(g, mouseX, mouseY, partialTick);
        if (!help && !settings && hoveredText != null) g.renderTooltip(font, font.split(hoveredText, 240), mouseX, mouseY);
    }

    /** The help box and its page buttons, sized for the longest page so the buttons stay put while paging. */
    private record HelpBox(int x, int y, int w, int h, int buttonY, int buttonW) {
        boolean back(double mx, double my) {
            return Ui.inside(mx, my, x + 6, buttonY, buttonW, 20);
        }

        boolean next(double mx, double my) {
            return Ui.inside(mx, my, x + w - 6 - buttonW, buttonY, buttonW, 20);
        }
    }

    private Component helpKeys() {
        return Component.translatable("screen.immersive_brewing.help_close", Keys.HELP.getTranslatedKeyMessage());
    }

    private HelpBox helpBox(Component[] pages) {
        int w = Math.min(width - 40, 300);
        int text = 0;
        for (Component c : pages) text = Math.max(text, Ui.lines(c, w - 12) * 10);
        int buttonOffset = 16 + text + 6;
        int h = buttonOffset + 24 + Ui.lines(helpKeys(), w - 12) * 10;
        int x = (width - w) / 2;
        int y = Math.max(24, (height - h) / 2);
        return new HelpBox(x, y, w, h, y + buttonOffset, Math.min(80, (w - 24) / 2));
    }

    private void updateHelpButtons() {
        for (Button button : gameButtons) button.visible = !help && !settings;
        if (helpBack == null) return;
        HelpBox box = helpBox(helpLines());
        helpBack.visible = helpNext.visible = help;
        helpBack.active = page > 0;
        helpBack.setX(box.x() + 6);
        helpBack.setY(box.buttonY());
        helpBack.setWidth(box.buttonW());
        helpNext.setX(box.x() + box.w() - 6 - box.buttonW());
        helpNext.setY(box.buttonY());
        helpNext.setWidth(box.buttonW());
        Ui.message(helpNext, Component.translatable(page < helpLines().length - 1
                ? "screen.immersive_brewing.help_next" : "screen.immersive_brewing.help_done"));
    }

    private void renderHelp(GuiGraphics g, int mouseX, int mouseY) {
        Component[] pages = helpLines();
        page = Math.max(0, Math.min(page, pages.length - 1));
        HelpBox box = helpBox(pages);
        Ui.Palette p = Ui.palette();
        Ui.panel(g, box.x(), box.y(), box.w(), box.h());
        Component count = Component.translatable("screen.immersive_brewing.help_page", page + 1, pages.length);
        int countWidth = font.width(count);
        Ui.fit(g, Component.translatable("screen.immersive_brewing.help_paused"), box.x() + 6, box.y() + 4,
                box.w() - countWidth - 20, p.accent());
        g.drawString(font, count, box.x() + box.w() - 6 - countWidth, box.y() + 4, p.muted(), false);
        Ui.wrapped(g, pages[page], box.x() + 6, box.y() + 16, box.w() - 12, p.text());
        int y = box.buttonY() + 24;
        for (FormattedCharSequence line : font.split(helpKeys(), box.w() - 12)) {
            g.drawString(font, line, width / 2 - font.width(line) / 2, y, p.muted(), false);
            y += 10;
        }
    }

    private void renderSettings(GuiGraphics g) {
        int w = Math.min(width - 40, 310);
        int h = 190;
        int x = (width - w) / 2;
        int y = Math.max(28, (height - h) / 2);
        Ui.panel(g, x, y, w, h);
        g.drawString(font, Component.translatable("screen.immersive_brewing.settings.title"), x + 8, y + 7, Ui.palette().text(), false);
        Ui.wrapped(g, Component.translatable("screen.immersive_brewing.settings.paused"), x + 8, y + h - 14, w - 16, Ui.palette().muted());
    }

    /** "[key]" label for a binding as currently bound. */
    protected static Component key(KeyMapping key) {
        return Component.literal("[").append(key.getTranslatedKeyMessage()).append("]");
    }
}
