package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.client.ServerCatalog;
import com.otectus.immersivebrewing.client.tutorial.GuidedPractice;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanStep;
import com.otectus.immersivebrewing.guide.GuideHooks;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.NotebookPrefsC2S;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import com.otectus.immersivebrewing.session.SessionKind;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Brewer's Notebook (spec 10, 25.1): a built-in guide that needs no other mod. Pages for getting started (with the
 * Kettle's crafting grid), stations, controls, quality and recovery; a searchable list of the known brewing paths with
 * exact reagents, fuel and returned containers; reagent preparation hints and reaction signatures; favourites; the last
 * result with its improvement tip; and links to free practice sessions. It is never a research gate.
 */
public class NotebookScreen extends Screen {
    private enum Page { START, STATIONS, CONTROLS, QUALITY, RECOVERY, RECIPES, REAGENTS, COMPATIBILITY, FAVORITES, JOURNAL, PRACTICE }

    /** The Kettle's own crafting recipe; a datapack may replace it under this id, or remove it. */
    private static final ResourceLocation KETTLE_RECIPE = ImmersiveBrewing.id("brewers_kettle");

    /**
     * The paths the recipe page lists: loaded infusion descriptions whose synchronized server status is supported or
     * metadata-only, sorted by result. Client recipes provide presentation, never validation authority. Rebuilt when
     * the recipe manager, server catalog generation or language changes.
     */
    private static List<InfusionRecipe> knownPaths = List.of();
    private static WeakReference<RecipeManager> knownFor = new WeakReference<>(null);
    @Nullable
    private static String knownLanguage;
    private static int knownCatalogGeneration = -1;

    private Page page = Page.START;
    private final Ui.ScrollArea content = new Ui.ScrollArea();
    private int mouseX, mouseY;
    private EditBox search;
    private int px, py, pw, ph, tabW;
    private boolean compact;
    @Nullable
    private CraftingRecipe kettleRecipe;
    @Nullable private CraftingRecipe paddleRecipe;
    @Nullable private CraftingRecipe mortarRecipe;
    @Nullable private CraftingRecipe alembicRecipe;
    /** The item under the mouse in a crafting grid this frame, for its tooltip; the visible text area bounds it. */
    private ItemStack hovered = ItemStack.EMPTY;
    @Nullable private Component diagnostic;
    private int clipTop, clipBottom;
    private int selectedFavorite;
    private int selectedHistory;
    private String filter = "all";
    private boolean restored;
    private final List<RowHit> favoriteHits = new ArrayList<>();
    private final List<RowHit> historyHits = new ArrayList<>();
    @Nullable private Button previousEntry;
    @Nullable private Button nextEntry;
    private int entryNavY;
    private int entryNavButtonW;
    private record RowHit(int index, int x, int y, int w, int h) {}

    public NotebookScreen() {
        super(Component.translatable("item.immersive_brewing.brewers_notebook"));
    }

