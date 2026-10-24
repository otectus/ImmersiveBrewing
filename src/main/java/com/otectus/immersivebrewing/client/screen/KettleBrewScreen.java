package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.Keys;
import com.otectus.immersivebrewing.client.sound.BrewingSounds;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.minigame.brewing.AdditionSpec;
import com.otectus.immersivebrewing.minigame.brewing.Disturbance;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettleSimulation;
import com.otectus.immersivebrewing.minigame.brewing.PhaseSpec;
import com.otectus.immersivebrewing.minigame.brewing.Signature;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * "Tend the Living Brew" (spec 7, 24.2). Layout: plan strip; a large cutaway of the vessel with the heat guide beside
 * the burner and the circulation guide under the liquid; draft controls on the left (one key raises the draft a level,
 * one lowers it), stir controls on the right; one next-addition tray with the actual item; written cues that stay useful
 * with particles and sound off.
 */
public class KettleBrewScreen extends GameScreen {
    private static final int STEADY = KettleInput.STEADY;

    private int draft;
    private boolean addQueued;
    private boolean toggledCcw;
    private boolean toggledCw;
    private int mouseStir;
    private double lastAngle = Double.NaN;
    private double swirl;
    private int buttonStir;
    private int lastPhase = -1;
    private int lastWarned = -1;
    private int lastCued = -1;
    private final Button[] draftButtons = new Button[3];
    private Button addButton;

    // layout
    private int px, py, pw, ph, vx, vy, vw, vh, colL, colR, colW, trayX, trayY, trayW, addW;

    public KettleBrewScreen(ClientSession session) {
        super(Component.translatable("screen.immersive_brewing.kettle"), session);
        this.draft = sim().draft();
    }

    @Override
    public Keys.Game game() {
        return Keys.Game.KETTLE;
    }

    private KettleSimulation sim() {
        return ((SimHandles.Brewing) session.sim()).sim();
    }

    /** The draft the next frame sends (0 low, 1 medium, 2 high). */
    public int draftLevel() {
        return draft;
    }

    @Override
    protected void init() {
        super.init();
        pw = Math.min(width - 16, 400);
        ph = Math.min(height - 28, 260);
        px = (width - pw) / 2;
        py = 22 + Math.max(0, (height - 28 - ph) / 2);
        vw = Math.max(90, Math.min(180, pw - 210));
        vh = Math.max(66, Math.min(112, ph - 144));
        vx = px + (pw - vw) / 2;
        vy = py + 44;
        colL = px + 8;
        colW = Math.max(56, vx - 40 - colL);
        colR = vx + vw + 14;
        trayW = pw - 16;
        addW = Math.min(trayW / 2, Math.max(72, font.width(Component.translatable("screen.immersive_brewing.add", key(Keys.ADD))) + 8));
        trayX = px + (pw - trayW) / 2;
        trayY = py + ph - 44;
        for (int i = 0; i < 3; i++) {
            int level = i;
            draftButtons[i] = gameButton(Component.empty(), colL, draftY(i), colW, 18, b -> draft = level);
        }
        Button ccw = gameButton(Component.literal("↶ ").append(key(Keys.STIR_CCW)), colR, vy + 4, stirW(), 20, b -> buttonStir = -1);
        ccw.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.stir_ccw").append(" ").append(key(Keys.STIR_CCW))));
        Button cw = gameButton(Component.literal("↷ ").append(key(Keys.STIR_CW)), colR, vy + 28, stirW(), 20, b -> buttonStir = 1);
        cw.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.immersive_brewing.stir_cw").append(" ").append(key(Keys.STIR_CW))));
        addButton = gameButton(Component.translatable("screen.immersive_brewing.add", key(Keys.ADD)),
                trayX + trayW - 6 - addW, trayY + 18, addW, 18, b -> addQueued = true);
    }

    // ---- input ----

    @Override
    protected int frame() {
        int stir = 0;
        boolean vigorous = down(Keys.VIGOROUS);
        boolean toggle = ClientConfig.get(ClientConfig.TOGGLE_STIR);
        boolean ccw = toggle ? toggledCcw : down(Keys.STIR_CCW);
        boolean cw = toggle ? toggledCw : down(Keys.STIR_CW);
        int magnitude = vigorous ? KettleInput.VIGOROUS : STEADY;
        if (ccw && !cw) stir = -magnitude;
        else if (cw && !ccw) stir = magnitude;
        else if (buttonStir != 0) stir = buttonStir * magnitude;
        else if (mouseStir != 0) stir = mouseStir;
        mouseStir = (int) Math.round(mouseStir * 0.5);
        boolean add = addQueued;
        addQueued = false;
        return new KettleInput(draft, stir, add).pack();
    }

