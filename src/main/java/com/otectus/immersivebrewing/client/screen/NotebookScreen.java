package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.config.ClientConfig;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanStep;
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
    private enum Page { START, STATIONS, CONTROLS, QUALITY, RECOVERY, RECIPES, REAGENTS, FAVORITES, LAST, PRACTICE }

    /** The Kettle's own crafting recipe; a datapack may replace it under this id, or remove it. */
    private static final ResourceLocation KETTLE_RECIPE = ImmersiveBrewing.id("brewers_kettle");

    /**
     * The paths the recipe page lists: infusion recipes validated against this client's brewing registry exactly as JEI
     * lists them (a plan for another mod's potion can be unsupported, for example when that mod's config changed its
     * ingredient), sorted by result. Rebuilt only when the recipes, the brewing data or the language change.
     */
    private static List<InfusionRecipe> knownPaths = List.of();
    private static WeakReference<RecipeManager> knownFor = new WeakReference<>(null);
    @Nullable
    private static BrewingDefinitions knownDefs;
    @Nullable
    private static String knownLanguage;

    private Page page = Page.START;
    private final Ui.ScrollArea content = new Ui.ScrollArea();
    private int mouseX, mouseY;
    private EditBox search;
    private int px, py, pw, ph, tabW;
    @Nullable
    private CraftingRecipe kettleRecipe;
    /** The item under the mouse in a crafting grid this frame, for its tooltip; the visible text area bounds it. */
    private ItemStack hovered = ItemStack.EMPTY;
    private int clipTop, clipBottom;

    public NotebookScreen() {
        super(Component.translatable("item.immersive_brewing.brewers_notebook"));
    }

    @Override
    protected void init() {
        pw = Math.min(width - 12, 420);
        ph = Math.min(height - 12, 250);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int widestTab = 0;
        for (Page tab : Page.values()) widestTab = Math.max(widestTab,
                font.width(Component.translatable("notebook.immersive_brewing.tab." + tab.name().toLowerCase(Locale.ROOT))));
        tabW = Math.min(pw - 150, Math.max(92, widestTab + 16));
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, px + tabW + 4, py + 6, pw - tabW - 10, 14, Component.translatable("screen.immersive_brewing.search"));
        Ui.hint(search, Component.translatable("screen.immersive_brewing.search_hint"));
        search.setValue(query);
        search.setResponder(s -> content.reset());
        kettleRecipe = findKettleRecipe();
        rebuild();
    }

    /** The Kettle's crafting recipe as this world loaded it: its own id first, else any crafting recipe that makes one. */
    @Nullable
    private CraftingRecipe findKettleRecipe() {
        if (minecraft == null || minecraft.level == null) return null;
        var recipes = minecraft.level.getRecipeManager();
        var access = minecraft.level.registryAccess();
        if (recipes.byKey(KETTLE_RECIPE).orElse(null) instanceof CraftingRecipe own && own.getResultItem(access).is(ModItems.BREWERS_KETTLE.get())) {
            return own;
        }
        for (CraftingRecipe r : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (r.getResultItem(access).is(ModItems.BREWERS_KETTLE.get())) return r;
        }
        return null;
    }

    private void rebuild() {
        clearWidgets();
        Page[] pages = Page.values();
        int th = Math.max(14, Math.min(18, (ph - 12) / pages.length));
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            Button b = Button.builder(Component.translatable("notebook.immersive_brewing.tab." + p.name().toLowerCase(Locale.ROOT)), btn -> {
                page = p;
                content.reset();
                rebuild();
            }).bounds(px + 4, py + 6 + i * th, tabW - 8, th - 2).build();
            b.active = p != page;
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(b.getMessage()));
            addRenderableWidget(b);
        }
        if (page == Page.RECIPES || page == Page.REAGENTS) addRenderableWidget(search);
        if (page == Page.PRACTICE) {
            int y = py + ph - 78;
            for (SessionKind kind : SessionKind.values()) {
                addRenderableWidget(Button.builder(Component.translatable("notebook.immersive_brewing.practice." + kind.name().toLowerCase(Locale.ROOT)),
                        b -> ClientPacketHandler.practice(kind)).bounds(px + tabW + 6, y, pw - tabW - 14, 20).build());
                y += 24;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return content.wheel(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
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
        Ui.panel(g, px, py, pw, ph);
        Ui.Palette p = Ui.palette();
        int x = px + tabW + 6;
        int w = pw - tabW - 14;
        g.fill(px + tabW - 2, py + 6, px + tabW - 1, py + ph - 6, p.panelDark());
        g.fill(px + tabW - 1, py + 6, px + tabW, py + ph - 6, p.panelLight());
        int top = (page == Page.RECIPES || page == Page.REAGENTS) ? py + 26 : py + 8;
        clipTop = top;
        clipBottom = py + ph - (page == Page.PRACTICE ? 84 : 6);
        hovered = ItemStack.EMPTY;
        content.bounds(x, top, w, clipBottom - top);
        w = content.width();
        content.begin(g);
        int y = content.top();
        switch (page) {
            case RECIPES -> y = recipes(g, x, y, w);
            case REAGENTS -> y = reagents(g, x, y, w);
            case FAVORITES -> y = favorites(g, x, y, w);
            case LAST -> y = last(g, x, y, w);
            default -> y = text(g, page.name().toLowerCase(Locale.ROOT), x, y, w, mouseX, mouseY);
        }
        content.end(g, y);
        super.render(g, mouseX, mouseY, partialTick);
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private int text(GuiGraphics g, String key, int x, int y, int w, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing." + key + ".title").withStyle(s -> s.withBold(true)), x, y, w, p.text()) + 4;
        for (int i = 1; i <= 12; i++) {
            String k = "notebook.immersive_brewing." + key + "." + i;
            Component c = Component.translatable(k);
            if (c.getString().equals(k)) break;
            y += Ui.wrapped(g, c, x, y, w, p.text()) + 4;
            // The Kettle's recipe right after the line that asks for a Kettle.
            if (key.equals("start") && i == 1) y = kettleGrid(g, x, y, w, mouseX, mouseY);
        }
        if (key.equals("start")) {
            y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.mode." + ServerConfig.brewingMode().name().toLowerCase(Locale.ROOT)),
                    x, y, w, p.accent()) + 4;
        }
        return y;
    }

    /**
     * The Kettle's recipe as a crafting grid, from the loaded recipe so a datapack's change shows (playtest: the recipe
     * written out as a list of materials read like more things a brewer needs). Hovering an item names it; an ingredient
     * that takes a tag shows its members in turn.
     */
    private int kettleGrid(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        Ui.Palette p = Ui.palette();
        if (kettleRecipe == null || minecraft == null || minecraft.level == null) {
            return y + Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.start.no_recipe"), x, y, w, p.muted()) + 4;
        }
        int gx = x + 2;
        for (int i = 0; i < 9; i++) Ui.slot(g, gx + i % 3 * 18, y + i / 3 * 18);
        List<Ingredient> ingredients = kettleRecipe.getIngredients();
        int columns = kettleRecipe instanceof IShapedRecipe<?> shaped ? shaped.getRecipeWidth() : 3;
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
        int caption = Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.start.recipe"), cx, y + 1, x + w - cx, p.text());
        int ry = y + Math.max(14, caption + 4);
        int ay = ry + 13;
        g.fill(cx, ay - 1, cx + 12, ay + 1, p.muted());
        for (int i = 0; i < 5; i++) g.fill(cx + 12 + i, ay - 5 + i, cx + 13 + i, ay + 5 - i, p.muted());
        ItemStack result = kettleRecipe.getResultItem(minecraft.level.registryAccess());
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
        List<InfusionRecipe> list = knownPaths();
        int servings = ClientGuide.preferredServings();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.recipes.intro", servings), x, y, w, p.muted()) + 4;
        for (InfusionRecipe r : list) {
            ItemStack out = r.getResultItem(minecraft.level.registryAccess());
            ItemStack in = r.input().stack(null);
            String name = out.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !name.contains(q) && r.steps().stream().noneMatch(s -> stepName(s).toLowerCase(Locale.ROOT).contains(q))) continue;
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
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.recipes.stand"), x, y, w, p.muted());
        return y;
    }

    private List<InfusionRecipe> knownPaths() {
        RecipeManager recipes = minecraft.level.getRecipeManager();
        BrewingDefinitions defs = BrewingDefinitions.client();
        String language = minecraft.getLanguageManager().getSelected();
        if (knownFor.get() != recipes || knownDefs != defs || !language.equals(knownLanguage)) {
            PlanIndex index = PlanIndex.forClient(recipes, defs);
            List<InfusionRecipe> list = new ArrayList<>();
            for (InfusionRecipe r : recipes.getAllRecipesFor(ModRecipeTypes.INFUSION.get())) {
                PlanIndex.Entry entry = index.entry(r.getId());
                if (entry != null && (entry.status() == PlanIndex.Status.SUPPORTED || entry.status() == PlanIndex.Status.METADATA_ONLY)) list.add(r);
            }
            var access = minecraft.level.registryAccess();
            list.sort(Comparator.comparing(r -> r.getResultItem(access).getHoverName().getString()));
            knownPaths = List.copyOf(list);
            knownFor = new WeakReference<>(recipes);
            knownDefs = defs;
            knownLanguage = language;
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
        for (ReagentProfile r : BrewingDefinitions.client().reagentProfiles()) {
            for (var id : r.selector().ids()) {
                var item = ForgeRegistries.ITEMS.getValue(id);
                if (item == null) continue;
                ItemStack stack = new ItemStack(item);
                if (!q.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) continue;
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
        return y;
    }

    private int favorites(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.favorites.intro"), x, y, w, p.muted()) + 4;
        for (ClientGuide.Favorite f : ClientGuide.favorites()) {
            g.renderItem(f.output(), x, y);
            hover(f.output(), x, y, 16, mouseX, mouseY);
            y += Math.max(18, Ui.wrapped(g, f.output().getHoverName(), x + 18, y + 4, w - 18, p.text()) + 6);
        }
        return y;
    }

    private int last(GuiGraphics g, int x, int y, int w) {
        Ui.Palette p = Ui.palette();
        CompoundTag last = ClientGuide.lastResult();
        if (last.isEmpty()) return y + Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.last.none"), x, y, w, p.muted());
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.last.scores", last.getInt("Q"), last.getInt("E"), last.getInt("C")), x, y, w, p.text()) + 2;
        y += Ui.wrapped(g, Component.translatable("notebook.immersive_brewing.last.terms", Math.round(last.getDouble("thermal") * 100),
                Math.round(last.getDouble("circulation") * 100), Math.round(last.getDouble("dosing") * 100)), x, y, w, p.muted()) + 4;
        if (last.contains("tip")) {
            var args = last.getList("tipArgs", 8);
            Object[] values = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                String a = args.getString(i);
                ResourceLocation id = ResourceLocation.tryParse(a);
                values[i] = a.startsWith("phase.") ? Component.translatable(a)
                        : id != null && ForgeRegistries.ITEMS.containsKey(id) ? new ItemStack(ForgeRegistries.ITEMS.getValue(id)).getHoverName()
                        : Component.literal(a);
            }
            y += Ui.wrapped(g, Component.translatable(last.getString("tip"), values), x, y, w, p.accent());
        }
        return y;
    }
}
