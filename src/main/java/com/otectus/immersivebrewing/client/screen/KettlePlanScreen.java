package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.StartBrewC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Choosing a plan (spec 13): every path the server validated from the bottles in the rack, searchable, with each step,
 * the exact reagents (raw and prepared), fuel charges, returned containers, the expected time and what is missing.
 * Alternatives that reach the same potion are listed separately so a rare reagent is never spent silently.
 */
public class KettlePlanScreen extends Screen {
    private static final int ROW = 20;
    @Nullable
    private final Screen parent;
    private final PlanListS2C plans;
    private final List<PlanListS2C.Entry> filtered = new ArrayList<>();
    private final Map<String, Integer> outputCounts = new HashMap<>();
    private EditBox search;
    private Button brew;
    private Button favorite;
    private final Ui.ScrollArea detail = new Ui.ScrollArea();
    private int selected = -1;
    private int scroll;
    private int px, py, pw, ph, listW, rows;

    public KettlePlanScreen(@Nullable Screen parent, PlanListS2C plans) {
        super(Component.translatable("screen.immersive_brewing.plans"));
        this.parent = parent;
        this.plans = plans;
        for (PlanListS2C.Entry e : plans.entries()) outputCounts.merge(e.output().getDescriptionId() + e.output().getHoverName().getString(), 1, Integer::sum);
    }