    @Override
    protected boolean pressed(KeyMapping key) {
        if (session.countingIn() && key != Keys.DRAFT_UP && key != Keys.DRAFT_DOWN) return true;
        if (key == Keys.DRAFT_UP) draft = Math.min(2, draft + 1);
        else if (key == Keys.DRAFT_DOWN) draft = Math.max(0, draft - 1);
        else if (key == Keys.ADD) addQueued = true;
        else if (key == Keys.STIR_CCW && ClientConfig.get(ClientConfig.TOGGLE_STIR)) {
            toggledCcw = !toggledCcw;
            toggledCw = false;
        } else if (key == Keys.STIR_CW && ClientConfig.get(ClientConfig.TOGGLE_STIR)) {
            toggledCw = !toggledCw;
            toggledCcw = false;
        }
        return true;
    }

    @Override
    protected void onReleaseAll() {
        toggledCcw = false;
        toggledCw = false;
        buttonStir = 0;
        mouseStir = 0;
        lastAngle = Double.NaN;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        if (Ui.inside(mx, my, vx, vy, vw, vh) && ClientConfig.get(ClientConfig.MOUSE_STIRRING)) {
            lastAngle = Math.atan2(my - (vy + vh / 2.0), mx - (vx + vw / 2.0));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && !Double.isNaN(lastAngle) && ClientConfig.get(ClientConfig.MOUSE_STIRRING)) {
            double angle = Math.atan2(my - (vy + vh / 2.0), mx - (vx + vw / 2.0));
            double delta = Mth.wrapDegrees(Math.toDegrees(angle - lastAngle));
            lastAngle = angle;
            // Same cap as the keys: a full-speed circle is a vigorous stir, never more (spec 7: capped mouse circles).
            int command = (int) Math.round(Math.max(-8, Math.min(8, delta / 4.0)));
            if (command != 0) mouseStir = command;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            buttonStir = 0;
            lastAngle = Double.NaN;
        }
        return super.mouseReleased(mx, my, button);
    }

    private int draftY(int level) {
        return vy + 12 + (2 - level) * 22;
    }

    private int stirW() {
        return Math.max(50, px + pw - 8 - colR);
    }

    // ---- sounds and cue edges ----

    @Override
    public void tick() {
        super.tick();
        if (session.ended()) return;
        if (session.paused()) {
            BrewingSounds.quiet(); // the brew stands still under the help
            return;
        }
        KettleSimulation s = sim();
        if (!session.countingIn()) swirl += s.circulation() * 0.25;
        BrewingSounds.kettle(s.heat(), s.circulation(), s.foam(), session.countingIn());
        int phase = s.params().phaseIndex(Math.min(s.step(), s.params().totalSteps() - 1));
        if (phase != lastPhase) lastPhase = phase;
        Disturbance warning = s.warning();
        if (warning != null && warning.step() != lastWarned && s.step() < warning.step()) {
            lastWarned = warning.step();
            BrewingSounds.cue(true);
        }
        int next = s.nextAddition();
        if (next < s.params().additions().size()) {
            AdditionSpec a = s.params().additions().get(next);
            if (s.step() >= a.openStep() && lastCued != next) {
                lastCued = next;
                BrewingSounds.cue(false);
            }
        }
    }

    @Override
    public void removed() {
        BrewingSounds.stop();
        super.removed();
    }

    // ---- rendering ----

    @Override
    protected void renderGame(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Optional small shake (screenShake, off by default; never under reduced motion) while the brew boils over or
        // just after a burner flare. Hit regions do not move; the offset is at most a pixel or two.
        boolean shake = !session.paused() && shaking(sim());
        if (shake) {
            float t = (sim().step() + partialTick) * 0.9F;
            g.pose().pushPose();
            g.pose().translate(Math.sin(t * 2.7) * 1.2, Math.cos(t * 3.1) * 0.8, 0);
        }
        renderBrew(g, partialTick);
        if (shake) g.pose().popPose();
    }

