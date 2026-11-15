package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.recipe.QuickFinishing;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Direct batch setup at the Kettle (handoff 6.2): the server reads what is staged (the bottle group on the rail, the
 * tray, fuel, paddle) and offers the supported routes on the book stand's page, so an ordinary batch needs no planner.
 *
 * <ul>
 * <li><b>Preview is pure.</b> It reads through the Kettle's own catalog, plan resolution and availability checks (the
 * same {@code check} the optional planner shows) and changes nothing.</li>
 * <li><b>Bounded.</b> Candidates come from the cached tray-limited catalog ({@link PlanIndex#plansUsing}) and the
 * contextual handlers; at most {@link #MAX_EVALUATED} are checked, at most {@link #MAX_PAGES} offered. Recomputing is
 * debounced and happens only when the staged contents, the data generation or a pinned route changed.</li>
 * <li><b>Never a silent choice.</b> One route that uses the most of what is staged is shown as the batch; several equal
 * routes are offered as pages, in a stable order by output name, and the shown page is what a deliberate action
 * accepts. A route chosen in the optional planner, JEI or the Notebook is pinned to the page, with ghosts at the real
 * holders for whatever it still lacks.</li>
 * <li><b>Accepting is exact.</b> The client names the page's {@link Preview#revision()}; if anything changed since, the
 * accept is refused with the reason and the page refreshes. Nothing is consumed until the Kettle's own reservation
 * transaction re-validates everything.</li>
 * </ul>
 */
public final class KettleSetupService {
    public static final int MAX_PAGES = 4;
    public static final int MAX_EVALUATED = 48;
    public static final int MAX_GHOSTS = 8;
    /** Ticks between recomputations while the staged contents keep changing. */
    public static final int DEBOUNCE = 5;
    /** How long a route chosen in the planner stays pinned to the page, in ticks (five minutes). */
    public static final int PIN_TICKS = 6000;

    public enum Status {
        /** A batch already owns the Kettle. */
        BUSY,
        /** Nothing staged at all. */
        EMPTY,
        /** Ingredients or a pinned route, but no bottles on the rail. */
        NEED_BOTTLES,
        /** Finished outputs are still on the rail. */
        COLLECT,
        /** The rail holds something no batch can start from (empty or mixed bottles). */
        PROBLEM,
        /** Bottles, but nothing on the tray. */
        NEED_INGREDIENTS,
        /** No supported route uses what is staged. */
        UNSUPPORTED,
        /** The best (or pinned) route lacks something; ghosts show what and where. */
        MISSING,
        /** Exactly one route: the page shows the batch a tipped bottle starts. */
        READY,
        /** Several equal routes: the player turns between them; the shown page is the one a tipped bottle starts. */
        CHOOSE
    }

    /** A missing supply shown where it belongs: a reagent on the tray, fuel at the burner, bottles on the rail. */
    public record Ghost(int part, ItemStack item) {}

    public record Route(BrewPlan plan, BrewersKettleBlockEntity.Check check, int coverage, boolean finishing, boolean pinned) {
        public boolean ready() {
            return check.missing() == null;
        }
    }

    public record Preview(long revision, Status status, List<Route> routes, int page, boolean more, @Nullable Component problem,
                          List<Ghost> ghosts, int servings, ItemStack input) {
        @Nullable
        public Route shown() {
            return routes.isEmpty() ? null : routes.get(Math.max(0, Math.min(page, routes.size() - 1)));
        }

        /** Whether a deliberate action may start the shown page now. */
        public boolean acceptable() {
            Route shown = shown();
            return (status == Status.READY || status == Status.CHOOSE) && shown != null && shown.ready();
        }
    }

    private record Pin(PlanKey key, ItemStack input, int servings, UUID by, long until) {}

    private final BrewersKettleBlockEntity kettle;
    private Preview preview;
    private long revision;
    private long lastShape;
    private int page;
    private boolean dirty = true;
    private int cooldown;
    @Nullable
    private Pin pin;

    KettleSetupService(BrewersKettleBlockEntity kettle) {
        this.kettle = kettle;
        // A fresh start per incarnation: a page revision seen before a restart never matches after it.
        this.revision = (UUID.randomUUID().getMostSignificantBits() & 0x3FFF_FFFFL) << 8;
        this.preview = new Preview(revision, Status.EMPTY, List.of(), 0, false, null, List.of(), 0, ItemStack.EMPTY);
    }

    public Preview preview() {
        return preview;
    }

    /** Something staged changed (inventory, fuel, a reload): the page is recomputed within {@link #DEBOUNCE} ticks. */
    public void markDirty() {
        dirty = true;
    }

    /** A route chosen in the optional planner, JEI or the Notebook: shown on the page until it starts or is replaced. */
    public void pin(PlanKey key, ItemStack input, int servings, UUID player, long now) {
        pin = new Pin(key, input.copyWithCount(1), Math.max(1, Math.min(6, servings)), player, now + PIN_TICKS);
        page = 0;
        dirty = true;
        cooldown = 0;
    }

    public void unpin() {
        if (pin == null) return;
        pin = null;
        dirty = true;
    }

    /** Server tick: recomputes a dirty page at a bounded rate. Returns whether what the page shows changed. */
    public boolean tick(long now) {
        if (pin != null && now > pin.until()) {
            pin = null;
            dirty = true;
        }
        if (cooldown > 0) cooldown--;
        if (!dirty || cooldown > 0) return false;
        return refresh();
    }

    /** Recomputes now (an accept re-validates against the freshest page). Returns whether the shown page changed. */
    public boolean refresh() {
        dirty = false;
        cooldown = DEBOUNCE;
        Preview next = compute();
        long shape = shape(next);
        if (shape == lastShape && next.status() == preview.status()) {
            // Same page: keep its revision, so a player who reads and then acts is never refused for nothing.
            preview = withRevision(next, revision);
            return false;
        }
        lastShape = shape;
        revision++;
        preview = withRevision(next, revision);
        return true;
    }

    /** Turns the page (handoff 3.3). Refused when that page does not exist. */
    public boolean turnTo(int target) {
        if (preview.routes().size() < 2 || target < 0 || target >= preview.routes().size() || target == page) return false;
        page = target;
        refresh();
        return true;
    }

    private static Preview withRevision(Preview p, long revision) {
        return new Preview(revision, p.status(), p.routes(), p.page(), p.more(), p.problem(), p.ghosts(), p.servings(), p.input());
    }

    // ---- the preview ----

    private Preview compute() {
        if (kettle.ledger() != null) return preview(Status.BUSY, List.of(), false, null, List.of(), 0, ItemStack.EMPTY);
        if (kettle.getLevel() == null || kettle.getLevel().getServer() == null) return preview(Status.EMPTY, List.of(), false, null, List.of(), 0, ItemStack.EMPTY);
        BrewersKettleBlockEntity.Rack rack = kettle.rackFor(false);
        BrewersKettleBlockEntity.Rack finishRack = kettle.rackFor(true);
        List<ItemStack> tray = kettle.trayItems();
        Set<Item> trayTypes = new LinkedHashSet<>();
        for (ItemStack s : tray) trayTypes.add(s.getItem());
        PlanIndex index = PlanIndex.get(kettle.getLevel().getServer().getRecipeManager());

        Route pinned = pinnedRoute(index, rack.problem() == null ? rack : finishRack);
        if (rack.problem() != null && finishRack.problem() != null) {
            List<Ghost> ghosts = new ArrayList<>();
            boolean noBottles = noBottles();
            if (noBottles && pin != null) {
                ItemStack bottle = pin.input().copyWithCount(1);
                for (int i = 0, shown = 0; i < BrewersKettleBlockEntity.RACK_SIZE && shown < pin.servings(); i++) {
                    if (!kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i).isEmpty()) continue;
                    ghosts.add(new Ghost(WorkshopParts.K_RACK + i, bottle));
                    shown++;
                }
            }
            if (noBottles) {
                Status s = tray.isEmpty() && pin == null ? Status.EMPTY : Status.NEED_BOTTLES;
                return preview(s, pinned == null ? List.of() : List.of(pinned), false, rack.problem(), ghosts, 0, ItemStack.EMPTY);
            }
            Status s = finished() ? Status.COLLECT : Status.PROBLEM;
            return preview(s, List.of(), false, rack.problem(), List.of(), 0, ItemStack.EMPTY);
        }
        boolean finishingOnly = rack.problem() != null;
        BrewersKettleBlockEntity.Rack usable = finishingOnly ? finishRack : rack;
        if (pinned != null) {
            List<Ghost> ghosts = pinned.ready() ? List.of() : ghosts(pinned, usable);
            Status s = pinned.ready() ? Status.READY : Status.MISSING;
            return preview(s, List.of(pinned), false, pinned.check().missing(), ghosts, usable.servings(), usable.input());
        }
        if (trayTypes.isEmpty()) {
            return preview(Status.NEED_INGREDIENTS, List.of(), false, null, List.of(), usable.servings(), usable.input());
        }

        // Candidates: catalog routes from this input that only use what the tray holds (cached), then contextual ones.
        boolean quick = ServerConfig.get(ServerConfig.QUICK_FINISHING);
        List<BrewPlan> candidates = new ArrayList<>();
        Set<PlanKey> seen = new java.util.HashSet<>();
        for (BrewPlan plan : index.plansUsing(usable.input(), trayTypes)) if (seen.add(plan.key())) candidates.add(plan);
        for (BrewPlan plan : index.contextualPlans(usable.input(), tray, candidates)) if (seen.add(plan.key()) && usesOnly(plan, trayTypes)) candidates.add(plan);
        if (finishingOnly) candidates.removeIf(plan -> !quick || !QuickFinishing.eligible(plan));
        if (candidates.isEmpty()) {
            return preview(Status.UNSUPPORTED, List.of(), false, Component.translatable("workshop.immersive_brewing.setup.unsupported"),
                    List.of(), usable.servings(), usable.input());
        }
        // Rank: the routes that use the most of what was staged first (a staged ingredient was put there for a reason),
        // then the longer route; within that, a stable order by output name (never registry order).
        candidates.sort(Comparator.comparingInt((BrewPlan p) -> -coverage(p, trayTypes)).thenComparingInt(p -> -p.steps().size())
                .thenComparing(p -> p.output().getHoverName().getString()).thenComparing(p -> p.key().serialize()));
        List<Route> evaluated = new ArrayList<>();
        for (BrewPlan plan : candidates) {
            if (evaluated.size() >= MAX_EVALUATED) break;
            boolean finishing = quick && QuickFinishing.eligible(plan) && (finishingOnly || kettle.finishingPreferred(plan));
            evaluated.add(new Route(plan, kettle.setupCheck(plan, usable.servings(), !finishing), coverage(plan, trayTypes), finishing, false));
        }
        List<Route> ready = evaluated.stream().filter(Route::ready).toList();
        if (!ready.isEmpty()) {
            int best = ready.get(0).coverage();
            int steps = ready.get(0).plan().steps().size();
            List<Route> top = new ArrayList<>();
            for (Route r : ready) if (r.coverage() == best && r.plan().steps().size() == steps) top.add(r);
            boolean more = top.size() > MAX_PAGES || ready.size() > top.size() || candidates.size() > evaluated.size();
            List<Route> pages = List.copyOf(top.subList(0, Math.min(MAX_PAGES, top.size())));
            if (page >= pages.size()) page = 0;
            return preview(pages.size() == 1 ? Status.READY : Status.CHOOSE, pages, more, null, List.of(), usable.servings(), usable.input());
        }
        Route best = evaluated.get(0);
        return preview(Status.MISSING, List.of(best), evaluated.size() > 1, best.check().missing(), ghosts(best, usable), usable.servings(), usable.input());
    }

    private Preview preview(Status status, List<Route> routes, boolean more, @Nullable Component problem, List<Ghost> ghosts, int servings, ItemStack input) {
        int shownPage = routes.isEmpty() ? 0 : Math.min(page, routes.size() - 1);
        return new Preview(revision, status, routes, shownPage, more, problem, List.copyOf(ghosts.subList(0, Math.min(MAX_GHOSTS, ghosts.size()))),
                servings, input.isEmpty() ? ItemStack.EMPTY : input.copyWithCount(1));
    }

    @Nullable
    private Route pinnedRoute(PlanIndex index, BrewersKettleBlockEntity.Rack rack) {
        if (pin == null) return null;
        ItemStack input = rack.input().isEmpty() ? pin.input() : rack.input();
        BrewPlan plan = index.resolve(pin.key(), input, kettle.trayItems());
        if (plan == null) return null;
        int servings = rack.servings() > 0 ? rack.servings() : pin.servings();
        boolean finishing = ServerConfig.get(ServerConfig.QUICK_FINISHING) && QuickFinishing.eligible(plan) && kettle.finishingPreferred(plan);
        BrewersKettleBlockEntity.Check check = kettle.setupCheck(plan, servings, !finishing);
        if (rack.input().isEmpty()) {
            check = new BrewersKettleBlockEntity.Check(check.costs(), check.fuelNeeded(), check.fuelAvailable(), check.remainders(),
                    Component.translatable("workshop.immersive_brewing.setup.place_bottles", pin.servings(), input.getHoverName()));
        }
        return new Route(plan, check, 0, finishing, true);
    }

    /** Where each missing supply of a route belongs. */
    private List<Ghost> ghosts(Route route, BrewersKettleBlockEntity.Rack rack) {
        List<Ghost> out = new ArrayList<>();
        BrewersKettleBlockEntity.Check check = route.check();
        boolean[] used = new boolean[BrewersKettleBlockEntity.TRAY_SIZE];
        for (PlanListS2C.Cost cost : check.costs()) {
            int short_ = cost.needed() - cost.loaded() - cost.kitGroups();
            if (short_ <= 0) continue;
            int slot = -1;
            for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE && slot < 0; i++) {
                ItemStack s = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + i);
                if (!used[i] && !s.isEmpty() && s.is(cost.item())) slot = i;
            }
            for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE && slot < 0; i++) {
                if (!used[i] && kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + i).isEmpty()) slot = i;
            }
            if (slot < 0) continue;
            used[slot] = true;
            out.add(new Ghost(WorkshopParts.K_TRAY + slot, new ItemStack(cost.item(), Math.min(64, short_))));
        }
        if (check.fuelAvailable() < check.fuelNeeded()) {
            int per = Math.max(1, ServerConfig.fuelChargesPerBlazePowder());
            int powder = (check.fuelNeeded() - check.fuelAvailable() + per - 1) / per;
            out.add(new Ghost(WorkshopParts.K_FUEL, new ItemStack(Items.BLAZE_POWDER, Math.min(64, powder))));
        }
        if (!route.finishing() && !kettle.hasPaddle()) out.add(new Ghost(WorkshopParts.K_PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get())));
        if (rack.servings() > route.plan().servingLimit()) {
            // Too many bottles for this route: nothing to add, the reason says how many it takes.
            return out;
        }
        return out;
    }

    private boolean noBottles() {
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) if (!kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i).isEmpty()) return false;
        return true;
    }

    private boolean finished() {
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            if (kettle.finished[BrewersKettleBlockEntity.RACK + i] && !kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i).isEmpty()) return true;
        }
        return false;
    }

    private static boolean usesOnly(BrewPlan plan, Set<Item> types) {
        for (BrewPlan.Step step : plan.steps()) if (!types.contains(step.reagent())) return false;
        return true;
    }

    /** How many different staged ingredients the route uses. */
    static int coverage(BrewPlan plan, Set<Item> types) {
        Set<Item> used = new java.util.HashSet<>();
        for (BrewPlan.Step step : plan.steps()) if (types.contains(step.reagent())) used.add(step.reagent());
        return used.size();
    }

    /** A digest of what the page shows: a change of any of it is a new revision. */
    private long shape(Preview p) {
        long h = p.status().ordinal();
        h = 31 * h + p.page();
        h = 31 * h + p.servings();
        h = 31 * h + (p.more() ? 1 : 0);
        h = 31 * h + (p.problem() == null ? 0 : p.problem().getString().hashCode());
        for (Route r : p.routes()) {
            h = 31 * h + r.plan().key().serialize().hashCode();
            h = 31 * h + (r.ready() ? 1 : 0);
            h = 31 * h + r.check().fuelNeeded();
            h = 31 * h + r.check().fuelAvailable();
            h = 31 * h + (r.finishing() ? 1 : 0);
            for (ItemStack rem : r.check().remainders()) h = 31 * h + Objects.hashCode(ForgeRegistries.ITEMS.getKey(rem.getItem())) + rem.getCount();
        }
        for (Ghost g : p.ghosts()) h = 31 * h + g.part() * 131 + g.item().getCount() + Objects.hashCode(ForgeRegistries.ITEMS.getKey(g.item().getItem()));
        h = 31 * h + BrewingDefinitions.server().generation();
        return h;
    }

    // ---- what tracking clients see (compact, nothing private) ----

    public void write(CompoundTag t) {
        Preview p = preview;
        CompoundTag s = new CompoundTag();
        s.putLong("rev", p.revision());
        s.putByte("status", (byte) p.status().ordinal());
        s.putByte("page", (byte) p.page());
        s.putByte("pages", (byte) p.routes().size());
        s.putBoolean("more", p.more());
        s.putByte("servings", (byte) p.servings());
        if (p.problem() != null) {
            String json = Component.Serializer.toJson(p.problem());
            if (json.length() <= 1024) s.putString("problem", json);
        }
        Route shown = p.shown();
        if (shown != null) {
            CompoundTag out = LedgerCodec.saveStack(shown.plan().output().copyWithCount(1));
            out.remove("FullCount");
            s.put("output", out);
            ListTag steps = new ListTag();
            byte[] counts = new byte[Math.min(8, shown.plan().steps().size())];
            List<BrewPlan.Cost> costs = shown.plan().costs(Math.max(1, p.servings()));
            for (int i = 0; i < counts.length; i++) {
                BrewPlan.Step step = shown.plan().steps().get(i);
                steps.add(StringTag.valueOf(String.valueOf(ForgeRegistries.ITEMS.getKey(step.reagent()))));
                int need = step.batch().reagentCost(Math.max(1, p.servings()));
                counts[i] = (byte) Math.min(64, need);
            }
            s.put("steps", steps);
            s.put("counts", new ByteArrayTag(counts));
            s.putShort("fuel", (short) Math.min(Short.MAX_VALUE, shown.check().fuelNeeded()));
            s.putShort("fuelHave", (short) Math.min(Short.MAX_VALUE, shown.check().fuelAvailable()));
            s.putBoolean("ready", shown.ready());
            s.putBoolean("finishing", shown.finishing());
            s.putBoolean("pinned", shown.pinned());
            ListTag rem = new ListTag();
            for (ItemStack r : shown.check().remainders()) {
                if (rem.size() >= 3) break;
                CompoundTag rt = LedgerCodec.saveStack(r);
                rt.remove("FullCount");
                rem.add(rt);
            }
            s.put("remainders", rem);
            if (costs.size() != counts.length) s.putBoolean("merged", true);
        }
        ListTag ghosts = new ListTag();
        for (Ghost g : p.ghosts()) {
            CompoundTag gt = new CompoundTag();
            gt.putByte("part", (byte) g.part());
            gt.putString("item", String.valueOf(ForgeRegistries.ITEMS.getKey(g.item().getItem())));
            gt.putByte("count", (byte) Math.min(64, g.item().getCount()));
            ghosts.add(gt);
        }
        s.put("ghosts", ghosts);
        t.put("setup", s);
    }
}
