package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.client.sound.BrewingSounds;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.minigame.distillation.DistillationInput;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.distillation.DistillationSimulation;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;

/**
 * "Catch the Heart" (spec 9.1): heat valve on the still, coolant valve on the coil, and the receiver switch. Early cloudy
 * condensate goes to waste, the clear central run is collected, rising haze means switch back. The markings on the
 * receiver are a generous guide; the condensate's own look is the precise cue.
 */
public class DistillationScreen extends GameScreen {
    private int heat;
    private int coolant;
    private boolean collect;
    private int px, py, pw, ph;
    private Button heatDown, heatUp, coolDown, coolUp, receiver;
    private DistillationSimulation.Condensate lastCondensate;

    public DistillationScreen(ClientSession session) {
        super(Component.translatable("screen.immersive_brewing.alembic"), session);
        DistillationSimulation s = sim();
        heat = s.heatLevel();
        coolant = s.coolLevel();
        collect = s.collecting();
    }

    private DistillationSimulation sim() {
        return ((SimHandles.Distillation) session.sim()).sim();
    }

    @Override
    public Keys.Game game() {
        return Keys.Game.ALEMBIC;
    }

    @Override
    protected void init() {
        super.init();
        pw = Math.min(width - 16, 380);
        ph = Math.min(height - 28, 230);
        px = (width - pw) / 2;
        py = 22 + Math.max(0, (height - 28 - ph) / 2);
        int w = colW();
        int half = w / 2 - 2;
        int by = controlsY();
        heatDown = gameButton(Component.literal("− ").append(key(Keys.HEAT_DOWN)), col(0), by, half, 18, b -> setHeat(heat - 1));
        heatUp = gameButton(Component.literal("+ ").append(key(Keys.HEAT_UP)), col(0) + half + 4, by, half, 18, b -> setHeat(heat + 1));
        coolDown = gameButton(Component.literal("− ").append(key(Keys.COOL_DOWN)), col(1), by, half, 18, b -> setCool(coolant - 1));
        coolUp = gameButton(Component.literal("+ ").append(key(Keys.COOL_UP)), col(1) + half + 4, by, half, 18, b -> setCool(coolant + 1));
        receiver = gameButton(Component.empty(), col(2), by, w, 18, b -> toggleReceiver());
    }

    /** The valve settings and receiver position the next frame sends. */
    public int heatLevel() {
        return heat;
    }

    public int coolantLevel() {
        return coolant;
    }

    public boolean collecting() {
        return collect;
    }

    @Override
    protected int frame() {
        return new DistillationInput(heat, coolant, collect).pack();
    }

    @Override
    protected boolean pressed(KeyMapping key) {
        if (session.countingIn() && key == Keys.RECEIVER) return true;
        if (key == Keys.HEAT_UP) setHeat(heat + 1);
        else if (key == Keys.HEAT_DOWN) setHeat(heat - 1);
        else if (key == Keys.COOL_UP) setCool(coolant + 1);
        else if (key == Keys.COOL_DOWN) setCool(coolant - 1);
        else if (key == Keys.RECEIVER) toggleReceiver();
        return true;
    }

    private void setHeat(int level) {
        int v = Math.max(0, Math.min(DistillationInput.LEVELS - 1, level));
        if (v == heat) return;
        BrewingSounds.valve();
        heat = v;
        if (minecraft != null) minecraft.getNarrator().sayNow(Component.translatable("narration.immersive_brewing.heat_level", heat + 1, DistillationInput.LEVELS));
    }

    private void setCool(int level) {
        int v = Math.max(0, Math.min(DistillationInput.LEVELS - 1, level));
        if (v == coolant) return;
        BrewingSounds.valve();
        coolant = v;
        if (minecraft != null) minecraft.getNarrator().sayNow(Component.translatable("narration.immersive_brewing.coolant_level", coolant + 1, DistillationInput.LEVELS));
    }