    private static boolean shaking(KettleSimulation s) {
        if (!ClientConfig.get(ClientConfig.SCREEN_SHAKE) || ClientConfig.get(ClientConfig.REDUCED_MOTION)) return false;
        if (s.heat() > s.phase().heatHigh() + 0.12) return true;
        Disturbance d = s.warning();
        return d != null && d.kind() == Disturbance.Kind.BURNER_FLARE && s.step() >= d.step() && s.step() < d.step() + 10;
    }

    private void renderBrew(GuiGraphics g, float partialTick) {
        KettleSimulation s = sim();
        KettleParams params = s.params();
        Ui.Palette p = Ui.palette();
        Ui.panel(g, px, py, pw, ph);
        PhaseSpec phase = s.phase();

        // Plan strip
        ItemStack output = LedgerCodec.loadStack(session.display.getCompound("output"));
        int servings = session.display.getInt("servings");
        Component phaseText = Component.translatable("phase.immersive_brewing." + phase.phase().serialized());
        int remaining = (s.remainingSteps() + 19) / 20;
        Component right = Component.translatable("screen.immersive_brewing.kettle.phase_time", phaseText, remaining);
        if (ClientConfig.get(ClientConfig.NUMERIC_DISPLAY)) right = right.copy().append(" · C " + s.score().control());
        header(g, output, Component.translatable("screen.immersive_brewing.kettle.making", servings, output.getHoverName()), right, px, py, pw);
        Ui.progress(g, px + 6, py + 35, pw - 12, 4, (double) s.step() / params.totalSteps(), p.accent());

        renderVessel(g, s, output, partialTick);

        // Heat guide beside the burner
        int hgx = vx - 26;
        Ui.vgauge(g, hgx, vy, 10, vh, s.heat(), phase.heatLow(), phase.heatHigh(), 0xFFFF7A30);
        fitted(g, Component.translatable("screen.immersive_brewing.heat"), hgx - 8, vy + vh + 2, 32, p.text());

        // Draft controls (left column)
        Component[] names = {Component.translatable("draft.immersive_brewing.low"), Component.translatable("draft.immersive_brewing.medium"),
                Component.translatable("draft.immersive_brewing.high")};
        for (int level = 2; level >= 0; level--) {
            int by = draftY(level);
            boolean selected = draft == level;
            Ui.message(draftButtons[level], names[level]);
            if (selected) g.fill(colL - 1, by - 1, colL + colW + 1, by + 19, p.accent());
        }
        fitted(g, Component.translatable("screen.immersive_brewing.draft"), colL, vy, colW, p.text());
        fitted(g, key(Keys.DRAFT_UP).copy().append(" / ").append(key(Keys.DRAFT_DOWN)), colL, vy + 82, colW, p.muted());

        // Stir controls (right column)
        int sw = stirW();
        boolean ccw = s.circulation() < -0.05;
        boolean cw = s.circulation() > 0.05;
        if (ccw) g.fill(colR - 1, vy + 3, colR + sw + 1, vy + 25, p.accent());
        if (cw) g.fill(colR - 1, vy + 27, colR + sw + 1, vy + 49, p.accent());
        Component vigorous = Component.translatable("screen.immersive_brewing.vigorous_hint", key(Keys.VIGOROUS));
        fitted(g, key(Keys.VIGOROUS).copy().append(" +"), colR, vy + 52, sw, p.muted());
        textTip(vigorous, colR, vy + 52, sw, 10);
        int cuesTop = vy + 66;

        // Circulation guide under the liquid (signed; the |circulation| band on both sides)
        int cgy = vy + vh + 4;
        Ui.inset(g, vx, cgy, vw, 8);
        int mid = vx + vw / 2;
        int lo = (int) Math.round(phase.circLow() * (vw / 2.0 - 1));
        int hi = (int) Math.round(phase.circHigh() * (vw / 2.0 - 1));
        g.fill(mid + lo, cgy + 1, mid + Math.max(lo + 1, hi), cgy + 7, p.band());
        g.fill(mid - Math.max(lo + 1, hi), cgy + 1, mid - lo, cgy + 7, p.band());
        int marker = mid + (int) Math.round(s.circulation() * (vw / 2.0 - 1));
        g.fill(marker - 1, cgy - 2, marker + 2, cgy + 10, 0xFFFFFFFF);
        g.fill(mid, cgy + 6, mid + 1, cgy + 8, p.muted());
        fitted(g, Component.translatable("screen.immersive_brewing.circulation"), vx, cgy + 10, vw, p.text());

        renderTray(g, s);
        renderCues(g, s, phase, cuesTop);
    }

