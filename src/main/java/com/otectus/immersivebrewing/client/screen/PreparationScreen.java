package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.client.sound.BrewingSounds;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.minigame.preparation.PreparationInput;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationSimulation;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * "Open the Ingredient" (spec 6). The pestle follows the mouse inside the bowl (or the movement keys), held pressure
 * builds force, a lift-and-fold loosens a clumping batch, and the player decides when the texture matches the endpoint.
 * Both input paths produce the same capped frames.
 */
public class PreparationScreen extends GameScreen {
    private double targetX;
    private double targetY;
    private boolean mousePressing;
    private boolean togglePress;
    private boolean foldQueued;
    private boolean finishQueued;
    private int lastFolds;
    private Button foldButton, finishButton;
    private int px, py, pw, ph, bowlX, bowlY, radius, colX, colW;

    public PreparationScreen(ClientSession session) {
        super(Component.translatable("screen.immersive_brewing.mortar"), session);
        targetX = sim().pestleX();
        targetY = sim().pestleY();
    }

    private PreparationSimulation sim() {
        return ((SimHandles.Preparation) session.sim()).sim();
    }

    @Override
    public Keys.Game game() {
        return Keys.Game.MORTAR;
    }

    @Override
    protected void init() {
        super.init();
        pw = Math.min(width - 16, 360);
        ph = Math.min(height - 28, 230);
        px = (width - pw) / 2;
        py = 22 + Math.max(0, (height - 28 - ph) / 2);
        radius = Math.max(32, Math.min(80, Math.min(controlsY() - 6 - (py + 46), pw / 2 - 30) / 2));
        bowlX = px + 14 + radius;
        bowlY = py + 46 + radius;
        colX = bowlX + radius + 20;
        colW = px + pw - 8 - colX;
        foldButton = gameButton(Component.translatable("screen.immersive_brewing.fold", key(Keys.FOLD)), px + 8, controlsY(), pw / 2 - 10, 20, b -> foldQueued = true);
        finishButton = gameButton(Component.translatable("screen.immersive_brewing.done", key(Keys.FINISH)), px + pw / 2 + 2, controlsY(), pw / 2 - 10, 20, b -> finishQueued = true);
    }

    /** Where the pestle is steered to, -1..1 on each axis (what the next frame sends). */
    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    @Override
    protected int frame() {
        double speed = 0.09;
        if (down(Keys.PESTLE_LEFT)) targetX -= speed;
        if (down(Keys.PESTLE_RIGHT)) targetX += speed;
        if (down(Keys.PESTLE_UP)) targetY -= speed;
        if (down(Keys.PESTLE_DOWN)) targetY += speed;
        double r = Math.sqrt(targetX * targetX + targetY * targetY);
        if (r > 1) {
            targetX /= r;
            targetY /= r;
        }
        boolean toggle = ClientConfig.get(ClientConfig.TOGGLE_PRESSURE);
        boolean press = toggle ? togglePress : down(Keys.PRESS) || mousePressing;
        if (toggle && mousePressing) press = true;
        boolean fold = foldQueued;
        boolean finish = finishQueued;
        foldQueued = false;
        finishQueued = false;
        return PreparationInput.of(targetX, targetY, press, fold, finish).pack();
    }

    @Override
    protected boolean pressed(KeyMapping key) {
        if (session.countingIn()) return true;
        if (key == Keys.FOLD) foldQueued = true;
        else if (key == Keys.FINISH) finishQueued = true;
        else if (key == Keys.PRESS && ClientConfig.get(ClientConfig.TOGGLE_PRESSURE)) togglePress = !togglePress;
        return true;
    }

    @Override
    protected void onReleaseAll() {
        mousePressing = false;
        togglePress = false;
    }

