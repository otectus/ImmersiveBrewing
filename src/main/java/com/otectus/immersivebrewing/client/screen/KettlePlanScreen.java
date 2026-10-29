package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.StartBrewC2S;
import com.otectus.immersivebrewing.network.packet.CatalogQueryC2S;
import com.otectus.immersivebrewing.network.packet.BatchActionC2S;
import com.otectus.immersivebrewing.network.packet.BatchPreviewS2C;
import com.otectus.immersivebrewing.blockentity.BatchStaging;
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
    private PlanListS2C plans;
    private final List<PlanListS2C.Entry> filtered = new ArrayList<>();
    private final Map<String, Integer> outputCounts = new HashMap<>();
    private EditBox search;
    private Button brew;
    private Button favorite;
    private Button prepare, finish, previous, next;
    private BatchPreviewS2C preview;
    private BatchStaging.Kits kits=BatchStaging.Kits.RAW;
    private int flags, page, request, debounce=-1, quantity, carrier=-1, queriedGeneration=-1;
    private boolean routeChanged;
    private boolean alternatives;
    private final List<Button> rowButtons=new ArrayList<>();
    private final Ui.ScrollArea detail = new Ui.ScrollArea();
    private int selected = -1;
    private int scroll;
    private int px, py, pw, ph, listW, rows;

    public KettlePlanScreen(@Nullable Screen parent, PlanListS2C plans) {
        super(Component.translatable("screen.immersive_brewing.plans"));
        this.parent = parent;
        this.plans = plans;
        request = Math.max(0, plans.request());
        page = plans.page();
        try { flags = Integer.parseInt(ClientGuide.catalogFilter().replace("catalog:", "")); }
        catch (NumberFormatException ignored) { flags = 0; }
        if (flags != 0 && flags != 1 && flags != 2 && flags != 4 && flags != 8 && flags != 16) flags = 0;
        if (plans.request() == PlanListS2C.SELECTED_ROUTE) flags = 0;
        quantity=plans.servings()>0?plans.servings():ClientGuide.preferredServings();
        for (PlanListS2C.Entry e : plans.entries()) outputCounts.merge(e.output().getDescriptionId() + e.output().getHoverName().getString(), 1, Integer::sum);
    }

    @Override
    protected void init() {
        pw = Math.min(width - 12, 420);
        ph = Math.min(height - 12, 260);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listW = Math.max(130, pw * 2 / 5);
        rows = Math.max(2, (ph - 110) / ROW);
        String query = search == null ? (plans.request() == PlanListS2C.SELECTED_ROUTE ? ""
                : plans.query().isEmpty() ? ClientGuide.search() : plans.query()) : search.getValue();
        search = new EditBox(font, px + 6, py + 20, listW - 6, 14, Component.translatable("screen.immersive_brewing.search"));
        Ui.hint(search, Component.translatable("screen.immersive_brewing.search_hint"));
        search.setValue(query);
        search.setMaxLength(128);
        search.setResponder(s -> { page=0;debounce=6; });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.translatable("catalog.immersive_brewing.filter."+flags), b->{
            flags=switch(flags){case 0->1;case 1->2;case 2->4;case 4->8;case 8->16;default->0;};
            b.setMessage(Component.translatable("catalog.immersive_brewing.filter."+flags));page=0;
            ModNetwork.sendToServer(new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_FILTER,"catalog:"+flags,ItemStack.EMPTY,0));query();
        }).bounds(px+6,py+38,listW-42,18).build());
        addRenderableWidget(Button.builder(Component.literal("≋"),b->{alternatives=!alternatives;page=0;query();})
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("catalog.immersive_brewing.alternatives")))
                .bounds(px+listW-32,py+38,26,18).build());
        int actionW = (pw - listW - 40) / 2;
        brew = addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.brew"), b -> start())
                .bounds(px + pw - actionW - 6, py + ph - 24, actionW, 18).build());
        favorite = addRenderableWidget(Button.builder(Component.literal("☆"), b -> toggleFavorite())
                .bounds(px + listW + actionW + 10, py + ph - 24, 18, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(px + listW + 8, py + ph - 24, actionW, 18).build());
        prepare=addRenderableWidget(Button.builder(Component.translatable("staging.immersive_brewing.prepare"),b->batch(BatchActionC2S.Action.PREPARE))
                .bounds(px+listW+8,py+ph-66,(pw-listW-18)/2,18).build());
        finish=addRenderableWidget(Button.builder(Component.translatable("finishing.immersive_brewing.finish"),b->batch(BatchActionC2S.Action.FINISH))
                .bounds(px+listW+10+(pw-listW-18)/2,py+ph-66,(pw-listW-18)/2,18).build());
        addRenderableWidget(Button.builder(Component.translatable("staging.immersive_brewing.kits."+kits.name().toLowerCase(Locale.ROOT)),b->{
            kits=BatchStaging.Kits.values()[(kits.ordinal()+1)%BatchStaging.Kits.values().length];
            b.setMessage(Component.translatable("staging.immersive_brewing.kits."+kits.name().toLowerCase(Locale.ROOT)));preview=null;batch(BatchActionC2S.Action.PREVIEW);
        }).bounds(px+listW+8,py+ph-46,pw-listW-16,18).build());
        previous=addRenderableWidget(Button.builder(Component.literal("<"),b->{page=Math.max(0,page-1);query();}).bounds(px+6,py+ph-24,22,18).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),b->{page++;query();}).bounds(px+listW-30,py+ph-24,22,18).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.servings",quantity),b->{
            quantity=quantity%6+1;b.setMessage(Component.translatable("notebook.immersive_brewing.servings",quantity));
            ModNetwork.sendToServer(new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SERVINGS,"",ItemStack.EMPTY,quantity));query();
        }).bounds(px+30,py+ph-24,listW-62,18).build());
        addRenderableWidget(Button.builder(Component.translatable("staging.immersive_brewing.carrier"),b->{
            int nextCarrier = -1;
            if(minecraft!=null&&minecraft.player!=null) for(int i=carrier+1;i<36;i++)
                if(minecraft.player.getInventory().getItem(i).getItem() instanceof com.otectus.immersivebrewing.item.BatchCarrierItem){nextCarrier=i;break;}
            carrier = nextCarrier;
            b.setMessage(Component.translatable(carrier<0?"staging.immersive_brewing.carrier":"staging.immersive_brewing.carrier_selected",carrier+1));
            preview=null;batch(BatchActionC2S.Action.PREVIEW);
        }).bounds(px+6,py+ph-46,listW-12,18).build());
        detail.bounds(px + listW + 6, py + 20, pw - listW - 12, ph - 90);
        rowButtons.clear();
        for(int i=0;i<rows;i++) { final int row=i;
            rowButtons.add(addRenderableWidget(Button.builder(Component.empty(),b->{if(scroll+row<filtered.size())select(scroll+row);})
                    .bounds(px+6,py+60+i*ROW,listW-12,ROW-2).build()));
        }
        refilter();
        if (!query.isEmpty() || flags != 0) debounce = 0;
        if (!filtered.isEmpty()) select(Math.max(0,selected));
        if (plans.problem() != null) {
            // Nothing to choose from: only the explanation and Back.
            search.visible = false;
            favorite.visible = false;
            brew.visible = false;
        }
    }

    private void refilter() {
        filtered.clear();
        List<PlanListS2C.Entry> favorites = new ArrayList<>();
        for (PlanListS2C.Entry e : plans.entries()) {
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
        preview=null;
        batch(BatchActionC2S.Action.PREVIEW);
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
        if(prepare!=null) prepare.active=e!=null&&preview!=null&&preview.problem()==null;
        if(finish!=null) finish.active=e!=null&&preview!=null&&e.finishReady();
        if(previous!=null) previous.active=page>0;
        if(next!=null) next.active=(page+1)*com.otectus.immersivebrewing.recipe.CatalogSearch.PAGE_SIZE<plans.total();
        for(int i=0;i<rowButtons.size();i++) {
            Button b=rowButtons.get(i);int index=scroll+i;b.visible=index<filtered.size();
            if(b.visible) { var entry=filtered.get(index);b.setMessage(Component.literal(index==selected?"> ":"").append(entry.output().getHoverName())); }
        }
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
        if(debounce>=0&&--debounce<0) query();
        if(com.otectus.immersivebrewing.client.ServerCatalog.ready()&&plans.generation()!=0
                &&plans.generation()!=com.otectus.immersivebrewing.client.ServerCatalog.generation()
                &&queriedGeneration!=com.otectus.immersivebrewing.client.ServerCatalog.generation()) { preview=null;routeChanged=true;query(); }
        updateButtons();
    }

    private void query() {
        String text=search==null?"":search.getValue(); request++;preview=null;
        queriedGeneration=com.otectus.immersivebrewing.client.ServerCatalog.generation();
        List<String> aliases=new ArrayList<>(); String lower=text.toLowerCase(Locale.ROOT).trim();
        if(!lower.isEmpty()) {
            for(var potion:net.minecraftforge.registries.ForgeRegistries.POTIONS) {
                ItemStack s=net.minecraft.world.item.alchemy.PotionUtils.setPotion(new ItemStack(net.minecraft.world.item.Items.POTION),potion);
                if(s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(lower)) aliases.add(String.valueOf(net.minecraftforge.registries.ForgeRegistries.POTIONS.getKey(potion)));
            }
            for(var effect:net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS) if(effect.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(lower)) aliases.add(String.valueOf(net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getKey(effect)));
            for(var item:net.minecraftforge.registries.ForgeRegistries.ITEMS) if(new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(lower)) aliases.add(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item)));
        }
        ModNetwork.sendToServer(new NotebookPrefsC2S(NotebookPrefsC2S.Action.SET_SEARCH,text,ItemStack.EMPTY,0));
        ModNetwork.sendToServer(new CatalogQueryC2S(plans.pos(),request,text,aliases.stream().distinct().limit(256).toList(),flags,page,alternatives,quantity));
    }

    public void accept(PlanListS2C message) {
        if(!message.pos().equals(plans.pos())||message.request()>0&&message.request()<request) return;
        String selectedKey=current()==null?"":current().key().serialize();
        plans=message;page=message.page();quantity=message.servings();preview=null;refilter();
        if (message.request() == PlanListS2C.SELECTED_ROUTE) {
            debounce = -1; flags = 0; search = null; rebuildWidgets(); return;
        }
        for(int i=0;i<filtered.size();i++) if(filtered.get(i).key().serialize().equals(selectedKey)){selected=i;break;}
        if(current()!=null) batch(BatchActionC2S.Action.PREVIEW);
    }

    public void preview(BatchPreviewS2C message) {
        if(current()!=null&&message.pos().equals(plans.pos())&&message.plan().equals(current().key().serialize())) preview=message;
    }

    private void batch(BatchActionC2S.Action action) {
        var entry=current();if(entry==null)return;
        ModNetwork.sendToServer(new BatchActionC2S(plans.pos(),action,entry.key().serialize(),quantity,kits,carrier,preview==null?0:preview.revision()));
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
        int ly = py + 60;
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
        if (!Ui.inside(mx, my, px + 5, py + 60, listW - 10, rows * ROW)) return super.mouseScrolled(mx, my, delta);
        int max = Math.max(0, filtered.size() - rows);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        updateButtons();
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
        int ly = py + 60;
        Ui.inset(g, px + 5, ly - 2, listW - 10, rows * ROW + 2);
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
        Ui.fit(g,plans.incomplete() ? Component.translatable("catalog.immersive_brewing.incomplete",plans.total())
                : Component.translatable("catalog.immersive_brewing.page",page+1,plans.total()),
                px+6,py+ph-60,listW-12,p.muted());
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
        y += Ui.wrapped(g,Component.translatable("staging.immersive_brewing.bottles",quantity,plans.input().getHoverName()),x,y,w,p.text());
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
        List<ItemStack> returns = preview != null && preview.problem() == null ? preview.remainders() : e.remainders();
        for (ItemStack remainder : returns) {
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.returns", remainder.getCount(), remainder.getHoverName()), x, y, w, p.muted());
        }
        y += 2;
        y += Ui.wrapped(g, Component.translatable(e.qualityAffectsEffects() ? "screen.immersive_brewing.plan.quality_effects"
                : "screen.immersive_brewing.plan.quality_recorded"), x, y, w, p.muted());
        y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.extraction",
                preview != null && preview.problem() == null ? preview.extraction() : e.expectedExtraction()), x, y, w, p.muted());
        if (ClientConfig.get(ClientConfig.REQUEST_ASSIST)) {
            var allow = com.otectus.immersivebrewing.config.ServerConfig.ALLOW_ASSISTED_CONTROLS;
            double time = com.otectus.immersivebrewing.config.ServerConfig.get(allow)
                    ? Math.min(ClientConfig.get(ClientConfig.ASSIST_TIME), com.otectus.immersivebrewing.config.ServerConfig.get(com.otectus.immersivebrewing.config.ServerConfig.MAX_ASSIST_TIME_MULTIPLIER)) : 1;
            double band = com.otectus.immersivebrewing.config.ServerConfig.get(allow)
                    ? Math.min(ClientConfig.get(ClientConfig.ASSIST_BAND), com.otectus.immersivebrewing.config.ServerConfig.get(com.otectus.immersivebrewing.config.ServerConfig.MAX_ASSIST_BAND_MULTIPLIER)) : 1;
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.assistance_policy", time, band), x, y, w, p.muted());
        }
        if (e.contextual()) y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.plan.contextual"), x, y, w, p.warn());
        if (e.missing() != null) y += 2 + Ui.wrapped(g, e.missing(), x, y + 2, w, p.bad());
        if(routeChanged) y+=Ui.wrapped(g,Component.translatable("catalog.immersive_brewing.reloaded"),x,y,w,p.warn());
        y+=Ui.wrapped(g,Component.translatable("staging.immersive_brewing.breakpoint"),x,y,w,p.muted());
        if(plans.omitted()>0) y+=Ui.wrapped(g,Component.translatable("catalog.immersive_brewing.omitted",plans.omitted()),x,y,w,p.warn());
        y+=Ui.wrapped(g,Component.translatable("catalog.immersive_brewing.contextual_unknown"),x,y,w,p.muted());
        if(preview!=null) y+=Ui.wrapped(g,preview.problem()!=null?preview.problem():Component.translatable("staging.immersive_brewing.preview",preview.extraction(),preview.paddleUses()<0?"—":preview.paddleUses()),x,y,w,preview.problem()!=null?p.bad():p.good());
        if(e.finishingSeconds()>0) y+=Ui.wrapped(g,Component.translatable("finishing.immersive_brewing.explain",e.finishingSeconds()),x,y,w,p.muted());
        return y + 4;
    }
}