    private void renderVessel(GuiGraphics g, KettleSimulation s, ItemStack output, float partialTick) {
        boolean reduced = ClientConfig.get(ClientConfig.REDUCED_MOTION);
        double intensity = reduced ? 0.3 : ClientConfig.get(ClientConfig.ANIMATION_INTENSITY);
        // iron vessel with copper rim
        g.fill(vx - 4, vy - 2, vx + vw + 4, vy + vh + 2, 0xFF2E2F33);
        g.fill(vx - 5, vy - 4, vx + vw + 5, vy - 1, 0xFFB86B3A);
        int liquidTop = vy + 10;
        int base = colourFor(output, s);
        g.fill(vx, liquidTop, vx + vw, vy + vh, base);
        // sediment
        int sediment = (int) Math.round(s.sediment() * (vh - 14) * 0.45);
        if (sediment > 0) g.fill(vx, vy + vh - sediment, vx + vw, vy + vh, Ui.withAlpha(0x5A2A1A, 220));
        // clarity haze
        int haze = (int) Math.round((1 - s.clarity()) * 170);
        if (haze > 0) g.fill(vx, liquidTop, vx + vw, vy + vh, Ui.withAlpha(0xC8C8C8, haze));
        // swirl lines follow circulation
        double angle = (swirl + (session.paused() ? 0 : partialTick * s.circulation() * 0.25)) * intensity;
        int cx = vx + vw / 2;
        int cy = (liquidTop + vy + vh) / 2;
        int rings = 3;
        for (int r = 1; r <= rings; r++) {
            double radius = r * Math.min(vw, vh - 10) / (2.4 * rings);
            int segments = 10;
            for (int i = 0; i < segments; i++) {
                double a = angle * (1.2 - r * 0.2) + i * (Math.PI * 2 / segments);
                int x = cx + (int) Math.round(Math.cos(a) * radius * 1.6);
                int y = cy + (int) Math.round(Math.sin(a) * radius * 0.6);
                int alpha = (int) Math.round(Math.min(1.0, Math.abs(s.circulation()) * 1.4) * 150);
                if (alpha > 8) g.fill(x - 1, y, x + 2, y + 1, Ui.withAlpha(0xFFFFFF, alpha));
            }
        }
        // bubbles from heat
        long time = s.step();
        double heat = s.heat();
        int bubbleCount = heat < 0.35 ? 0 : (int) Math.round((heat - 0.3) * 30);
        int bubbleSize = heat > s.phase().heatHigh() + 0.1 ? 3 : heat > s.phase().heatHigh() ? 2 : 1;
        for (int i = 0; i < bubbleCount; i++) {
            long seed = (i * 7919L + (time / (reduced ? 6 : 2)) * 31L) & 0xFFFF;
            int bx = vx + 3 + (int) (seed % Math.max(1, vw - 6));
            int by = liquidTop + 2 + (int) ((seed / 7) % Math.max(1, (vy + vh - liquidTop) / 3));
            g.fill(bx, by, bx + bubbleSize, by + bubbleSize, 0xCCFFFFFF);
        }
        // foam layer
        int foam = (int) Math.round(s.foam() * 14);
        if (foam > 0) g.fill(vx, liquidTop - Math.max(0, foam - 6), vx + vw, liquidTop + Math.min(foam, 8), 0xE8F4F4EC);
        // signature glyphs
        Signature sig = s.phase().signature();
        double trouble = s.trouble();
        switch (sig) {
            case ALIGNING_GLINTS -> {
                for (int i = 0; i < 12; i++) {
                    double scatter = trouble * 30;
                    int gx = vx + 8 + i * (vw - 16) / 11 + (int) Math.round(Math.sin(i * 2.3 + time * 0.05 * intensity) * scatter);
                    int gy = cy + (int) Math.round(Math.cos(i * 1.7) * scatter * 0.6);
                    g.fill(gx, gy, gx + 1, gy + 1, 0xFFFFFFC0);
                }
            }
            case DARK_WHORLS -> {
                for (int i = 0; i < (int) (trouble * 10); i++) {
                    double a = angle + i;
                    int wx = cx + (int) Math.round(Math.cos(a) * (8 + i * 3));
                    int wy = cy + (int) Math.round(Math.sin(a) * (4 + i * 1.5));
                    g.fill(wx - 1, wy - 1, wx + 2, wy + 1, 0xAA1A0A20);
                }
            }
            case MAGMA_STREAKS -> {
                for (int i = 0; i < (int) (trouble * 8); i++) {
                    int sx = vx + 6 + (i * 37) % Math.max(1, vw - 12);
                    g.fill(sx, cy - 6, sx + 2, cy + 10, 0xCCFF7A20);
                }
            }
            case FLOATING_MOTES, SUSPENDED_BUBBLES -> {
                for (int i = 0; i < (int) (trouble * 16); i++) {
                    int mx2 = vx + 4 + (i * 53) % Math.max(1, vw - 8);
                    int my2 = liquidTop + 2 + (i * 11) % 10;
                    g.fill(mx2, my2, mx2 + 2, my2 + 2, sig == Signature.FLOATING_MOTES ? 0xDDFFF0D0 : 0xDDCFEFFF);
                }
            }
            case DELICATE_SHIMMER -> {
                int alpha = (int) Math.round((1 - trouble) * 160);
                if (alpha > 8) g.fill(vx + 2, liquidTop + 1, vx + vw - 2, liquidTop + 2, Ui.withAlpha(0xFFFFFF, alpha));
            }
            default -> {
            }
        }
    }