    @Override
    protected void init() {
        pw = Math.min(width - 12, 420);
        ph = Math.min(height - 12, 260);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listW = Math.max(130, pw * 2 / 5);
        rows = Math.max(3, (ph - 60) / ROW);
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, px + 6, py + 20, listW - 6, 14, Component.translatable("screen.immersive_brewing.search"));
        Ui.hint(search, Component.translatable("screen.immersive_brewing.search_hint"));
        search.setValue(query);
        search.setResponder(s -> refilter());
        addRenderableWidget(search);
        brew = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.brew"), b -> start())
                .bounds(px + pw - 86, py + ph - 26, 80, 20).build());
        favorite = addRenderableWidget(Button.builder(Component.literal("☆"), b -> toggleFavorite())
                .bounds(px + pw - 110, py + ph - 26, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(px + listW + 8, py + ph - 26, 60, 20).build());
        detail.bounds(px + listW + 6, py + 20, pw - listW - 12, ph - 52);
        refilter();
        if (selected < 0 && !filtered.isEmpty()) select(0);
        if (plans.problem() != null) {
            // Nothing to choose from: only the explanation and Back.
            search.visible = false;
            favorite.visible = false;
            brew.visible = false;
        }
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        filtered.clear();
        List<PlanListS2C.Entry> favorites = new ArrayList<>();
        for (PlanListS2C.Entry e : plans.entries()) {
            if (!q.isEmpty() && !matches(e, q)) continue;
            if (ClientGuide.isFavorite(e.key().serialize())) favorites.add(e);
            else filtered.add(e);
        }
        filtered.addAll(0, favorites);
        scroll = 0;
        selected = filtered.isEmpty() ? -1 : 0;
        detail.reset();
        updateButtons();
    }

    private static boolean matches(PlanListS2C.Entry e, String q) {
        if (e.output().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (PlanListS2C.Step s : e.steps()) {
            if (new ItemStack(s.reagent()).getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private void select(int index) {
        selected = index;
        detail.reset();
        updateButtons();
    }

    @Nullable
    private PlanListS2C.Entry current() {
        return selected >= 0 && selected < filtered.size() ? filtered.get(selected) : null;
    }

    private void updateButtons() {
        if (brew == null) return;
        PlanListS2C.Entry e = current();
        brew.active = e != null && e.available();
        favorite.active = e != null && !e.contextual();
        favorite.setMessage(Component.literal(e != null && ClientGuide.isFavorite(e.key().serialize()) ? "★" : "☆"));
    }

    private void start() {
        PlanListS2C.Entry e = current();
        if (e == null || !e.available()) return;
        boolean assist = ClientConfig.get(ClientConfig.REQUEST_ASSIST);
        float time = assist ? (float) (double) ClientConfig.get(ClientConfig.ASSIST_TIME) : 1F;
        float band = assist ? (float) (double) ClientConfig.get(ClientConfig.ASSIST_BAND) : 1F;
        ModNetwork.sendToServer(new StartBrewC2S(plans.pos(), e.key(), time, band));
    }

    private void toggleFavorite() {
        PlanListS2C.Entry e = current();
        if (e == null || e.contextual()) return;
        String key = e.key().serialize();
        NotebookPrefsC2S.Action action = ClientGuide.isFavorite(key) ? NotebookPrefsC2S.Action.REMOVE_FAVORITE : NotebookPrefsC2S.Action.ADD_FAVORITE;
        ModNetwork.sendToServer(new NotebookPrefsC2S(action, key, e.output(), 0));
    }

    @Override
    public void tick() {
        updateButtons();
    }

    @Nullable
    public Screen parent() {
        return parent;
    }

    public PlanListS2C plans() {
        return plans;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (detail.click(mx, my, button)) return true;
        int ly = py + 38;
        for (int i = 0; button == 0 && plans.problem() == null && i < rows; i++) {
            int index = scroll + i;
            if (index >= filtered.size()) break;
            if (Ui.inside(mx, my, px + 6, ly + i * ROW, listW - 12, ROW - 2)) {
                select(index);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (detail.wheel(mx, my, delta)) return true;
        if (!Ui.inside(mx, my, px + 5, py + 38, listW - 10, rows * ROW)) return super.mouseScrolled(mx, my, delta);
        int max = Math.max(0, filtered.size() - rows);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return detail.drag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        detail.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!search.isFocused() && detail.key(keyCode)) return true;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN && !filtered.isEmpty()) {
            select(Math.min(filtered.size() - 1, selected + 1));
            if (selected >= scroll + rows) scroll = selected - rows + 1;
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP && !filtered.isEmpty()) {
            select(Math.max(0, selected - 1));
            if (selected < scroll) scroll = selected;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Ui.panel(g, px, py, pw, ph);
        Ui.Palette p = Ui.palette();
        Component title = plans.input().isEmpty() || plans.servings() <= 0 ? Component.translatable("screen.immersive_brewing.plans")
                : Component.translatable("screen.immersive_brewing.plans_for", plans.servings(), plans.input().getHoverName());
        Ui.fit(g, title, px + 6, py + 6, pw - 12, p.text());
        if (plans.problem() != null) {
            detail.bounds(px + 6, py + 30, pw - 12, ph - 62);
            detail.begin(g);
            int bottom = detail.top() + Ui.wrapped(g, plans.problem(), px + 6, detail.top(), detail.width(), p.bad());
            detail.end(g, bottom);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        // List
        int ly = py + 38;
        Ui.inset(g, px + 5, ly - 2, listW - 10, rows * ROW + 2);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            if (index >= filtered.size()) break;
            PlanListS2C.Entry e = filtered.get(index);
            int y = ly + i * ROW;
            if (index == selected) g.fill(px + 6, y, px + listW - 6, y + ROW - 2, Ui.withAlpha(p.accent(), 0x99));
            g.renderItem(e.output(), px + 8, y + 1);
            Component name = e.output().getHoverName();
            if (outputCounts.getOrDefault(e.output().getDescriptionId() + name.getString(), 0) > 1) name = name.copy().append(" ").append(via(e));
            String mark = (ClientGuide.isFavorite(e.key().serialize()) ? "★ " : "") + (e.available() ? "✔" : "✖");
            g.drawString(font, mark, px + listW - 10 - font.width(mark), y + 5, Ui.onDark(e.available() ? p.good() : p.bad()), false);
            int maxW = listW - 46 - font.width(mark);
            String shown = name.getString();
            if (font.width(shown) > maxW) shown = font.plainSubstrByWidth(shown, maxW - font.width("…")) + "…";
            g.drawString(font, shown, px + 28, y + 5, p.insetText(), false);
        }
        if (filtered.isEmpty()) Ui.wrapped(g, Component.translatable("screen.immersive_brewing.no_plans"), px + 10, ly + 4, listW - 20, p.insetMuted());
        if (filtered.size() > rows) {
            int barH = rows * ROW;
            int knob = Math.max(10, barH * rows / filtered.size());
            int ky = ly + (barH - knob) * scroll / Math.max(1, filtered.size() - rows);
            g.fill(px + listW - 6, ky, px + listW - 4, ky + knob, p.muted());
        }
        detail.begin(g);
        int bottom = details(g, current());
        detail.end(g, bottom);
        super.render(g, mouseX, mouseY, partialTick);
        if (Ui.inside(mouseX, mouseY, px + 6, py + 6, pw - 12, 10) && font.width(title) > pw - 12) {
            g.renderTooltip(font, font.split(title, 240), mouseX, mouseY);
        }
        for (int i = 0; i < rows && scroll + i < filtered.size(); i++) {
            if (Ui.inside(mouseX, mouseY, px + 6, ly + i * ROW, listW - 12, ROW - 2)) {
                g.renderTooltip(font, filtered.get(scroll + i).output(), mouseX, mouseY);
                break;
            }
        }
    }

    private static Component via(PlanListS2C.Entry e) {
        for (PlanListS2C.Step s : e.steps()) {
            if (s.role() == KettleSetup.Role.EFFECT || s.role() == KettleSetup.Role.CORRUPTION) {
                return Component.translatable("screen.immersive_brewing.via", new ItemStack(s.reagent()).getHoverName());
            }
        }
        return Component.translatable("screen.immersive_brewing.via", new ItemStack(e.steps().get(0).reagent()).getHoverName());
    }

    private int details(GuiGraphics g, @Nullable PlanListS2C.Entry e) {
        if (e == null) return detail.top();
        Ui.Palette p = Ui.palette();
        int x = px + listW + 6;
        int w = detail.width();
        int y = detail.top();
        g.renderItem(e.output(), x, y);
        y += Math.max(20, Ui.wrapped(g, e.output().getHoverName(), x + 20, y + 4, w - 20, p.text()) + 6);
        Component tier = Component.translatable("tier.immersive_brewing." + e.tier());
        y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.tier_time", tier, e.seconds()), x, y, w, p.muted()) + 1;
        for (int i = 0; i < e.steps().size(); i++) {
            PlanListS2C.Step s = e.steps().get(i);
            Component line = Component.literal((i + 1) + ". ").append(Component.translatable("phase.immersive_brewing." + s.phase().serialized()))
                    .append(": ").append(new ItemStack(s.reagent()).getHoverName());
            y += Ui.wrapped(g, line, x, y, w, p.text());
        }
        y += 3;
        g.drawString(font, Component.translatable("screen.immersive_brewing.plan.costs"), x, y, p.accent(), false);
        y += 10;
        for (PlanListS2C.Cost c : e.costs()) {
            boolean enough = c.loaded() + c.kitGroups() >= c.needed();
            Component line = Component.translatable(c.kitGroups() > 0 ? "screen.immersive_brewing.plan.cost_kits" : "screen.immersive_brewing.plan.cost",
                    c.needed(), new ItemStack(c.item()).getHoverName(), c.loaded(), c.kitGroups());
            y += Ui.wrapped(g, Component.literal(enough ? "✔ " : "✖ ").append(line), x, y, w, enough ? p.text() : p.bad());
            if (c.preparable() && c.kitGroups() == 0) y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.preparable"), x + 8, y, w - 8, p.muted());
        }
        boolean fuelOk = e.fuelAvailable() >= e.fuelNeeded();
        y += Ui.wrapped(g, Component.literal(fuelOk ? "✔ " : "✖ ").append(Component.translatable("screen.immersive_brewing.plan.fuel",
                e.fuelNeeded(), e.fuelAvailable())), x, y, w, fuelOk ? p.text() : p.bad());
        if (!e.remainders().isEmpty()) {
            ItemStack r = e.remainders().get(0);
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.returns", e.remainders().size(), r.getHoverName()), x, y, w, p.muted());
        }
        y += 2;
        y += Ui.wrapped(g, Component.translatable(e.qualityAffectsEffects() ? "screen.immersive_brewing.plan.quality_effects"
                : "screen.immersive_brewing.plan.quality_recorded"), x, y, w, p.muted());
        y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.extraction", e.expectedExtraction()), x, y, w, p.muted());
        if (e.contextual()) y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.contextual"), x, y, w, p.warn());
        if (e.missing() != null) y += 2 + Ui.wrapped(g, e.missing(), x, y + 2, w, p.bad());
        return y + 4;
    }
}