    @Override
    protected void init() {
        if (!restored) {
            try {
                if (!ClientGuide.lastTab().isEmpty()) page = Page.valueOf(ClientGuide.lastTab().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
            filter = ClientGuide.filter();
            restored = true;
        }
        pw = Math.min(width - 12, 420);
        ph = Math.min(height - 12, 250);
        compact = width < 480 || height < 300;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int widestTab = 0;
        for (Page tab : Page.values()) widestTab = Math.max(widestTab,
                font.width(Component.translatable("notebook.immersive_brewing.tab." + tab.name().toLowerCase(Locale.ROOT))));
        tabW = compact ? 0 : Math.min(pw - 150, Math.max(92, widestTab + 16));
        String query = search == null ? ClientGuide.search() : search.getValue();
        search = new EditBox(font, px + tabW + 4, py + (compact ? 26 : 6), pw - tabW - 10, 14, Component.translatable("screen.immersive_brewing.search"));
        Ui.hint(search, Component.translatable("screen.immersive_brewing.search_hint"));
        search.setValue(query);
        search.setResponder(s -> content.reset());
        kettleRecipe = findKettleRecipe();
        paddleRecipe = findCraftingRecipe(ImmersiveBrewing.id("wooden_brewing_paddle"), ModItems.WOODEN_BREWING_PADDLE.get());
        mortarRecipe = findCraftingRecipe(ImmersiveBrewing.id("apothecary_mortar"), ModItems.APOTHECARY_MORTAR.get());
        alembicRecipe = findCraftingRecipe(ImmersiveBrewing.id("alembic"), ModItems.ALEMBIC.get());
        rebuild();
    }

    /** The Kettle's crafting recipe as this world loaded it: its own id first, else any crafting recipe that makes one. */
    @Nullable
    private CraftingRecipe findKettleRecipe() {
        return findCraftingRecipe(KETTLE_RECIPE, ModItems.BREWERS_KETTLE.get());
    }

    @Nullable
    private CraftingRecipe findCraftingRecipe(ResourceLocation preferred, net.minecraft.world.item.Item target) {
        if (minecraft == null || minecraft.level == null) return null;
        var recipes = minecraft.level.getRecipeManager();
        var access = minecraft.level.registryAccess();
        if (recipes.byKey(preferred).orElse(null) instanceof CraftingRecipe own && own.getResultItem(access).is(target)) {
            return own;
        }
        for (CraftingRecipe r : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (r.getResultItem(access).is(target)) return r;
        }
        return null;
    }

    private void rebuild() {
        clearWidgets();
        previousEntry = nextEntry = null;
        Page[] pages = Page.values();
        int th = Math.max(14, Math.min(18, (ph - 12) / pages.length));
        if (compact) {
            addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.tab." + page.name().toLowerCase(Locale.ROOT)), b -> {
                page = pages[(page.ordinal() + 1) % pages.length]; content.reset(); rebuild();
            }).bounds(px + 4, py + 6, pw - 60, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(px + pw - 52, py + 6, 48, 18).build());
        }
        for (int i = 0; !compact && i < pages.length; i++) {
            Page p = pages[i];
            Button b = Button.builder(Component.translatable("notebook.immersive_brewing.tab." + p.name().toLowerCase(Locale.ROOT)), btn -> {
                persistView();
                page = p;
                content.reset();
                rebuild();
            }).bounds(px + 4, py + 6 + i * th, tabW - 8, th - 2).build();
            b.active = p != page;
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(b.getMessage()));
            addRenderableWidget(b);
        }
        if (page == Page.RECIPES || page == Page.REAGENTS) addRenderableWidget(search);
        if (page == Page.RECIPES) recipePreferenceButtons();
        if (page == Page.START) onboardingButtons();
        if (page == Page.FAVORITES || page == Page.JOURNAL) entryNavigationButtons();
        if (page == Page.FAVORITES) favoriteButtons();
        if (page == Page.JOURNAL) journalButtons();
        if (page == Page.PRACTICE) {
            int y = py + ph - 134;
            for (GuidedPractice.Stage stage : GuidedPractice.Stage.values()) {
                addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.practice.guided." + GuidedPractice.id(stage)),
                        b -> ClientPacketHandler.guidedPractice()).bounds(px + tabW + 6, y, pw - tabW - 14, 16).build());
                y += 18;
            }
            for (SessionKind kind : SessionKind.values()) {
                addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.practice." + kind.name().toLowerCase(Locale.ROOT)),
                        b -> ClientPacketHandler.practice(kind)).bounds(px + tabW + 6, y, pw - tabW - 14, 16).build());
                y += 18;
            }
        }
    }

    private void onboardingButtons() {
        int x = px + tabW + 6;
        int y = py + ph - 26;
        int each = (pw - tabW - 18) / 3;
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.onboarding.first"), b -> chooseOnboarding(1))
                .bounds(x, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.onboarding.improve"), b -> chooseOnboarding(2))
                .bounds(x + each + 2, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.onboarding.explore"), b -> chooseOnboarding(3))
                .bounds(x + (each + 2) * 2, y, each, 20).build());
    }

    private void recipePreferenceButtons() {
        int x = px + tabW + 6;
        int y = py + ph - 26;
        int w = pw - tabW - 14;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> setServings(ClientGuide.preferredServings() - 1)).bounds(x, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.servings", ClientGuide.preferredServings()), b -> {})
                .bounds(x + 22, y, Math.max(52, w - 92), 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> setServings(ClientGuide.preferredServings() + 1)).bounds(x + w - 68, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.filter." + filter), b -> cycleFilter())
                .bounds(x + w - 46, y, 46, 20).build());
    }

    private void setServings(int servings) {
        send(NotebookPrefsC2S.Action.SET_SERVINGS, "", ItemStack.EMPTY, Math.max(1, Math.min(6, servings)));
    }

    private void cycleFilter() {
        filter = filter.equals("favorites") ? "all" : "favorites";
        send(NotebookPrefsC2S.Action.SET_FILTER, filter, ItemStack.EMPTY, 0);
        content.reset();
        rebuild();
    }

    private void chooseOnboarding(int choice) {
        send(NotebookPrefsC2S.Action.SET_ONBOARDING, "", ItemStack.EMPTY, choice);
        if (choice == 1) ClientPacketHandler.guidedPractice();
        else {
            page = choice == 2 ? Page.QUALITY : Page.RECIPES;
            content.reset();
            rebuild();
        }
    }

    private void favoriteButtons() {
        int x = px + tabW + 6;
        int y = py + ph - 48;
        int w = pw - tabW - 14;
        int each = (w - 4) / 3;
        boolean available=favorite()!=null;
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.inspect"), b -> inspectFavorite()).bounds(x, y, each, 20).build());
        boolean pinned = ClientGuide.pinned() != null;
        addRenderableWidget(Button.builder(Component.translatable(pinned ? "notebook.immersive_brewing.action.dismiss_pin" : "notebook.immersive_brewing.action.pin"),
                b -> { if (pinned) send(NotebookPrefsC2S.Action.DISMISS_PIN, "", ItemStack.EMPTY, 0); else pinFavorite(); }).bounds(x + each + 2, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.practice"), b -> {
            ClientGuide.Favorite f = favorite();
            if (f != null) send(NotebookPrefsC2S.Action.PRACTICE_PLAN, f.key(), ItemStack.EMPTY, ClientGuide.preferredServings());
        }).bounds(x + (each + 2) * 2, y, each, 20).build());
        int half=(w-2)/2;
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.select"), b -> selectFavorite()).bounds(x,y+22,half,20).build());
        boolean replacing = ClientGuide.pendingFavorite() != null;
        addRenderableWidget(Button.builder(Component.translatable(replacing ? "notebook.immersive_brewing.action.replace" : "notebook.immersive_brewing.action.remove"),
                b -> { if (replacing) replaceFavorite(); else removeFavorite(); }).bounds(x+half+2,y+22,half,20).build());
        if(!available) for(var child:children()) if(child instanceof Button button&&button.getY()>=y&&button.getX()>=x)
            button.active=pinned&&button.getX()==x+each+2&&button.getY()==y;
    }

    private ClientGuide.Favorite favorite() {
        List<ClientGuide.Favorite> list = ClientGuide.favorites();
        return list.isEmpty() ? null : list.get(Math.max(0, Math.min(selectedFavorite, list.size() - 1)));
    }

    private void inspectFavorite() {
        ClientGuide.Favorite f = favorite();
        if (f == null) return;
        search.setValue(f.output().getHoverName().getString());
        page = Page.RECIPES;
        content.reset();
        rebuild();
    }

    private void pinFavorite() {
        ClientGuide.Favorite f = favorite();
        if (f != null) send(NotebookPrefsC2S.Action.PIN_RECIPE, f.key(), f.output(), ClientGuide.preferredServings());
    }

    private void selectFavorite() {
        ClientGuide.Favorite f = favorite();
        if (f != null) GuideHooks.request(GuideHooks.JournalAction.SELECT_AT_STATION, f.key(), ClientGuide.preferredServings());
    }

    private void removeFavorite() {
        ClientGuide.Favorite f = favorite();
        if (f != null) send(NotebookPrefsC2S.Action.REMOVE_FAVORITE, f.key(), ItemStack.EMPTY, 0);
    }

    private void replaceFavorite() {
        ClientGuide.Favorite pending = ClientGuide.pendingFavorite();
        List<ClientGuide.Favorite> list = ClientGuide.favorites();
        if (pending != null && !list.isEmpty()) {
            int index = Math.max(0, Math.min(selectedFavorite, list.size() - 1));
            send(NotebookPrefsC2S.Action.REPLACE_FAVORITE, pending.key(), pending.output(), index);
        }
    }

    private void journalButtons() {
        int x = px + tabW + 6;
        int y = py + ph - 26;
        int each = (pw - tabW - 18) / 3;
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.repeat_short"), b -> repeatSelected())
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("notebook.immersive_brewing.action.repeat")))
                .bounds(x, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.pin"), b -> pinHistory())
                .bounds(x + each + 2, y, each, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.action.clear_short"),
                b -> send(NotebookPrefsC2S.Action.CLEAR_HISTORY, "", ItemStack.EMPTY, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("notebook.immersive_brewing.action.clear_history")))
                .bounds(x + (each + 2) * 2, y, each, 20).build());
    }

    /** One bounded focus row selects every saved entry without turning scroll-translated rows into overlapping widgets. */
    private void entryNavigationButtons() {
        int x = px + tabW + 6;
        int w = pw - tabW - 14;
        entryNavY = py + ph - (page==Page.FAVORITES?70:48);
        entryNavButtonW = 22;
        previousEntry = addRenderableWidget(Button.builder(Component.literal("<"),
                b -> changeEntry(-1)).createNarration(ignored->Component.translatable("notebook.immersive_brewing.action.previous_entry"))
                .bounds(x, entryNavY, entryNavButtonW, 18).build());
        nextEntry = addRenderableWidget(Button.builder(Component.literal(">"),
                b -> changeEntry(1)).createNarration(ignored->Component.translatable("notebook.immersive_brewing.action.next_entry"))
                .bounds(x + w - entryNavButtonW, entryNavY, entryNavButtonW, 18).build());
        previousEntry.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("notebook.immersive_brewing.action.previous_entry")));
        nextEntry.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("notebook.immersive_brewing.action.next_entry")));
        updateEntryButtons();
    }

    private int entryCount() {
        return page == Page.FAVORITES ? ClientGuide.favorites().size()
                : page == Page.JOURNAL ? ClientGuide.history().size() : 0;
    }

    private int selectedEntry() {
        int count = entryCount();
        if (count == 0) return 0;
        int selected = page == Page.FAVORITES ? selectedFavorite : selectedHistory;
        return Math.max(0, Math.min(selected, count - 1));
    }

    private void changeEntry(int delta) {
        selectEntry(selectedEntry() + delta, true);
    }

    private void selectEntry(int requested, boolean narrate) {
        int count = entryCount();
        if (count == 0) return;
        int selected = Math.max(0, Math.min(requested, count - 1));
        if (page == Page.FAVORITES) selectedFavorite = selected;
        else if (page == Page.JOURNAL) selectedHistory = selected;
        updateEntryButtons();
        if (narrate && minecraft != null) minecraft.getNarrator().sayNow(entryNarration());
    }

    private void updateEntryButtons() {
        if (previousEntry == null || nextEntry == null) return;
        int count = entryCount();
        int selected = selectedEntry();
        if (page == Page.FAVORITES) selectedFavorite = selected;
        else if (page == Page.JOURNAL) selectedHistory = selected;
        previousEntry.active = count > 0 && selected > 0;
        nextEntry.active = count > 0 && selected + 1 < count;
    }

    private Component entryNarration() {
        int count = entryCount();
        int selected = selectedEntry();
        if (page == Page.FAVORITES && count > 0) {
            ClientGuide.Favorite favorite = ClientGuide.favorites().get(selected);
            Component reason = favoriteReason(favorite);
            return Component.translatable("narration.immersive_brewing.notebook.favorite", selected + 1, count,
                    favorite.output().getHoverName(), reason == null
                            ? Component.translatable("notebook.immersive_brewing.favorite.available") : reason);
        }
        if (page == Page.JOURNAL && count > 0) {
            ClientGuide.History entry = ClientGuide.history().get(selected);
            Component detail = historyDetail(entry, selected);
            return Component.translatable("narration.immersive_brewing.notebook.journal", selected + 1, count, detail);
        }
        return Component.empty();
    }

    private Component historyDetail(ClientGuide.History entry, int index) {
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(new java.util.Date(entry.timestamp()));
        var detail = Component.translatable("notebook.immersive_brewing.journal.entry", entry.servings(),
                entry.output().getHoverName(), entry.quality()).append(". ")
                .append(Component.translatable("notebook.immersive_brewing.journal.when", when)).append(". ")
                .append(Component.translatable("notebook.immersive_brewing.journal.metrics", Math.round(entry.thermal() * 100),
                        Math.round(entry.circulation() * 100), Math.round(entry.dosing() * 100)));
        if (entry.assistTime() > 1F || entry.assistBand() > 1F) {
            detail.append(". ").append(Component.translatable("screen.immersive_brewing.assistance_approved",
                    entry.assistTime(), entry.assistBand()));
        }
        Component observation = observation(entry);
        if (observation != null) detail.append(". ").append(observation);
        ClientGuide.History previous = ClientGuide.previousComparable(index);
        if (previous != null) {
            String comparison = entry.sameAssistance(previous) ? "notebook.immersive_brewing.journal.compare"
                    : "notebook.immersive_brewing.journal.compare_assistance_changed";
            detail.append(". ").append(Component.translatable(comparison, signed(entry.quality() - previous.quality()),
                    signed(entry.control() - previous.control())));
        }
        return detail;
    }

    private ClientGuide.History history() {
        List<ClientGuide.History> list = ClientGuide.history();
        return list.isEmpty() ? null : list.get(Math.max(0, Math.min(selectedHistory, list.size() - 1)));
    }

    private void repeatSelected() {
        ClientGuide.History h = history();
        if (h != null && !h.plan().isEmpty()) send(NotebookPrefsC2S.Action.REPEAT_SETUP, h.plan(), h.input(), h.servings());
    }

    private void pinHistory() {
        ClientGuide.History h = history();
        if (h != null && !h.plan().isEmpty()) send(NotebookPrefsC2S.Action.PIN_RECIPE, h.plan(), h.output(), h.servings());
    }

    private static void send(NotebookPrefsC2S.Action action, String key, ItemStack display, int value) {
        ModNetwork.sendToServer(new NotebookPrefsC2S(action, key, display, value));
    }

    private void persistView() {
        if (search != null) send(NotebookPrefsC2S.Action.SET_SEARCH, search.getValue(), ItemStack.EMPTY, 0);
        send(NotebookPrefsC2S.Action.SET_FILTER, filter, ItemStack.EMPTY, 0);
        send(NotebookPrefsC2S.Action.SET_TAB, page.name().toLowerCase(Locale.ROOT), ItemStack.EMPTY, 0);
    }

    @Override
    public void onClose() {
        persistView();
        super.onClose();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return content.wheel(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            List<RowHit> hits = page == Page.FAVORITES ? favoriteHits : page == Page.JOURNAL ? historyHits : List.of();
            for (RowHit hit : hits) {
                if (my >= clipTop && my < clipBottom && Ui.inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    selectEntry(hit.index(), true);
                    return true;
                }
            }
        }
        return content.click(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return content.drag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        content.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!search.isFocused() && content.key(key)) return true;
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        renderBackground(g);
        Ui.bookPanel(g, px, py, pw, ph);
        Ui.Palette p = Ui.palette();
        int x = px + tabW + 6;
        int w = pw - tabW - 14;
        g.fill(px + tabW - 2, py + 6, px + tabW - 1, py + ph - 6, p.panelDark());
        g.fill(px + tabW - 1, py + 6, px + tabW, py + ph - 6, p.panelLight());
        boolean searchable = page == Page.RECIPES || page == Page.REAGENTS;
        int top = searchable ? search.getY() + search.getHeight() + 6 : compact ? py + 30 : py + 8;
        clipTop = top;
        clipBottom = py + ph - (page == Page.PRACTICE ? 140 : page == Page.FAVORITES ? 74 : page == Page.JOURNAL ? 52
                : page == Page.START || page == Page.RECIPES ? 32 : 6);
        hovered = ItemStack.EMPTY;
        diagnostic = null;
        favoriteHits.clear();
        historyHits.clear();
        content.bounds(x, top, w, clipBottom - top);
        w = content.width();
        content.begin(g);
        int y = content.top();
        switch (page) {
            case RECIPES -> y = recipes(g, x, y, w);
            case REAGENTS -> y = reagents(g, x, y, w);
            case COMPATIBILITY -> y = compatibility(g, x, y, w);
            case FAVORITES -> y = favorites(g, x, y, w);
            case JOURNAL -> y = journal(g, x, y, w);
            default -> y = text(g, page.name().toLowerCase(Locale.ROOT), x, y, w, mouseX, mouseY);
        }
        content.end(g, y);
        renderEntrySelection(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        if(!compact) {
            int tabH=Math.max(14,Math.min(18,(ph-12)/Page.values().length));
            g.fill(px+4,py+7+page.ordinal()*tabH,px+6,py+5+(page.ordinal()+1)*tabH,p.accent());
        }
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
        else if(diagnostic!=null) g.renderTooltip(font,font.split(diagnostic,Math.min(260,width-24)),mouseX,mouseY);
    }

    private int text(GuiGraphics g, String key, int x, int y, int w, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing." + key + ".title").withStyle(s -> s.withBold(true)), x, y, w, p.text()) + 4;
        for (int i = 1; i <= 32; i++) {
            String k = "notebook.immersive_brewing." + key + "." + i;
            Component c = Component.translatable(k);
            if (c.getString().equals(k)) break;
            y += Ui.wrapped(g, c, x, y, w, p.text()) + 4;
            // The Kettle's recipe right after the line that asks for a Kettle.
            if (key.equals("start") && i == 1) {
                y = craftingGrid(g, kettleRecipe, "notebook.immersive_brewing.start.no_recipe", "notebook.immersive_brewing.start.recipe", x, y, w, mouseX, mouseY);
                y = craftingGrid(g, paddleRecipe, "notebook.immersive_brewing.start.no_paddle_recipe", "notebook.immersive_brewing.start.paddle_recipe", x, y, w, mouseX, mouseY);
            }
            if (key.equals("stations") && i == 1) {
                y = craftingGrid(g, mortarRecipe, "notebook.immersive_brewing.stations.no_mortar_recipe", "notebook.immersive_brewing.stations.mortar_recipe", x, y, w, mouseX, mouseY);
            }
            if (key.equals("stations") && i == 3) {
                y = craftingGrid(g, alembicRecipe, "notebook.immersive_brewing.stations.no_alembic_recipe", "notebook.immersive_brewing.stations.alembic_recipe", x, y, w, mouseX, mouseY);
            }
        }
        if (key.equals("start")) {
            y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.mode." + ServerConfig.brewingMode().name().toLowerCase(Locale.ROOT)),
                    x, y, w, p.accent()) + 4;
        }
        if (key.equals("stations")) y = instructionCards(g, x, y, w);
        if (key.equals("recovery")) y = recoveryCard(g, x, y, w);
        return y;
    }

    /** Still-first cards reuse live-screen language; they remain useful at zero animation intensity. */
    private int instructionCards(GuiGraphics g, int x, int y, int w) {
        y = card(g, x, y, w, Component.translatable("screen.immersive_brewing.mortar"),
                Component.translatable("help.immersive_brewing.mortar.goal"), new ItemStack(ModItems.APOTHECARY_MORTAR.get()));
        y = card(g, x, y, w, Component.translatable("screen.immersive_brewing.kettle"),
                Component.translatable("help.immersive_brewing.kettle.signature"), new ItemStack(ModItems.BREWERS_KETTLE.get()));
        return card(g, x, y, w, Component.translatable("screen.immersive_brewing.alembic"),
                Component.translatable("help.immersive_brewing.alembic.receiver", "[R]"), new ItemStack(ModItems.ALEMBIC.get()));
    }

    private int recoveryCard(GuiGraphics g, int x, int y, int w) {
        return card(g, x, y, w, Component.translatable("screen.immersive_brewing.sealed_title"),
                Component.translatable("screen.immersive_brewing.sealed_body"));
    }

    private int card(GuiGraphics g, int x, int y, int w, Component title, Component body) {
        return card(g,x,y,w,title,body,ItemStack.EMPTY);
    }

    private int card(GuiGraphics g,int x,int y,int w,Component title,Component body,ItemStack icon) {
        Ui.Palette p = Ui.palette();
        int bodyY=icon.isEmpty()?15:25;
        int h = bodyY + 11 + Ui.lines(body, w - 12) * 10;
        Ui.inset(g, x, y, w, h);
        if(!icon.isEmpty()) g.renderItem(icon,x+6,y+4);
        Ui.fit(g,title,x+(icon.isEmpty()?6:28),y+7,w-(icon.isEmpty()?12:34),p.insetText());
        Ui.wrapped(g,body,x+6,y+bodyY,w-12,p.insetMuted());
        return y + h + 5;
    }

    private void renderEntrySelection(GuiGraphics g, int mouseX, int mouseY) {
        if (previousEntry == null || nextEntry == null) return;
        updateEntryButtons();
        int count = entryCount();
        if (count == 0) return;
        int x = px + tabW + 8 + entryNavButtonW;
        int w = pw - tabW - 18 - entryNavButtonW * 2;
        ItemStack output = page == Page.FAVORITES ? ClientGuide.favorites().get(selectedEntry()).output()
                : ClientGuide.history().get(selectedEntry()).output();
        Component label = page == Page.FAVORITES ? output.getHoverName()
                : Component.translatable("notebook.immersive_brewing.journal.entry",
                        ClientGuide.history().get(selectedEntry()).servings(), output.getHoverName(),
                        ClientGuide.history().get(selectedEntry()).quality());
        Ui.fit(g, label, x, entryNavY + 5, Math.max(1, w), Ui.palette().text());
        if (Ui.inside(mouseX, mouseY, x, entryNavY, Math.max(1, w), 18)) hovered = output;
    }

    /**
     * The Kettle's recipe as a crafting grid, from the loaded recipe so a datapack's change shows (playtest: the recipe
     * written out as a list of materials read like more things a brewer needs). Hovering an item names it; an ingredient
     * that takes a tag shows its members in turn.
     */
    private int craftingGrid(GuiGraphics g, @Nullable CraftingRecipe recipe, String missingKey, String captionKey,
                             int x, int y, int w, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        if (recipe == null || minecraft == null || minecraft.level == null) {
            return y + Ui.wrapped(g, Component.translatable(missingKey), x, y, w, p.muted()) + 4;
        }
        int gx = x + 2;
        for (int i = 0; i < 9; i++) Ui.slot(g, gx + i % 3 * 18, y + i / 3 * 18);
        List<Ingredient> ingredients = recipe.getIngredients();
        int columns = recipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
        boolean cycle = !ClientConfig.get(ClientConfig.REDUCED_MOTION);
        for (int i = 0; i < ingredients.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            ItemStack stack = shown(ingredients.get(i), cycle);
            if (col > 2 || row > 2 || stack.isEmpty()) continue;
            int sx = gx + col * 18;
            int sy = y + row * 18;
            g.renderItem(stack, sx + 1, sy + 1);
            hover(stack, sx, sy, 18, mouseX, mouseY);
        }
        // Beside the grid: where it is crafted, then the arrow and the result in a larger slot.
        int cx = gx + 3 * 18 + 8;
        int caption = Ui.wrapped(g, Component.translatable(captionKey), cx, y + 1, x + w - cx, p.text());
        int ry = y + Math.max(14, caption + 4);
        int ay = ry + 13;
        g.fill(cx, ay - 1, cx + 12, ay + 1, p.muted());
        for (int i = 0; i < 5; i++) g.fill(cx + 12 + i, ay - 5 + i, cx + 13 + i, ay + 5 - i, p.muted());
        ItemStack result = recipe.getResultItem(minecraft.level.registryAccess());
        int rx = cx + 24;
        Ui.inset(g, rx, ry, 26, 26);
        g.renderItem(result, rx + 5, ry + 5);
        g.renderItemDecorations(font, result, rx + 5, ry + 5);
        hover(result, rx, ry, 26, mouseX, mouseY);
        return y + Math.max(3 * 18, ry + 26 - y) + 6;
    }

    /** The item an ingredient shows: a tag's members take turns each second; under reduced motion the first stays. */
    private static ItemStack shown(Ingredient ingredient, boolean cycle) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return ItemStack.EMPTY;
        return items[cycle ? (int) (Util.getMillis() / 1000 % items.length) : 0];
    }

    private void hover(ItemStack stack, int x, int y, int size, int mouseX, int mouseY) {
        if (mouseY >= clipTop && mouseY < clipBottom && Ui.inside(mouseX, mouseY, x, y, size, size)) hovered = stack;
    }

    private int recipes(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        if (minecraft == null || minecraft.level == null) return y;
        String q = search.getValue().toLowerCase(Locale.ROOT).trim();
        if (!ServerCatalog.ready()) {
            return y + Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.catalog.syncing"), x, y, w, p.muted());
        }
        List<InfusionRecipe> list = knownPaths();
        int servings = ClientGuide.preferredServings();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.recipes.intro", servings), x, y, w, p.muted()) + 4;
        boolean matched = false;
        for (InfusionRecipe r : list) {
            if (!matchesFilter(r)) continue;
            ItemStack out = r.getResultItem(minecraft.level.registryAccess());
            ItemStack in = r.input().stack(null);
            String name = out.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !name.contains(q) && r.steps().stream().noneMatch(s -> stepName(s).toLowerCase(Locale.ROOT).contains(q))) continue;
            matched = true;
            g.renderItem(out, x, y);
            hover(out, x, y, 16, mouseX, mouseY);
            Component head = r.generic() ? Component.translatable("notebook.immersive_brewing.recipes.container", out.getHoverName())
                    : Component.translatable("notebook.immersive_brewing.recipes.path", in.getHoverName(), out.getHoverName());
            y += Math.max(16, Ui.wrapped(g, head, x + 18, y + 4, w - 18, p.text()) + 4);
            int per = Math.max(1, r.servingsPerReagent());
            int applications = (servings + per - 1) / per;
            for (PlanStep s : r.steps()) {
                Component line = Component.translatable("notebook.immersive_brewing.recipes.step",
                        Component.translatable("phase.immersive_brewing." + s.phase().serialized()), applications, Component.literal(stepName(s)));
                y += Ui.wrapped(g, line, x + 18, y, w - 18, p.muted());
            }
            y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.recipes.fuel", applications * r.steps().size()), x + 18, y, w - 18, p.muted()) + 4;
        }
        if (!matched) y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.search.empty"), x, y, w, p.text()) + 8;
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.recipes.stand"), x, y, w, p.muted());
        return y;
    }

    private boolean matchesFilter(InfusionRecipe recipe) {
        if (!filter.equals("favorites")) return true;
        for (ClientGuide.Favorite favorite : ClientGuide.favorites()) {
            var key = com.otectus.immersivebrewing.recipe.PlanKey.parse(favorite.key());
            if (key != null && key.parts().stream().anyMatch(part -> part.source().equals(recipe.getId()))) return true;
        }
        return false;
    }

    private List<InfusionRecipe> knownPaths() {
        RecipeManager recipes = minecraft.level.getRecipeManager();
        String language = minecraft.getLanguageManager().getSelected();
        if (knownFor.get() != recipes || knownCatalogGeneration != ServerCatalog.generation() || !language.equals(knownLanguage)) {
            List<InfusionRecipe> list = new ArrayList<>();
            for (InfusionRecipe r : recipes.getAllRecipesFor(ModRecipeTypes.INFUSION.get())) {
                PlanIndex.Entry entry = ServerCatalog.entry(r.getId());
                if (entry != null && (entry.status() == PlanIndex.Status.SUPPORTED || entry.status() == PlanIndex.Status.METADATA_ONLY)) list.add(r);
            }
            var access = minecraft.level.registryAccess();
            list.sort(Comparator.comparing(r -> r.getResultItem(access).getHoverName().getString()));
            knownPaths = List.copyOf(list);
            knownFor = new WeakReference<>(recipes);
            knownLanguage = language;
            knownCatalogGeneration = ServerCatalog.generation();
        }
        return knownPaths;
    }

    private static String stepName(PlanStep s) {
        ItemStack[] items = s.reagent().getItems();
        return items.length == 0 ? "?" : items[0].getHoverName().getString();
    }

    private int reagents(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        String q = search.getValue().toLowerCase(Locale.ROOT).trim();
        boolean matched = false;
        for (ReagentProfile r : BrewingDefinitions.client().reagentProfiles()) {
            for (var id : r.selector().ids()) {
                var item = ForgeRegistries.ITEMS.getValue(id);
                if (item == null) continue;
                ItemStack stack = new ItemStack(item);
                if (!q.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
                matched = true;
                g.renderItem(stack, x, y);
                hover(stack, x, y, 16, mouseX, mouseY);
                Component mode = r.preparation() == null ? Component.translatable("mode.immersive_brewing.none")
                        : Component.translatable("mode.immersive_brewing." + r.preparation().mode().serialized());
                y += Math.max(16, Ui.wrapped(g, stack.getHoverName().copy().append(" — ").append(mode), x + 18, y + 4, w - 18, p.text()) + 4);
                if (r.preparation() != null) {
                    y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.reagents.hint." + r.preparation().mode().serialized()), x + 18, y, w - 18, p.muted());
                }
                if (r.reaction().profile() != null) {
                    var brew = BrewingDefinitions.client().brewProfile(r.reaction().profile());
                    y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.reagents.signature",
                            Component.translatable("signature.immersive_brewing." + brew.infuse().signature().serialized())), x + 18, y, w - 18, p.muted());
                }
                y += 4;
            }
        }
        if (!matched) y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.search.empty"), x, y, w, p.text());
        return y;
    }

    /** Player-facing view of the authoritative server coverage report; raw warnings stay in operator diagnostics. */
    private int compatibility(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.title").withStyle(s -> s.withBold(true)),
                x, y, w, p.text()) + 4;
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.intro"), x, y, w, p.text()) + 4;
        if (!ServerCatalog.ready()) {
            return y + Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.catalog.syncing"), x, y, w, p.muted());
        }
        if (ServerCatalog.arrowConflict()) {
            y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.arrow_conflict"), x, y, w, p.warn()) + 4;
        }
        List<PlanIndex.Entry> entries = new ArrayList<>(ServerCatalog.entries());
        entries.sort(Comparator.comparing(e -> e.recipe().toString()));
        if (entries.isEmpty()) {
            y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.none"), x, y, w, p.muted()) + 4;
        }
        for (PlanIndex.Entry entry : entries) {
            String status=entry.status().name().toLowerCase(Locale.ROOT);
            int color=switch(entry.status()) {
                case SUPPORTED -> p.good(); case METADATA_ONLY -> p.accent(); case EXCLUDED -> p.muted(); case UNSUPPORTED -> p.bad();
            };
            ItemStack output=minecraft==null||minecraft.level==null?ItemStack.EMPTY:minecraft.level.getRecipeManager().byKey(entry.recipe())
                    .map(recipe->recipe.getResultItem(minecraft.level.registryAccess())).orElse(ItemStack.EMPTY);
            boolean generic=output.getItem() instanceof net.minecraft.world.item.PotionItem
                    &&net.minecraft.world.item.alchemy.PotionUtils.getPotion(output)==net.minecraft.world.item.alchemy.Potions.EMPTY;
            Component name=output.isEmpty()?Component.translatable("notebook.immersive_brewing.compatibility.path"):
                    generic?Component.translatable(output.getItem().getDescriptionId()):output.getHoverName();
            int rowY=y;
            if(!output.isEmpty()) { g.renderItem(output,x,y+2);if(!generic) hover(output,x,y+2,16,mouseX,mouseY); }
            int tx=x+22,tw=w-24;
            y+=Math.max(18,Ui.wrapped(g,name,tx,y+2,tw,p.text())+3);
            y+=Ui.wrapped(g,Component.translatable("notebook.immersive_brewing.compatibility.status."+status),tx,y,tw,color)+3;
            if(entry.status()==PlanIndex.Status.UNSUPPORTED || entry.status()==PlanIndex.Status.EXCLUDED)
                y+=Ui.wrapped(g,reportReason(entry.reason()),tx,y,tw,p.muted())+3;
            if(Ui.inside(mouseX,mouseY,tx,rowY,tw,y-rowY)&&mouseY>=clipTop&&mouseY<clipBottom) diagnostic=Component.literal(entry.recipe().toString()).append(" · ").append(reportReason(entry.reason()));
            g.fill(x,y,x+w-2,y+1,p.panelDark());y+=7;
        }
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.contextual"), x, y, w, p.muted()) + 4;
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.compatibility.budget"), x, y, w, p.muted());
        return y;
    }

    private static Component reportReason(String reason) {
        if (reason == null || reason.isEmpty()) return Component.empty();
        String[] encoded = reason.split("\\|");
        Object[] args = new Object[Math.max(0, encoded.length - 1)];
        for (int i = 1; i < encoded.length; i++) args[i - 1] = Component.literal(encoded[i]);
        return Component.translatable(encoded[0], args);
    }

    private int favorites(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        List<ClientGuide.Favorite> favorites = ClientGuide.favorites();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.favorites.capacity", favorites.size(), PlayerGuide.MAX_FAVORITES), x, y, w, p.muted()) + 6;
        if (!ClientGuide.favoriteNotice().isEmpty())
            y += Ui.wrapped(g, Component.translatable(ClientGuide.favoriteNotice()), x, y, w, p.warn()) + 6;
        for (int i = 0; i < favorites.size(); i++) {
            ClientGuide.Favorite f = favorites.get(i);
            Component problem = favoriteReason(f);
            Component reason = problem == null ? Component.translatable("notebook.immersive_brewing.favorite.available") : problem;
            int rowY = y;
            int titleH = Math.max(18, 2 + Ui.lines(f.output().getHoverName(), w - 26) * 10);
            int rowH = titleH + Ui.lines(reason, w - 26) * 10 + 5;
            boolean selected = i == selectedFavorite;
            if (selected) Ui.inset(g, x, y, w - 2, rowH);
            g.renderItem(f.output(), x + 3, y + 3);
            hover(f.output(), x + 3, y + 3, 16, mouseX, mouseY);
            Ui.wrapped(g, f.output().getHoverName(), x + 23, y + 3, w - 26, selected ? p.insetText() : p.text());
            Ui.wrapped(g, reason, x + 23, y + titleH, w - 26,
                    selected ? Ui.onDark(problem == null ? p.good() : p.warn()) : problem == null ? p.good() : p.warn());
            favoriteHits.add(new RowHit(i, x, rowY, w - 2, rowH));
            y += rowH + 5;
        }
        if (favorites.isEmpty()) y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.favorites.none"), x, y, w, p.muted());
        return y;
    }

    @Nullable
    private Component favoriteReason(ClientGuide.Favorite favorite) {
        var parsed = com.otectus.immersivebrewing.recipe.PlanKey.parse(favorite.key());
        if (parsed == null) return Component.translatable("notebook.immersive_brewing.favorite.invalid_key");
        if (!ServerCatalog.ready()) return Component.translatable("notebook.immersive_brewing.catalog.syncing");
        for (var part : parsed.parts()) {
            if (part.contextual()) return Component.translatable("notebook.immersive_brewing.favorite.contextual");
            PlanIndex.Entry entry = ServerCatalog.entry(part.source());
            if (entry == null) return invalidFavorite("notebook.immersive_brewing.favorite.recipe_removed", favorite.output());
            if (entry.status() != PlanIndex.Status.SUPPORTED && entry.status() != PlanIndex.Status.METADATA_ONLY) {
                return reportReason(entry.reason()).copy().append(" ").append(replacementMessage(favorite.output()));
            }
        }
        return null;
    }

    private Component invalidFavorite(String reason, ItemStack output) {
        return Component.translatable(reason).copy().append(" ").append(replacementMessage(output));
    }

    private Component replacementMessage(ItemStack output) {
        boolean replacement = minecraft != null && minecraft.level != null && knownPaths().stream()
                .anyMatch(recipe -> PotionContentsService.sameCanonical(recipe.getResultItem(minecraft.level.registryAccess()), output));
        return Component.translatable(replacement ? "notebook.immersive_brewing.favorite.replacement_available"
                : "notebook.immersive_brewing.favorite.inspect_replacement");
    }

    private int journal(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        List<ClientGuide.History> history = ClientGuide.history();
        if (history.isEmpty()) return y + Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.journal.none"), x, y, w, p.muted());
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.journal.count", history.size(), PlayerGuide.MAX_HISTORY), x, y, w, p.muted()) + 6;
        for (int i = 0; i < history.size(); i++) {
            ClientGuide.History entry = history.get(i);
            boolean selected = i == selectedHistory;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("notebook.immersive_brewing.journal.entry", entry.servings(), entry.output().getHoverName(), entry.quality()));
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(new java.util.Date(entry.timestamp()));
            lines.add(Component.translatable("notebook.immersive_brewing.journal.when", when));
            lines.add(Component.translatable("notebook.immersive_brewing.journal.metrics", Math.round(entry.thermal() * 100),
                    Math.round(entry.circulation() * 100), Math.round(entry.dosing() * 100)));
            if (entry.assistTime() > 1F || entry.assistBand() > 1F)
                lines.add(Component.translatable("screen.immersive_brewing.assistance_approved", entry.assistTime(), entry.assistBand()));
            int contextEnd = lines.size();
            Component observation = observation(entry);
            if (selected && observation != null) lines.add(observation);
            ClientGuide.History previous = ClientGuide.previousComparable(i);
            if (previous != null) lines.add(Component.translatable(entry.sameAssistance(previous)
                    ? "notebook.immersive_brewing.journal.compare" : "notebook.immersive_brewing.journal.compare_assistance_changed",
                    signed(entry.quality() - previous.quality()), signed(entry.control() - previous.control())));
            int rowH = 6;
            for (int n = 0; n < lines.size(); n++) rowH += Math.max(n == 0 ? 18 : 0, Ui.lines(lines.get(n), w - 26) * 10);
            if (selected) Ui.inset(g, x, y, w - 2, rowH);
            g.renderItem(entry.output(), x + 3, y + 3);
            int rowY = y, textY = y + 3;
            for (int n = 0; n < lines.size(); n++) {
                int color = n == 0 ? selected ? p.insetText() : p.text()
                        : n < contextEnd ? selected ? p.insetMuted() : p.muted() : selected ? 0xFF99C9EC : p.accent();
                textY += Math.max(n == 0 ? 18 : 0, Ui.wrapped(g, lines.get(n), x + 23, textY, w - 26, color));
            }
            historyHits.add(new RowHit(i, x, rowY, w - 2, rowH));
            y += rowH + 5;
        }
        return y;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    @Nullable
    private static Component observation(ClientGuide.History history) {
        if (history.observation().isEmpty()) return null;
        Object[] values = new Object[history.observationArgs().size()];
        for (int i = 0; i < values.length; i++) {
            String value = history.observationArgs().get(i);
            ResourceLocation id = ResourceLocation.tryParse(value);
            values[i] = value.startsWith("phase.") ? Component.translatable(value)
                    : id != null && ForgeRegistries.ITEMS.containsKey(id) ? new ItemStack(ForgeRegistries.ITEMS.getValue(id)).getHoverName()
                    : Component.literal(value);
        }
        return Component.translatable(history.observation(), values);
    }
}