    private static int colourFor(ItemStack output, KettleSimulation s) {
        int water = 0x385DC6;
        int target = output.isEmpty() ? water : PotionUtils.getColor(output);
        double t = s.phase().phase().ordinal() == 0 ? 0.15 : s.phase().phase().ordinal() == 1 ? 0.6 : 1.0;
        return Ui.lerpColor(0xFF000000 | water, 0xFF000000 | target, t);
    }

    private void renderTray(GuiGraphics g, KettleSimulation s) {
        Ui.Palette p = Ui.palette();
        Ui.inset(g, trayX, trayY, trayW, 40);
        KettleParams params = s.params();
        int next = s.nextAddition();
        addButton.active = !session.countingIn() && next < params.additions().size();
        addButton.visible = !help && next < params.additions().size();
        if (next >= params.additions().size()) {
            fitted(g, Component.translatable("screen.immersive_brewing.tray.done"), trayX + 6, trayY + 16, trayW - 12, p.insetMuted());
            return;
        }
        AdditionSpec a = params.additions().get(next);
        ItemStack item = itemFor(a.item());
        g.renderItem(item, trayX + 6, trayY + 12);
        fitted(g, item.getHoverName(), trayX + 26, trayY + 6, trayW - 32, p.insetText());
        Component cue;
        int color;
        if (a.phase() != s.phase().phase()) {
            cue = Component.translatable("screen.immersive_brewing.tray.later", Component.translatable("phase.immersive_brewing." + a.phase().serialized()));
            color = p.insetMuted();
        } else if (s.step() < a.cueStep()) {
            cue = Component.translatable("screen.immersive_brewing.tray.wait", (a.openStep() - s.step() + 19) / 20);
            color = p.insetMuted();
        } else if (s.step() < a.openStep()) {
            cue = Component.translatable("screen.immersive_brewing.tray.soon");
            color = p.warn();
        } else if (s.step() < a.closeStep()) {
            cue = Component.translatable("screen.immersive_brewing.tray.now");
            color = p.good();
        } else {
            cue = Component.translatable("screen.immersive_brewing.tray.late");
            color = p.bad();
        }
        fitted(g, cue, trayX + 26, trayY + 18, trayW - addW - 38, Ui.onDark(color));
        // window bar: cue, window, end of phase
        int barX = trayX + 26;
        int barW = trayW - addW - 38;
        int phaseEnd = params.phaseAt(a.openStep()).end();
        int span = Math.max(1, phaseEnd - a.cueStep());
        int wx0 = barX + (int) ((a.openStep() - a.cueStep()) * (double) barW / span);
        int wx1 = barX + (int) ((a.closeStep() - a.cueStep()) * (double) barW / span);
        g.fill(barX, trayY + 30, barX + barW, trayY + 34, 0xFF2A2A2A);
        g.fill(wx0, trayY + 30, Math.max(wx0 + 1, wx1), trayY + 34, p.good());
        int now = barX + (int) (Math.max(0, Math.min(span, s.step() - a.cueStep())) * (double) barW / span);
        g.fill(now, trayY + 28, now + 1, trayY + 36, 0xFFFFFFFF);
    }