    private boolean inBowl(double mx, double my) {
        double dx = mx - bowlX;
        double dy = my - bowlY;
        return dx * dx + dy * dy <= (radius + 4) * (radius + 4);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        if (!helpShowing() && inBowl(mx, my)) {
            targetX = Math.max(-1, Math.min(1, (mx - bowlX) / radius));
            targetY = Math.max(-1, Math.min(1, (my - bowlY) / radius));
        }
        super.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (session.countingIn()) return true;
        if (inBowl(mx, my)) {
            if (button == 0) mousePressing = true;
            else if (button == 1) foldQueued = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!helpShowing() && inBowl(mx, my)) {
            targetX = Math.max(-1, Math.min(1, (mx - bowlX) / radius));
            targetY = Math.max(-1, Math.min(1, (my - bowlY) / radius));
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) mousePressing = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void tick() {
        super.tick();
        if (session.ended() || session.paused()) return; // the pestle stands still under the help
        PreparationSimulation s = sim();
        boolean lifted = s.folds() != lastFolds;
        lastFolds = s.folds();
        BrewingSounds.mortar(s.lastWork(), s.texture().ordinal(), lifted);
    }

    @Override
    protected void renderGame(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PreparationSimulation s = sim();
        PreparationParams params = s.params();
        Ui.Palette p = Ui.palette();
        Ui.panel(g, px, py, pw, ph);
        ItemStack reagent = LedgerCodec.loadStack(session.display.getCompound("reagent"));
        Component time = Component.translatable("screen.immersive_brewing.seconds_left", (s.remainingSteps() + 19) / 20);
        header(g, reagent, Component.translatable("screen.immersive_brewing.mortar.preparing", params.kits(), reagent.getHoverName(),
                Component.translatable("mode.immersive_brewing." + params.mode().serialized())), time, px, py, pw);
        Ui.progress(g, px + 6, py + 35, pw - 12, 4, (double) s.step() / params.durationSteps(), p.accent());

        renderBowl(g, s, params);

        // Gauges
        int y = py + 46;
        fitted(g, Component.translatable("screen.immersive_brewing.processed"), colX, y, colW, p.text());
        y += 14;
        Ui.gauge(g, colX, y, colW, 10, s.processed(), params.endpointLow(), params.endpointHigh(), false, 0xFFFFFFFF);
        int ow = colX + 1 + (int) Math.round(params.overworkThreshold() * (colW - 2));
        g.fill(ow, y - 2, ow + 1, y + 12, p.bad());
        y += 16;
        fitted(g, Component.translatable("screen.immersive_brewing.pressure"), colX, y, colW, p.text());
        y += 14;
        Ui.gauge(g, colX, y, colW, 8, s.pressure(), 0.0, params.pressureLimit(), false, s.overpressure() ? p.bad() : 0xFFFFFFFF);
        y += 14;
        // Texture, clumping and the endpoint picture in words
        PreparationSimulation.Texture texture = s.texture();
        int tcolor = texture == PreparationSimulation.Texture.ENDPOINT ? p.good()
                : texture == PreparationSimulation.Texture.OVERWORKED ? p.bad() : p.warn();
        Component description = Component.translatable("texture.immersive_brewing." + texture.name().toLowerCase(java.util.Locale.ROOT));
        int lines = Math.min(Ui.lines(description, colW), Math.max(0, (controlsY() - 6 - y) / 10));
        cueText(g, description, colX, y, colW, lines, tcolor);
        y += lines * 10 + 2;
        if (s.overpressure()) {
            Component warning = Component.translatable("cue.immersive_brewing.overpressure");
            lines = Math.min(Ui.lines(warning, colW), Math.max(0, (controlsY() - 6 - y) / 10));
            cueText(g, warning, colX, y, colW, lines, p.bad());
            y += lines * 10 + 2;
        }
        if (s.clump() > 0.45) cueText(g, Component.translatable("cue.immersive_brewing.clumping", key(Keys.FOLD)), colX, y, colW,
                Math.max(0, (controlsY() - 6 - y) / 10), p.warn());

        foldButton.active = finishButton.active = !session.countingIn();
        // The numbers replace the standing reminder (it is also in the help overlay), so neither overlaps the other.
        if (ClientConfig.get(ClientConfig.NUMERIC_DISPLAY)) {
            g.drawString(font, String.format("x %.2f  P %.2f  clump %.2f  E %d", s.processed(), s.pressure(), s.clump(), s.score().extraction()),
                    px + 8, py + ph - 16, p.muted(), false);
        } else {
            Ui.wrapped(g, Component.translatable("screen.immersive_brewing.mortar.controls", key(Keys.PRESS)), px + 8, py + ph - footerHeight(), pw - 16, p.muted());
        }
    }

    private int footerHeight() {
        return ClientConfig.get(ClientConfig.NUMERIC_DISPLAY) ? 18
                : Ui.lines(Component.translatable("screen.immersive_brewing.mortar.controls", key(Keys.PRESS)), pw - 16) * 10 + 6;
    }

    private int controlsY() { return py + ph - footerHeight() - 26; }

    private void renderBowl(GuiGraphics g, PreparationSimulation s, PreparationParams params) {
        // stone bowl
        for (int dy = -radius - 4; dy <= radius + 4; dy++) {
            int half = (int) Math.sqrt(Math.max(0, (radius + 4) * (radius + 4) - dy * dy));
            int inner = (int) Math.sqrt(Math.max(0, radius * radius - dy * dy));
            g.fill(bowlX - half, bowlY + dy, bowlX + half, bowlY + dy + 1, 0xFF7D7D80);
            if (inner > 0) g.fill(bowlX - inner, bowlY + dy, bowlX + inner, bowlY + dy + 1, 0xFF5A5A5E);
        }
        int base = switch (params.mode()) {
            case CRYSTALLINE -> 0xF4F4F0;
            case FIBROUS -> 0x6E9E46;
            case SOFT -> 0xA0463C;
            case BRITTLE -> 0xB8B0A0;
            case LIQUID -> 0xD8A040;
        };
        double x = s.processed();
        int fragments = 70;
        int size = x < 0.25 ? 4 : x < params.endpointLow() ? 3 : x <= params.endpointHigh() ? 2 : 1;
        double spread = radius * 0.62 * (1.0 - 0.45 * s.clump());
        for (int i = 0; i < fragments; i++) {
            double a = i * 2.39996;
            double r = Math.sqrt((i + 0.5) / fragments) * spread;
            int fx = bowlX + (int) Math.round(Math.cos(a) * r);
            int fy = bowlY + (int) Math.round(Math.sin(a) * r + s.clump() * radius * 0.25);
            int shade = (i * 37) % 40 - 20;
            int color = 0xFF000000 | shadeColor(base, shade - (int) (x * 30));
            g.fill(fx, fy, fx + size, fy + size, color);
        }
        if (params.mode() == PreparationMode.LIQUID && x > params.overworkThreshold()) {
            g.fill(bowlX - radius / 2, bowlY - 4, bowlX + radius / 2, bowlY - 2, 0xAAE8D070);
        } else if (x > params.overworkThreshold()) {
            for (int i = 0; i < 24; i++) {
                int fx = bowlX + (int) Math.round(Math.cos(i * 1.3) * radius * 0.8);
                int fy = bowlY - radius / 3 + (int) Math.round(Math.sin(i * 2.1) * radius * 0.3);
                g.fill(fx, fy, fx + 1, fy + 1, 0x88FFFFFF);
            }
        }
        // pestle
        int tipX = bowlX + (int) Math.round(s.pestleX() * radius * 0.85);
        int tipY = bowlY + (int) Math.round(s.pestleY() * radius * 0.85);
        int lift = (int) Math.round((1 - s.pressure()) * 6);
        int color = s.overpressure() ? 0xFFB03A2E : 0xFF8B6A44;
        for (int i = 0; i < 26; i++) {
            int lx = tipX + i / 2;
            int ly = tipY - lift - i;
            g.fill(lx - 2, ly, lx + 3, ly + 1, color);
        }
        g.fill(tipX - 4, tipY - lift - 2, tipX + 5, tipY - lift + 3, 0xFFC8C0B0);
        // target marker (keyboard/mouse aim)
        int ax = bowlX + (int) Math.round(targetX * radius * 0.85);
        int ay = bowlY + (int) Math.round(targetY * radius * 0.85);
        g.fill(ax - 1, ay - 1, ax + 2, ay + 2, 0xAAFFFFFF);
    }

    private static int shadeColor(int rgb, int delta) {
        int r = Math.max(0, Math.min(255, (rgb >> 16 & 0xFF) + delta));
        int gg = Math.max(0, Math.min(255, (rgb >> 8 & 0xFF) + delta));
        int b = Math.max(0, Math.min(255, (rgb & 0xFF) + delta));
        return r << 16 | gg << 8 | b;
    }

    @Override
    protected Component[] helpLines() {
        return new Component[] {
                Component.translatable("help.immersive_brewing.mortar.goal"),
                Component.translatable("help.immersive_brewing.mortar.move", key(Keys.PESTLE_UP), key(Keys.PESTLE_LEFT), key(Keys.PESTLE_DOWN), key(Keys.PESTLE_RIGHT)),
                Component.translatable("help.immersive_brewing.mortar.press", key(Keys.PRESS)),
                Component.translatable("help.immersive_brewing.mortar.fold", key(Keys.FOLD)),
                Component.translatable("help.immersive_brewing.mortar.finish", key(Keys.FINISH)),
                Component.translatable("help.immersive_brewing.mortar.score"),
                Component.translatable("help.immersive_brewing.seal")
        };
    }
}