    private void toggleReceiver() {
        collect = !collect;
        BrewingSounds.receiver();
        if (minecraft != null) minecraft.getNarrator().sayNow(Component.translatable(collect
                ? "screen.immersive_brewing.collecting" : "screen.immersive_brewing.to_waste"));
    }

    private int col(int i) {
        return px + 8 + i * (pw - 16) / 3;
    }

    private int colW() {
        return (pw - 16) / 3 - 8;
    }

    @Override
    public void tick() {
        super.tick();
        if (!session.ended() && !session.paused()) {
            BrewingSounds.alembic(sim().lastCondensed());
            DistillationSimulation.Condensate condensate = sim().condensate();
            if (condensate != lastCondensate && minecraft != null) {
                lastCondensate = condensate;
                minecraft.getNarrator().sayNow(Component.translatable("condensate.immersive_brewing." + condensate.name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
    }

    @Override
    protected void renderGame(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        DistillationSimulation s = sim();
        DistillationParams params = s.params();
        Ui.Palette p = Ui.palette();
        Ui.panel(g, px, py, pw, ph);
        ItemStack input = LedgerCodec.loadStack(session.display.getCompound("input"));
        Component time = Component.translatable("screen.immersive_brewing.seconds_left", (s.remainingSteps() + 19) / 20);
        header(g, input, Component.translatable("screen.immersive_brewing.alembic.refining", input.getHoverName()), time, px, py, pw);

        int w = colW();
        int top = py + 48;
        int by = controlsY();
        int sampleLines = 1;
        for (var sample : DistillationSimulation.Condensate.values()) sampleLines = Math.max(sampleLines,
                Ui.lines(Component.translatable("condensate.immersive_brewing." + sample.name().toLowerCase(java.util.Locale.ROOT)), w - 16));
        int sy = by - Math.max(18, sampleLines * 10) - 6;
        int gh = sy - 4 - top - 2;
        // Still
        fitted(g, Component.translatable("screen.immersive_brewing.still"), col(0), top - 10, w, p.text());
        Ui.vgauge(g, col(0), top + 2, 10, gh, s.temperature(), params.heatLow(), params.heatHigh(), 0xFFFF7A30);
        int flaskX = col(0) + 22;
        int fill = (int) Math.round((1 - s.fraction()) * (gh - 10));
        g.fill(flaskX, top + 2, flaskX + w - 30, top + 2 + gh, 0xFFB86B3A);
        g.fill(flaskX + 2, top + 2 + gh - fill, flaskX + w - 32, top + gh, input.isEmpty() ? 0xFF6A8AD0 : 0xFF000000 | PotionUtils.getColor(input));
        // Coil
        fitted(g, Component.translatable("screen.immersive_brewing.coil"), col(1), top - 10, w, p.text());
        Ui.vgauge(g, col(1), top + 2, 10, gh, s.coolant(), params.coolLow(), params.coolHigh(), 0xFF60C0FF);
        for (int i = 0; i < 6; i++) {
            int cy = top + 6 + i * (gh - 10) / 6;
            g.fill(col(1) + 22, cy, col(1) + w, cy + 3, Ui.lerpColor(0xFFB86B3A, 0xFF5FA8D8, s.coolant()));
        }
        // Receiver
        fitted(g, Component.translatable("screen.immersive_brewing.receiver"), col(2), top - 10, w, p.text());
        Ui.inset(g, col(2), top + 2, w, gh);
        int nominalHeads = top + 2 + gh - 1 - (int) Math.round(DistillationParams.NOMINAL_HEADS_END * (gh - 2));
        int nominalTails = top + 2 + gh - 1 - (int) Math.round(DistillationParams.NOMINAL_TAILS_START * (gh - 2));
        g.fill(col(2) + 1, nominalTails, col(2) + w - 1, nominalHeads, p.band());
        g.drawString(font, "★", col(2) + w + 2, (nominalTails + nominalHeads) / 2 - 4, p.good(), false);
        int level = top + 2 + gh - 1 - (int) Math.round(s.fraction() * (gh - 2));
        g.fill(col(2) + 1, level, col(2) + w - 1, level + 1, 0xFFFFFFFF);
        // Condensate sample, described in words as well as colour
        DistillationSimulation.Condensate c = s.condensate();
        int sampleColor = switch (c) {
            case CLOUDY -> 0xFFB8B8B0;
            case CLEARING -> 0xFFD8E0E0;
            case CLEAR -> 0xFFE8F8FF;
            case HAZE_RISING -> 0xFFE8E0B0;
            case HAZY -> 0xFFD0C080;
        };
        g.fill(col(2), sy, col(2) + 12, sy + 12, sampleColor);
        Ui.wrapped(g, Component.translatable("condensate.immersive_brewing." + c.name().toLowerCase(java.util.Locale.ROOT)), col(2) + 16, sy + 1,
                w - 16, c == DistillationSimulation.Condensate.CLEAR ? p.good() : c == DistillationSimulation.Condensate.HAZY ? p.bad() : p.warn());

        // Controls
        valve(g, col(0), by, w, heat, Keys.HEAT_DOWN, Keys.HEAT_UP);
        valve(g, col(1), by, w, coolant, Keys.COOL_DOWN, Keys.COOL_UP);
        heatDown.active = heat > 0;
        heatUp.active = heat < DistillationInput.LEVELS - 1;
        coolDown.active = coolant > 0;
        coolUp.active = coolant < DistillationInput.LEVELS - 1;
        receiver.active = !session.countingIn();
        Ui.message(receiver, Component.translatable(collect ? "screen.immersive_brewing.collecting" : "screen.immersive_brewing.to_waste"));
        g.fill(col(2) - 1, by - 1, col(2) + w + 1, by + 19, collect ? p.good() : p.warn());
        fitted(g, key(Keys.RECEIVER), col(2), by + 20, w, p.muted());
        // The numbers replace the standing reminder (it is also in the help overlay), so neither overlaps the other.
        if (ClientConfig.get(ClientConfig.NUMERIC_DISPLAY)) {
            g.drawString(font, String.format("T %.2f  K %.2f  d %.2f  D %d", s.temperature(), s.coolant(), s.fraction(), s.score().distillation()),
                    px + 8, py + ph - 14, p.muted(), false);
        } else {
            Ui.wrapped(g, Component.translatable("screen.immersive_brewing.alembic.cue_help"), px + 8, py + ph - footerHeight(), pw - 16, p.muted());
        }
    }

    /** Top of the valve and receiver buttons; their pips and key labels end 29 px lower, above the bottom text. */
    private int controlsY() {
        return py + ph - footerHeight() - 32;
    }

    private int footerHeight() {
        return ClientConfig.get(ClientConfig.NUMERIC_DISPLAY) ? 18
                : Ui.lines(Component.translatable("screen.immersive_brewing.alembic.cue_help"), pw - 16) * 10 + 6;
    }

    private void valve(GuiGraphics g, int x, int y, int w, int level, KeyMapping down, KeyMapping up) {
        for (int i = 0; i < DistillationInput.LEVELS; i++) {
            int dx = x + i * (w / DistillationInput.LEVELS);
            g.fill(dx + 1, y + 20, dx + w / DistillationInput.LEVELS - 1, y + 24, i <= level ? 0xFFFFC040 : 0xFF404040);
        }
    }

    @Override
    protected Component[] helpLines() {
        return new Component[] {
                Component.translatable("help.immersive_brewing.alembic.goal"),
                Component.translatable("help.immersive_brewing.alembic.heat", key(Keys.HEAT_DOWN), key(Keys.HEAT_UP)),
                Component.translatable("help.immersive_brewing.alembic.cool", key(Keys.COOL_DOWN), key(Keys.COOL_UP)),
                Component.translatable("help.immersive_brewing.alembic.receiver", key(Keys.RECEIVER)),
                Component.translatable("help.immersive_brewing.alembic.score"),
                Component.translatable("help.immersive_brewing.seal")
        };
    }
}