    private static ItemStack itemFor(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return ItemStack.EMPTY;
        var item = ForgeRegistries.ITEMS.getValue(rl);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** The written cues: heat, circulation, the reaction's own signature and any warning (spec 24.2). */
    /**
     * A cue line: the written cue, with its value beside it under the numeric display (so the numbers need no room of
     * their own), or the value alone when written cues are off.
     */
    private static Component cue(boolean written, boolean numbers, Component text, String value) {
        if (!written) return Component.literal(value);
        return numbers ? text.copy().append(" (" + value + ")") : text;
    }

    /** Draws the written cues and/or values from {@code top} down the right column and returns the y below them. */
    private int renderCues(GuiGraphics g, KettleSimulation s, PhaseSpec phase, int top) {
        boolean written = ClientConfig.get(ClientConfig.WRITTEN_CUES);
        boolean numbers = ClientConfig.get(ClientConfig.NUMERIC_DISPLAY);
        if (!written && !numbers) return top;
        Ui.Palette p = Ui.palette();
        List<Component> cues = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        double h = s.heat();
        String heat = h < phase.heatLow() - 0.1 ? "cold" : h < phase.heatLow() ? "warming" : h <= phase.heatHigh() ? "gentle"
                : h <= phase.heatHigh() + 0.12 ? "hot" : "boiling";
        cues.add(cue(written, numbers, Component.translatable("cue.immersive_brewing.heat." + heat), String.format("H %.2f", h)));
        colors.add(heat.equals("gentle") ? p.good() : heat.equals("boiling") || heat.equals("cold") ? p.bad() : p.warn());
        double v = Math.abs(s.circulation());
        String circ = v < phase.circLow() ? "more" : v > phase.circHigh() ? "less" : "good";
        cues.add(cue(written, numbers, Component.translatable("cue.immersive_brewing.circulation." + circ), String.format("V %+.2f", s.circulation())));
        colors.add(circ.equals("good") ? p.good() : p.warn());
        if (phase.signature() != Signature.NONE) {
            String level = s.trouble() < 0.25 ? "low" : s.trouble() < 0.55 ? "mid" : "high";
            cues.add(cue(written, numbers, Component.translatable("cue.immersive_brewing.sig." + phase.signature().serialized() + "." + level),
                    String.format("S %.2f", s.trouble())));
            colors.add(level.equals("low") ? p.good() : level.equals("mid") ? p.warn() : p.bad());
            if (written && phase.reverseStep() >= 0 && s.step() >= phase.reverseStep()) {
                cues.add(Component.translatable("cue.immersive_brewing.reverse"));
                colors.add(p.warn());
            }
        }
        Disturbance warning = s.warning();
        if (written && warning != null) {
            cues.add(Component.translatable("cue.immersive_brewing.warning." + warning.kind().serialized()));
            colors.add(p.bad());
        }
        if (written && s.refusedAddition()) {
            cues.add(Component.translatable("screen.immersive_brewing.tray.refused"));
            colors.add(p.bad());
        }
        int y = top;
        int w = Math.max(60, px + pw - 8 - colR);
        int bottom = trayY - 30;
        for (int i = 0; i < cues.size(); i++) {
            int available = (bottom - y) / 10;
            if (available <= 0) break;
            String marker = colors.get(i) == p.good() ? "✔ " : colors.get(i) == p.bad() ? "! " : "● ";
            Component text = Component.literal(marker).append(cues.get(i));
            int lines = Math.min(available, Ui.lines(text, w));
            cueText(g, text, colR, y, w, lines, colors.get(i));
            y += lines * 10 + 2;
        }
        // The most important cue has its own bounded area under the vessel.
        if (written) cueText(g, cues.get(cues.size() - 1), px + 8, trayY - 26, pw - 16, 2, colors.get(colors.size() - 1));
        return y + 2;
    }

    @Override
    protected Component[] helpLines() {
        return new Component[] {
                Component.translatable("help.immersive_brewing.kettle.goal"),
                Component.translatable("help.immersive_brewing.kettle.heat", key(Keys.DRAFT_UP), key(Keys.DRAFT_DOWN)),
                Component.translatable("help.immersive_brewing.kettle.stir", key(Keys.STIR_CCW), key(Keys.STIR_CW), key(Keys.VIGOROUS)),
                Component.translatable("help.immersive_brewing.kettle.add", key(Keys.ADD)),
                Component.translatable("help.immersive_brewing.kettle.signature"),
                Component.translatable("help.immersive_brewing.kettle.score", QualityMath.BASELINE),
                Component.translatable("help.immersive_brewing.seal")
        };
    }
}
