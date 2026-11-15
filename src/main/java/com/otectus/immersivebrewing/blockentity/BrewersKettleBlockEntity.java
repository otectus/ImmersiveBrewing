package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.advancement.Milestone;
import com.otectus.immersivebrewing.advancement.ModAdvancements;
import com.otectus.immersivebrewing.api.BrewingAdapter;
import com.otectus.immersivebrewing.api.event.BrewPlanEvent;
import com.otectus.immersivebrewing.api.event.BrewResultEvent;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.batch.SlotStack;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewProfile;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.minigame.brewing.BrewScore;
import com.otectus.immersivebrewing.minigame.brewing.KettleParams;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.minigame.brewing.Signature;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.Grade;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.potion.QualityInheritance;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.recipe.adapter.AdapterRegistry;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModSounds;
import com.otectus.immersivebrewing.registry.ModTags;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.workshop.FinishingProcess;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import com.otectus.immersivebrewing.workshop.WorkshopProfile;
import com.otectus.immersivebrewing.workshop.WorkshopProfiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.util.Mth;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Brewer's Kettle (spec 4, 5, 7): a six-bottle rack, an ingredient tray, an enclosed blaze burner with a
 * persistent charge counter, a paddle slot and a by-product shelf.
 *
 * <p>Starting a brew re-resolves the player's plan key against the actual bottles, checks reagents (raw or prepared
 * kits), fuel, paddle and by-product room, then moves exactly what the plan costs into a {@link JobLedger}. The commit
 * replays every step through its adapter for every serving, compares each result with the snapshot taken at start, and
 * only then turns the bottles into the graded outputs in place. Bottling consumes no extra bottles and never multiplies
 * a batch (spec 5.1).
 */
public class BrewersKettleBlockEntity extends StationBlockEntity {
    public static final int RACK = 0;
    public static final int RACK_SIZE = 6;
    public static final int TRAY = 6;
    public static final int TRAY_SIZE = 6;
    public static final int FUEL = 12;
    public static final int PADDLE = 13;
    public static final int BYPRODUCT = 14;
    public static final int BYPRODUCT_SIZE = 3;
    public static final int SLOTS = 17;
    /** Water for one bottle (spec 5.1): one 1000 mB bucket fills four. */
    public static final int MB_PER_BOTTLE = 250;

    private LazyOptional<IFluidHandler> fluid = LazyOptional.of(WaterHandler::new);
    private long lastLineage;

    // ---- hands-on workshop (server transient; the ledger's process payload is authoritative when saved) ----
    @Nullable
    private KettleProcess process;
    @Nullable
    private FinishingProcess finishingProcess;
    private final ToolTrack paddleTrack = new ToolTrack(WorkshopParts.KETTLE_HX, WorkshopParts.KETTLE_HZ);
    private int pourQueue;
    private int fillQueue;
    private int queueCooldown;
    @Nullable
    private java.util.UUID lastActor;
    /** Direct batch setup: the recipe page on the book stand (transient; recomputed from the staged contents). */
    final KettleSetupService setup = new KettleSetupService(this);
    /** Where each carried bottle or dose cup is held over the apparatus (transient; normalised block coordinates). */
    private final Map<Integer, float[]> carryPose = new HashMap<>();
    /** Ticks between two bottles of a poured batch: long enough for each to be lifted, tipped and returned in view. */
    public static final int POUR_PACE = 14;
    /** Ticks between two bottles of a filled batch (handoff 13: a six-bottle fill in about five seconds). */
    public static final int FILL_PACE = 16;
    /** Ticks before a batch's first pour or fill commits (the time its bottle or the carriage takes to get there). */
    public static final int POUR_LEAD = 11, FILL_LEAD = 5;
    /** The game time the next queued pour or fill commits (presentation: clients time the bottle's lift to it). */
    private long queueAt;

    public BrewersKettleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BREWERS_KETTLE.get(), pos, state, SLOTS);
    }

    @Override
    public ResourceLocation stationId() {
        return ImmersiveBrewing.id("brewers_kettle");
    }

    // ---- slot rules ----

    public static boolean isRack(int slot) {
        return slot >= RACK && slot < RACK + RACK_SIZE;
    }

    public static boolean isTray(int slot) {
        return slot >= TRAY && slot < TRAY + TRAY_SIZE;
    }

    public static boolean isByproduct(int slot) {
        return slot >= BYPRODUCT && slot < BYPRODUCT + BYPRODUCT_SIZE;
    }

    @Override
    protected int slotLimit(int slot) {
        if (isRack(slot) || slot == PADDLE) return 1;
        return 64;
    }

    /** A potion container a batch can start from: anything the brewing registry accepts as an input, or an empty bottle to fill. */
    public static boolean isBottle(ItemStack stack) {
        if (stack.is(Items.GLASS_BOTTLE)) return true;
        if (stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        return stack.getItem() instanceof PotionItem || BrewingRecipeRegistry.isValidInput(stack.copyWithCount(1));
    }

    @Override
    protected boolean canPlace(int slot, ItemStack stack) {
        if (stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        if (isRack(slot)) return ledger == null && isBottle(stack);
        if (isTray(slot)) return ledger == null && !(stack.getItem() instanceof PotionItem) && !stack.is(ModTags.BREWING_PADDLES);
        if (slot == FUEL) return stack.is(ModTags.BURNER_FUEL);
        if (slot == PADDLE) return stack.is(ModTags.BREWING_PADDLES) && !paddleInUse();
        return false;
    }

    @Override
    public boolean canTake(int slot) {
        return slot != PADDLE || !paddleInUse();
    }

    private boolean paddleInUse() {
        if (level != null && level.isClientSide) {
            return view.controller() != null || view.state() == JobState.RESERVED || view.state() == JobState.RUNNING;
        }
        // A resumed count-in still has a SEALED ledger, but already owns the paddle through its controller lock. A running
        // hands-on batch reserves its paddle too (a sealed one allows maintenance, as before).
        return ledger != null && (ledger.state() == JobState.RESERVED || ledger.state() == JobState.RUNNING
                || SessionManager.stationBusy(this));
    }

    @Override
    protected boolean automationInsert(@Nullable Direction side, int slot, ItemStack stack) {
        if (!canPlace(slot, stack)) return false;
        if (side == Direction.DOWN) return false;
        if (side != null && side.getAxis().isHorizontal()) return slot == FUEL;
        return isRack(slot) || isTray(slot);
    }

    @Override
    protected boolean automationExtract(@Nullable Direction side, int slot) {
        if (side != null && side != Direction.DOWN) return false;
        if (isByproduct(slot)) return true;
        return isRack(slot) && finished[slot] && (ledger == null || !reservedRackSlot(slot - RACK));
    }

    @Override
    protected boolean isOutputSlot(int slot) {
        return isRack(slot);
    }

    @Override
    protected boolean loaded() {
        for (int i = 0; i < RACK_SIZE; i++) if (!inventory.getStackInSlot(RACK + i).isEmpty()) return true;
        return false;
    }

    @Override
    protected boolean emptyForRecovery() {
        for (int i = 0; i < RACK_SIZE; i++) if (!inventory.getStackInSlot(RACK + i).isEmpty()) return false;
        return true;
    }

    // ---- rack and tray ----

    /** The rack's batch: one canonical input, its serving count, or why there is none. */
    public record Rack(ItemStack input, int servings, @Nullable Component problem) {}

    public Rack rack() {
        return rack(false);
    }

    /** The rack's batch for the setup service ({@code finishedAllowed}: finished bottles may be finished again in place). */
    Rack rackFor(boolean finishedAllowed) {
        return rack(finishedAllowed);
    }

    /** The recipe page on the book stand (handoff 6.2). */
    public KettleSetupService setup() {
        return setup;
    }

    /** The planner's availability check, for the setup service. */
    Check setupCheck(BrewPlan plan, int servings, boolean needsPaddle) {
        return check(plan, servings, new TrayStock(), needsPaddle);
    }

    /** Whether a container-only route is set out as a short fitting rather than a full reaction (handoff 8.4). */
    boolean finishingPreferred(BrewPlan plan) {
        return ServerConfig.get(ServerConfig.QUICK_FINISHING) && com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan);
    }

    private Rack rack(boolean finishedAllowed) {
        ItemStack first = ItemStack.EMPTY;
        int servings = 0;
        for (int i = 0; i < RACK_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(RACK + i);
            if (s.isEmpty()) continue;
            if (!finishedAllowed && finished[RACK + i]) return new Rack(ItemStack.EMPTY, 0, Component.translatable("plan.immersive_brewing.problem.collect"));
            if (s.is(Items.GLASS_BOTTLE)) return new Rack(ItemStack.EMPTY, 0, Component.translatable("plan.immersive_brewing.problem.empty_bottles"));
            if (first.isEmpty()) first = s;
            else if (!PotionContentsService.sameCanonical(first, s) || !sameMetadataGroup(first, s)) {
                return new Rack(ItemStack.EMPTY, 0, Component.translatable("plan.immersive_brewing.problem.mixed"));
            }
            servings += s.getCount();
        }
        if (first.isEmpty()) return new Rack(ItemStack.EMPTY, 0, Component.translatable("plan.immersive_brewing.problem.no_bottles"));
        return new Rack(first.copyWithCount(1), servings, null);
    }

    /** Graded inputs must share quality and lineage, so one inherited result describes the whole batch. */
    private static boolean sameMetadataGroup(ItemStack a, ItemStack b) {
        Optional<BrewingMetadata> ma = BrewingMetadata.read(a);
        Optional<BrewingMetadata> mb = BrewingMetadata.read(b);
        if (ma.isEmpty() || mb.isEmpty()) return ma.isEmpty() == mb.isEmpty();
        return ma.get().quality() == mb.get().quality() && ma.get().lineage() == mb.get().lineage()
                && ma.get().purifications() == mb.get().purifications() && ma.get().extraction() == mb.get().extraction();
    }

    /** What the tray holds for plan queries: raw stacks plus each kit's source reagent. */
    public List<ItemStack> trayItems() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < TRAY_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(TRAY + i);
            if (s.isEmpty()) continue;
            Optional<KitData> kit = KitData.read(s);
            out.add(kit.map(KitData::source).orElse(s));
        }
        return out;
    }

    // ---- plans ----

    /** The plans this Kettle offers for its current rack and tray, as its plan list shows them. */
    public List<BrewPlan> plans() {
        return plansFor(rack());
    }

    /**
     * The catalog from the rack's input, every catalog path the tray can pay for (the catalog alone is bounded and a large
     * modded registry can crowd those out), then contextual queries with the tray's items.
     */
    private List<BrewPlan> plansFor(Rack rack) {
        if (level == null || level.getServer() == null || rack.input().isEmpty()) return List.of();
        PlanIndex index = PlanIndex.get(level.getServer().getRecipeManager());
        List<ItemStack> tray = trayItems();
        List<BrewPlan> catalog = index.plansFrom(rack.input());
        List<BrewPlan> all = new ArrayList<>(catalog);
        Set<PlanKey> listed = new HashSet<>();
        for (BrewPlan plan : catalog) listed.add(plan.key());
        List<Item> loaded = new ArrayList<>();
        for (ItemStack s : tray) loaded.add(s.getItem());
        for (BrewPlan plan : index.plansUsing(rack.input(), loaded)) if (listed.add(plan.key())) all.add(plan);
        all.addAll(index.contextualPlans(rack.input(), tray, catalog));
        return all;
    }

    /** Availability of one plan for this rack: per-reagent supply, fuel, by-product room and paddle. */
    public record Check(List<PlanListS2C.Cost> costs, int fuelNeeded, int fuelAvailable,
                        List<ItemStack> remainders, @Nullable Component missing) {}

    /** An empty station can browse and prepare a Water batch without first loading its ingredients. */
    public Rack queryRack(int preferredServings) {
        Rack actual=rack(true);
        if (!loaded() && ledger==null) return new Rack(PotionUtils.setPotion(new ItemStack(Items.POTION),Potions.WATER),
                Math.max(1,Math.min(ServerConfig.maxBatchServings(),preferredServings)),null);
        return new Rack(actual.input(),Math.max(1,Math.min(ServerConfig.maxBatchServings(),preferredServings)),actual.problem());
    }

    @Nullable
    public PlanListS2C.Entry describePlan(ServerPlayer player,BrewPlan plan,int servings) {
        if(MinecraftForge.EVENT_BUS.post(new BrewPlanEvent(player,worldPosition,plan.key().serialize(),plan.input(),plan.output(),servings,true))) return null;
        boolean finishing = ServerConfig.get(ServerConfig.QUICK_FINISHING) && com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan);
        Check check=check(plan,servings,new TrayStock(),!finishing);
        List<PlanListS2C.Step> steps=new ArrayList<>();
        for(BrewPlan.Step s:plan.steps()) steps.add(new PlanListS2C.Step(s.reagent(),s.spec().phase(),s.spec().role(),s.after().copy()));
        KettleSetup.Plan setup=setupPlan(plan,servings,BrewingDefinitions.server());
        boolean ready=check.missing()==null && hasPaddle() && rack().problem()==null && rack().servings()==servings;
        boolean finishReady = finishing && check.missing()==null && rack(true).problem()==null && rack(true).servings()==servings;
        return new PlanListS2C.Entry(plan.key(),plan.output().copy(),steps,check.costs(),check.fuelNeeded(),check.fuelAvailable(),
                check.remainders(),ready,check.missing(),plan.contextual(),KettleSetup.tier(setup),
                estimateSeconds(plan,BrewingDefinitions.server()),PotionContentsService.durationCanScale(plan.output(),BrewingDefinitions.server()),plan.servingLimit(),expectedExtraction(plan,servings),
                finishing ? ServerConfig.get(ServerConfig.FINISHING_SECONDS) : 0, finishReady);
    }

    public Check check(BrewPlan plan, int servings) {
        return check(plan, servings, new TrayStock());
    }

    /**
     * The tray read once for a whole plan list (a large modded registry lists hundreds of plans): raw counts and prepared
     * kits' serving groups per reagent, and each reagent's preparability as it is first asked.
     */
    private final class TrayStock {
        private record Prep(Item item, @Nullable ResourceLocation profile) {}

        final Map<Item, Integer> raw = new HashMap<>();
        final Map<Item, Integer> kits = new HashMap<>();
        final Map<Prep, Boolean> preparable = new HashMap<>();
        final BrewingDefinitions defs = BrewingDefinitions.server();

        TrayStock() {
            for (int i = 0; i < TRAY_SIZE; i++) {
                ItemStack s = inventory.getStackInSlot(TRAY + i);
                if (s.isEmpty()) continue;
                Optional<KitData> kit = KitData.read(s);
                if (kit.isPresent()) kits.merge(kit.get().source().getItem(), kit.get().groups() * s.getCount(), Integer::sum);
                else raw.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }

        boolean preparable(Item item, @Nullable ResourceLocation profile) {
            return preparable.computeIfAbsent(new Prep(item, profile), k -> BrewersKettleBlockEntity.preparable(defs, item, profile));
        }
    }

    private Check check(BrewPlan plan, int servings, TrayStock stock) {
        return check(plan,servings,stock,true);
    }

    private Check check(BrewPlan plan, int servings, TrayStock stock, boolean needsPaddle) {
        List<PlanListS2C.Cost> costs = new ArrayList<>();
        Component missing = null;
        for (BrewPlan.Cost cost : plan.costs(servings)) {
            int raw = stock.raw.getOrDefault(cost.item(), 0);
            int kits = stock.kits.getOrDefault(cost.item(), 0);
            boolean preparable = stock.preparable(cost.item(), cost.preparationProfile());
            costs.add(new PlanListS2C.Cost(cost.item(), cost.count(), raw, kits, preparable));
            if (missing == null && raw + kits < cost.count()) {
                missing = Component.translatable("plan.immersive_brewing.missing.reagent", cost.count() - raw - kits, new ItemStack(cost.item()).getHoverName());
            }
        }
        Selection selection = select(plan, servings, true);
        List<ItemStack> remainders = selection == null
                ? owedRemainders(plan.costs(servings).stream().map(cost -> new ItemStack(cost.item(), cost.count())).toList())
                : owedRemainders(selectionStacks(selection));
        // Aggregated item totals can look sufficient when an indivisible multi-group kit overcovers an earlier
        // same-item step. The reservation pass is authoritative, so never advertise or start that plan as ready.
        if (missing == null && selection == null) missing = Component.translatable("plan.immersive_brewing.missing.kit_steps");
        int fuelNeeded = plan.fuelCharges(servings);
        int fuelAvailable = fuelCharges + inventory.getStackInSlot(FUEL).getCount() * ServerConfig.fuelChargesPerBlazePowder();
        if (missing == null && fuelAvailable < fuelNeeded) missing = Component.translatable("plan.immersive_brewing.missing.fuel", fuelNeeded, fuelAvailable);
        if (missing == null && !byproductRoom(remainders)) missing = Component.translatable("plan.immersive_brewing.missing.byproduct_room");
        if (missing == null && needsPaddle && !hasPaddle()) missing = Component.translatable("plan.immersive_brewing.missing.paddle");
        if (missing == null && servings > plan.servingLimit()) missing = Component.translatable("plan.immersive_brewing.missing.servings", plan.servingLimit());
        return new Check(costs, fuelNeeded, fuelAvailable, remainders, missing);
    }

    static boolean preparable(BrewingDefinitions defs, Item item, @Nullable ResourceLocation explicit) {
        ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(item, null, explicit);
        return choice != null && choice.value().preparation() != null;
    }

    public boolean hasPaddle() {
        ItemStack paddle = inventory.getStackInSlot(PADDLE);
        return paddle.is(ModTags.BREWING_PADDLES) && (!paddle.isDamageableItem() || paddle.getDamageValue() < paddle.getMaxDamage());
    }

    private boolean byproductRoom(List<ItemStack> remainders) {
        if (remainders.isEmpty()) return true;
        List<ItemStack> shelf = new ArrayList<>();
        for (int i = 0; i < BYPRODUCT_SIZE; i++) shelf.add(inventory.getStackInSlot(BYPRODUCT + i).copy());
        for (ItemStack r : remainders) {
            ItemStack left = r.copy();
            for (int i = 0; i < shelf.size() && !left.isEmpty(); i++) {
                ItemStack s = shelf.get(i);
                if (s.isEmpty()) {
                    shelf.set(i, left.copy());
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameTags(s, left) && s.getCount() < s.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
                    s.grow(move);
                    left.shrink(move);
                }
            }
            if (!left.isEmpty()) return false;
        }
        return true;
    }

    /** Sends the validated plan list for the current rack to a player who has the menu open. */
    public void sendPlans(ServerPlayer player) {
        Rack rack = rack();
        List<PlanListS2C.Entry> entries = new ArrayList<>();
        if (rack.problem() == null) {
            // Order by availability and length first (a stable sort keeps catalog order within), then build the bounded
            // list's entries only: a large modded registry offers far more plans than one list shows.
            record Candidate(BrewPlan plan, Check check) {}
            List<Candidate> candidates = new ArrayList<>();
            TrayStock stock = new TrayStock();
            for (BrewPlan plan : plansFor(rack)) candidates.add(new Candidate(plan, check(plan, rack.servings(), stock)));
            candidates.sort(Comparator.comparing((Candidate c) -> c.check().missing() != null).thenComparing(c -> c.plan().steps().size()));
            for (Candidate candidate : candidates) {
                if (entries.size() >= PlanListS2C.MAX_ENTRIES) break;
                BrewPlan plan = candidate.plan();
                if (MinecraftForge.EVENT_BUS.post(new BrewPlanEvent(player, worldPosition, plan.key().serialize(), rack.input(), plan.output(), rack.servings(), true))) {
                    continue;
                }
                Check check = candidate.check();
                List<PlanListS2C.Step> steps = new ArrayList<>();
                for (BrewPlan.Step s : plan.steps()) steps.add(new PlanListS2C.Step(s.reagent(), s.spec().phase(), s.spec().role(), s.after().copy()));
                KettleSetup.Plan setup = setupPlan(plan, rack.servings(), BrewingDefinitions.server());
                int tier = KettleSetup.tier(setup);
                boolean effects = AdapterRegistry.getAny(plan.steps().get(plan.steps().size() - 1).adapter()) != null
                        && AdapterRegistry.getAny(plan.steps().get(plan.steps().size() - 1).adapter()).contentsPolicy(plan.output()) == BrewingAdapter.ContentsPolicy.EFFECTS;
                entries.add(new PlanListS2C.Entry(plan.key(), plan.output().copy(), steps, check.costs(), check.fuelNeeded(), check.fuelAvailable(),
                        check.remainders(), check.missing() == null, check.missing(), plan.contextual(), tier,
                        estimateSeconds(plan, BrewingDefinitions.server()), effects, plan.servingLimit(), expectedExtraction(plan, rack.servings())));
            }
        }
        ModNetwork.sendTo(player, new PlanListS2C(worldPosition, rack.input(), rack.servings(), rack.problem(), entries));
    }

    private int expectedExtraction(BrewPlan plan, int servings) {
        Selection selection = select(plan, servings, true);
        return selection == null ? QualityMath.RAW_EXTRACTION : selection.extraction;
    }

    // ---- starting ----

    /** Reagent stacks chosen for a plan, step by step, kits first. */
    private record Selection(List<List<SlotStack>> perStep, int extraction) {}

    private static List<ItemStack> selectionStacks(Selection selection) {
        List<ItemStack> stacks = new ArrayList<>();
        for (List<SlotStack> step : selection.perStep()) for (SlotStack selected : step) stacks.add(selected.stack());
        return stacks;
    }

    /** Exact containers owed by consumed raw reagents or prepared kits, split into legal stack sizes. */
    static List<ItemStack> owedRemainders(Collection<ItemStack> consumed) {
        List<ItemStack> returns = new ArrayList<>();
        for (ItemStack stack : consumed) {
            Optional<KitData> kit = KitData.read(stack);
            ItemStack source = kit.map(KitData::source).orElse(stack);
            if (!source.hasCraftingRemainingItem()) continue;
            ItemStack unit = source.getCraftingRemainingItem();
            if (unit.isEmpty()) continue;
            int sourceUnits = stack.getCount() * kit.map(KitData::groups).orElse(1);
            int owed = sourceUnits * unit.getCount();
            for (ItemStack existing : returns) {
                if (owed <= 0) break;
                if (!ItemStack.isSameItemSameTags(existing, unit)) continue;
                int move = Math.min(owed, existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                owed -= move;
            }
            while (owed > 0) {
                int move = Math.min(owed, unit.getMaxStackSize());
                returns.add(unit.copyWithCount(move));
                owed -= move;
            }
        }
        return returns;
    }

    @Nullable
    private Selection select(BrewPlan plan, int servings, boolean simulate) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        ItemStack[] tray = new ItemStack[TRAY_SIZE];
        for (int i = 0; i < TRAY_SIZE; i++) tray[i] = inventory.getStackInSlot(TRAY + i).copy();
        List<List<SlotStack>> perStep = new ArrayList<>();
        int extractionSum = 0;
        int extractionUnits = 0;
        for (BrewPlan.Step step : plan.steps()) {
            int need = step.batch().reagentCost(servings);
            boolean prep = preparable(defs, step.reagent(), step.spec().preparationProfile());
            List<SlotStack> taken = new ArrayList<>();
            // Prepared kits first: they were made for this.
            for (int i = 0; i < TRAY_SIZE && need > 0; i++) {
                Optional<KitData> kit = KitData.read(tray[i]);
                if (kit.isEmpty() || !kit.get().source().is(step.reagent())) continue;
                int groups = kit.get().groups();
                int take = Math.min(tray[i].getCount(), (need + groups - 1) / groups);
                taken.add(new SlotStack(TRAY + i, tray[i].copyWithCount(take)));
                tray[i].shrink(take);
                need -= take * groups;
                if (prep) {
                    extractionSum += kit.get().extraction() * take * groups;
                    extractionUnits += take * groups;
                }
            }
            for (int i = 0; i < TRAY_SIZE && need > 0; i++) {
                if (!tray[i].is(step.reagent()) || KitData.read(tray[i]).isPresent()) continue;
                int take = Math.min(tray[i].getCount(), need);
                taken.add(new SlotStack(TRAY + i, tray[i].copyWithCount(take)));
                tray[i].shrink(take);
                need -= take;
                if (prep) {
                    extractionSum += QualityMath.RAW_EXTRACTION * take;
                    extractionUnits += take;
                }
            }
            if (need > 0) return null;
            perStep.add(taken);
        }
        int extraction = extractionUnits == 0 ? QualityMath.RAW_EXTRACTION : Math.round((float) extractionSum / extractionUnits);
        return new Selection(perStep, extraction);
    }

    /** Builds the kettle session plan from a brew plan: phases, roles, reagent cooling and the reaction profile. */
    public static KettleSetup.Plan setupPlan(BrewPlan plan, int servings, BrewingDefinitions defs) {
        BrewProfile profile = defs.brewProfile(plan.brewProfile());
        List<KettleSetup.Step> steps = new ArrayList<>();
        for (BrewPlan.Step s : plan.steps()) {
            ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(s.reagent(), s.source(), s.spec().preparationProfile());
            ReagentProfile.Reaction reaction = choice != null ? choice.value().reaction() : ReagentProfile.Reaction.DEFAULT;
            KettlePhase phase = s.spec().phase();
            Signature signature = phase == KettlePhase.BLOOM ? profile.bloomOrDefault().signature()
                    : phase == KettlePhase.INFUSE ? profile.infuse().signature() : profile.settleOrDefault().signature();
            double kick = Math.min(1.0, signature.kick * reaction.sensitivity());
            boolean lingering = s.spec().role() == KettleSetup.Role.CONTAINER && s.after().is(Items.LINGERING_POTION);
            steps.add(new KettleSetup.Step(phase, s.spec().role(), String.valueOf(ForgeRegistries.ITEMS.getKey(s.reagent())),
                    reaction.additionCooling(), kick, lingering));
        }
        return new KettleSetup.Plan(steps, profile.bloomOrDefault(), profile.infuse(), profile.settleOrDefault(), profile.disturbances(),
                profile.inertia(), servings);
    }

    /** Validates and reserves a plan, then starts the session (spec 20.2 Ready to Reserved to Running). */
    public void startBrew(ServerPlayer player, PlanKey key, float assistTime, float assistBand) {
        startBrew(player,key,assistTime,assistBand,false);
    }

    public void startFinishing(ServerPlayer player, PlanKey key) { startBrew(player,key,1,1,true); }

    private void startBrew(ServerPlayer player, PlanKey key, float assistTime, float assistBand, boolean finishing) {
        WorkshopResult result = setOut(player, key, assistTime, assistBand, finishing, true);
        if (!result.accepted() && result.reason() != null) notice(player, result.reason(), true);
    }

    /**
     * The one reservation transaction every way of starting a batch shares (handoff 6.2): the optional planner, JEI and
     * the Notebook through {@link #startBrew}, the recipe page by a tipped bottle through {@link #acceptSetup}. Re-resolves
     * the plan key against the actual bottles and tray, re-checks every supply, fires the plan event, then moves exactly
     * what the plan costs into the ledger at once. Refusals change nothing.
     *
     * @param reopenView reopen the working view (the planner was open over it); the page's own path is already there
     */
    private WorkshopResult setOut(ServerPlayer player, PlanKey key, float assistTime, float assistBand, boolean finishing, boolean reopenView) {
        if (level == null || level.getServer() == null) return WorkshopResult.IGNORED;
        if (ServerConfig.disabled()) return WorkshopResult.refuse("notice.immersive_brewing.disabled");
        if (ledger != null || SessionManager.stationBusy(this)) return WorkshopResult.refuse("notice.immersive_brewing.busy");
        if (SessionManager.playerBusy(player)) return WorkshopResult.refuse("notice.immersive_brewing.already_working");
        Rack rack = rack(finishing);
        if (rack.problem() != null) return WorkshopResult.refuse(rack.problem());
        if (rack.servings() > ServerConfig.maxBatchServings()) {
            return WorkshopResult.refuse("plan.immersive_brewing.missing.servings", ServerConfig.maxBatchServings());
        }
        BrewPlan plan = PlanIndex.get(level.getServer().getRecipeManager()).resolve(key, rack.input(), trayItems());
        if (plan == null) {
            if (reopenView) sendPlans(player);
            return WorkshopResult.refuse("notice.immersive_brewing.plan_invalid");
        }
        if (finishing && (!ServerConfig.get(ServerConfig.QUICK_FINISHING) || !com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan))) {
            return WorkshopResult.refuse("finishing.immersive_brewing.ineligible");
        }
        Check check = check(plan, rack.servings(),new TrayStock(),!finishing);
        if (check.missing() != null) return WorkshopResult.refuse(check.missing());
        if (MinecraftForge.EVENT_BUS.post(new BrewPlanEvent(player, worldPosition, key.serialize(), rack.input(), plan.output(), rack.servings(), false))) {
            return WorkshopResult.refuse("notice.immersive_brewing.plan_refused");
        }
        Selection selection = select(plan, rack.servings(), false);
        if (selection == null) return WorkshopResult.refuse("notice.immersive_brewing.plan_invalid");
        if (!byproductRoom(owedRemainders(selectionStacks(selection)))) return WorkshopResult.refuse("plan.immersive_brewing.missing.byproduct_room");
        BrewingDefinitions defs = BrewingDefinitions.server();
        boolean assist = ServerConfig.get(ServerConfig.ALLOW_ASSISTED_CONTROLS);
        KettleSetup.Assist approved = assist ? new KettleSetup.Assist(Math.min(assistTime, ServerConfig.get(ServerConfig.MAX_ASSIST_TIME_MULTIPLIER)),
                Math.min(assistBand, ServerConfig.get(ServerConfig.MAX_ASSIST_BAND_MULTIPLIER))) : KettleSetup.Assist.NONE;
        long seed = ((ServerLevel) level).getRandom().nextLong();

        // ---- reserve: every owned stack moves into the ledger in this one transition ----
        List<ItemStack> bottles = new ArrayList<>();
        List<Integer> rackSlots = new ArrayList<>();
        for (int i = 0; i < RACK_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(RACK + i);
            if (s.isEmpty()) continue;
            bottles.add(s.copy());
            rackSlots.add(i);
            putDirect(RACK + i, ItemStack.EMPTY);
        }
        List<SlotStack> reserved = new ArrayList<>();
        CompoundTag snapshot = new CompoundTag();
        ListTag stepTags = new ListTag();
        for (int i = 0; i < plan.steps().size(); i++) {
            BrewPlan.Step step = plan.steps().get(i);
            List<SlotStack> taken = selection.perStep().get(i);
            for (SlotStack t : taken) {
                ItemStack live = inventory.getStackInSlot(t.slot());
                putDirect(t.slot(), live.copyWithCount(live.getCount() - t.stack().getCount()));
                reserved.add(t);
            }
            CompoundTag st = new CompoundTag();
            st.putString("source", step.source().toString());
            st.putString("reagent", String.valueOf(ForgeRegistries.ITEMS.getKey(step.reagent())));
            st.putString("adapter", step.adapter().toString());
            st.putInt("adapterVersion", step.adapterVersion());
            st.put("before", LedgerCodec.saveStack(step.before()));
            st.put("after", LedgerCodec.saveStack(step.after()));
            st.putInt("units", taken.size());
            st.putBoolean("effect", step.spec().createsEffect());
            st.putBoolean("container",step.spec().role()==KettleSetup.Role.CONTAINER);
            st.putInt("applications", step.batch().applications(bottles.size()));
            stepTags.add(st);
        }
        snapshot.put("steps", stepTags);
        snapshot.putString("plan", key.serialize());
        snapshot.putString("profile", plan.brewProfile().toString());
        snapshot.putInt("servings", bottles.size());
        snapshot.putBoolean("contextual", plan.contextual());
        snapshot.putFloat("assistTime", (float) approved.time());
        snapshot.putFloat("assistBand", (float) approved.band());

        int needed = check.fuelNeeded();
        int perPowder = ServerConfig.fuelChargesPerBlazePowder();
        while (fuelCharges < needed && !inventory.getStackInSlot(FUEL).isEmpty()) {
            ItemStack fuelStack = inventory.getStackInSlot(FUEL);
            putDirect(FUEL, fuelStack.copyWithCount(fuelStack.getCount() - 1));
            fuelCharges += perPowder;
        }
        fuelCharges -= needed;

        BrewingMetadata inputMeta = BrewingMetadata.read(bottles.get(0)).orElse(null);
        if (inputMeta != null) snapshot.put("inputMeta", inputMeta.toTag());
        int[] slots = rackSlots.stream().mapToInt(Integer::intValue).toArray();
        CompoundTag process;
        if (finishing) {
            process = FinishingProcess.begin(plan.steps().size(), slots, ServerConfig.get(ServerConfig.FINISHING_SECONDS)).save();
        } else {
            var resolved = WorkshopProfiles.brewing(plan.brewProfile(), defs);
            snapshot.putString("workshopProfile", resolved.source());
            snapshot.putBoolean("workshopFallback", resolved.fallback());
            ResourceLocation presentation = WorkshopProfiles.presentationOf(resolved.source());
            if (presentation != null) snapshot.putString("presentation", presentation.toString());
            process = KettleProcess.begin(kettleSetup(plan, resolved.value(), defs, approved.band()), slots, player.getUUID()).save();
        }
        ledger = new JobLedger(java.util.UUID.randomUUID(), finishing ? Operation.FINISH : Operation.BREW, JobState.RUNNING, bottles, reserved, needed,
                snapshot, plan.output().copy(), new long[0], new long[0], seed, selection.extraction(), player.getUUID(),
                player.getGameProfile().getName(), List.of(), null, 0, defs.generation(), newLineage());
        ledger.setProcess(process);
        this.process = null;
        this.finishingProcess = null;
        paddleTrack.reset();
        leases.clear();
        carryPose.clear();
        setup.unpin();
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, ledger.jobId(), 0, 0, 0);
        notice(player, Component.translatable(finishing ? "finishing.immersive_brewing.started" : "workshop.immersive_brewing.kettle.set_out",
                bottles.size(), plan.output().getHoverName()), false);
        if (reopenView) {
            // The planner closes; the player works at the apparatus. Their working view (re)opens on this kettle.
            player.closeContainer();
            openWorkingView(player);
        } else {
            markWorkshopDirty(true);
        }
        return WorkshopResult.ACCEPTED;
    }

    /**
     * Starts the batch the recipe page shows (handoff 6.2 step 6-7): only when the client names the page revision it saw
     * and that page is still the one shown, so a change since (an ingredient taken, another page turned) refuses with the
     * reason and nothing is consumed. {@code pourSlot} pours that bottle at once (the bottle tipped to begin).
     */
    WorkshopResult acceptSetup(ServerPlayer player, WorkshopIntent intent, int pourSlot) {
        setup.refresh();
        KettleSetupService.Preview page = setup.preview();
        if (intent.expected() != page.revision()) {
            markWorkshopDirty(true);
            return WorkshopResult.refuse("workshop.immersive_brewing.setup.changed");
        }
        if (!page.acceptable()) {
            Component why = page.problem() != null ? page.problem() : Component.translatable("workshop.immersive_brewing.setup.not_ready");
            return WorkshopResult.refuse(why);
        }
        KettleSetupService.Route route = page.shown();
        double band = 1.0 + Math.max(0, Math.min(25, intent.argument())) / 100.0;
        WorkshopResult started = setOut(player, route.plan().key(), 1F, (float) band, route.finishing(), false);
        if (!started.accepted()) {
            setup.markDirty();
            return started;
        }
        participate(player);
        if (pourSlot < 0 || route.finishing()) return started;
        KettleProcess p = liveProcess();
        int index = p == null ? -1 : p.portionAtSlot(pourSlot);
        if (index >= 0 && p.pour(index)) {
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.POUR, ledger.jobId(), (int) p.ticks(), pourSlot, 0);
            markWorkshopDirty(true);
        }
        return WorkshopResult.ACCEPTED;
    }

    /**
     * The physical setup for a plan (handoff 13): the reaction family and temperature bands from the workshop profile
     * (or the translated brew profile), each step's cooling and trouble from its reagent profile. An approved
     * assistance request only widens the temperature tolerances around the same centre; the attainable score is the
     * same with or without it.
     */
    public static KettleProcess.Setup kettleSetup(BrewPlan plan, WorkshopProfile.Brewing brewing, BrewingDefinitions defs, double assistBand) {
        int n = Math.min(KettleProcess.MAX_STEPS, plan.steps().size());
        double[] cooling = new double[n];
        double[] kick = new double[n];
        for (int i = 0; i < n; i++) {
            BrewPlan.Step s = plan.steps().get(i);
            ProfileSelector.Choice<ReagentProfile> choice = defs.reagentProfile(s.reagent(), s.source(), s.spec().preparationProfile());
            ReagentProfile.Reaction reaction = choice != null ? choice.value().reaction() : ReagentProfile.Reaction.DEFAULT;
            cooling[i] = reaction.additionCooling();
            kick[i] = Math.min(1, brewing.family().kick * reaction.sensitivity());
        }
        double widen = Math.max(1.0, Math.min(1.25, assistBand));
        double centre = (brewing.idealLow() + brewing.idealHigh()) / 2;
        double half = (brewing.idealHigh() - brewing.idealLow()) / 2 * widen;
        double workHalfLow = (centre - brewing.workLow()) * widen, workHalfHigh = (brewing.workHigh() - centre) * widen;
        return new KettleProcess.Setup(brewing.family(), centre - half, centre + half, centre - workHalfLow, centre + workHalfHigh,
                brewing.inertia(), brewing.stepSeconds(), brewing.restSeconds(), cooling, kick, plan.brewProfile().toString(), defs.generation());
    }

    /** A rough active-work estimate for the plan list, in seconds (a playtest target, never a promise). */
    public static int estimateSeconds(BrewPlan plan, BrewingDefinitions defs) {
        var brewing = WorkshopProfiles.brewing(plan.brewProfile(), defs).value();
        return (int) Math.round(6 + plan.steps().size() * (brewing.stepSeconds() + 2) + brewing.restSeconds() + 3);
    }

    private CompoundTag display(ItemStack output, @Nullable BrewPlan plan, KettleSetup.Assist assist) {
        CompoundTag tag = new CompoundTag();
        tag.put("output", LedgerCodec.saveStack(output.copyWithCount(1)));
        if (ledger != null) {
            tag.putInt("servings", ledger.inputs().size());
            tag.putInt("extraction", ledger.extraction());
            tag.putString("profile", ledger.snapshot().getString("profile"));
            tag.putIntArray("stageColors", StationPresentations.stageColors(ledger));
            ListTag stages = new ListTag();
            for (var entry : ledger.snapshot().getList("steps", Tag.TAG_COMPOUND)) {
                if (entry instanceof CompoundTag step) stages.add(step.getCompound("after").copy());
            }
            tag.put("stageDisplays", stages);
            if (!ledger.inputs().isEmpty()) tag.put("input", LedgerCodec.saveStack(ledger.inputs().get(0).copyWithCount(1)));
        }
        tag.putFloat("assistTime", (float) assist.time());
        tag.putFloat("assistBand", (float) assist.band());
        tag.putBoolean("assisted", assist.time() > 1.0 || assist.band() > 1.0);
        return tag;
    }

    @Override
    public void handleAction(ServerPlayer player, StationActionC2S.Action action, int argument) {
        switch (action) {
            case REQUEST_PLANS -> com.otectus.immersivebrewing.recipe.CatalogSearch.submit(player,
                    new com.otectus.immersivebrewing.network.packet.CatalogQueryC2S(worldPosition,0,"",List.of(),0,0,false,3));
            case RESUME -> {
                if (ledger != null && ledger.physical()) {
                    WorkshopResult r = unsealPhysical(player);
                    if (r.reason() != null) notice(player, r.reason(), true);
                    player.closeContainer();
                    openWorkingView(player);
                } else resume(player);
            }
            case CANCEL_PASSIVE -> {
                if (ledger != null && ledger.physical()) {
                    sealPhysical(player, "workshop.immersive_brewing.seal.manual");
                } else if(ledger!=null&&ledger.operation()==Operation.FINISH) {
                    ledger.setState(ledger.state()==JobState.RUNNING?JobState.SEALED:ledger.state()); changed();
                }
            }
            default -> super.handleAction(player, action, argument);
        }
    }

    /** Resumes a sealed brew with its stored state and seed (spec 20.4: count-in, held input neutral, no reroll). */
    public void resume(ServerPlayer player) {
        if (ledger != null && ledger.physical()) {
            // A hands-on batch never enters the legacy session: reopening it is an unseal at the apparatus.
            WorkshopResult r = unsealPhysical(player);
            if (r.reason() != null) notice(player, r.reason(), true);
            return;
        }
        revalidateRecovery();
        if(ledger!=null&&ledger.operation()==Operation.FINISH&&ledger.state()==JobState.SEALED) {
            String problem=recoveryProblem();
            if(problem!=null) ledger.hold(problem); else { ledger.setState(JobState.RUNNING); ledger.countResume(); }
            changed(); return;
        }
        if (ledger == null || ledger.state() != JobState.SEALED || ledger.operation() != Operation.BREW) {
            notice(player, Component.translatable("notice.immersive_brewing.nothing_to_resume"), true);
            return;
        }
        if (SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        if (!hasPaddle()) {
            notice(player, Component.translatable("plan.immersive_brewing.missing.paddle"), true);
            return;
        }
        String problem = recoveryProblem();
        if (problem != null) {
            ledger.hold(problem);
            changed();
            notice(player, Component.translatable("notice.immersive_brewing.held", Component.translatable(problem)), true);
            return;
        }
        SimHandle sim;
        try {
            sim = SimHandles.create(SessionKind.BREWING, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            ledger.hold("recovery.immersive_brewing.state_unreadable");
            changed();
            notice(player, Component.translatable("notice.immersive_brewing.held", Component.translatable("recovery.immersive_brewing.state_unreadable")), true);
            return;
        }
        player.closeContainer();
        KettleSetup.Assist assist = new KettleSetup.Assist(ledger.snapshot().getFloat("assistTime"), ledger.snapshot().getFloat("assistBand"));
        SessionManager.begin(player, this, sim, true, display(ledger.outputSnapshot(), null, assist));
    }

    /** Why a sealed brew can not resume right now (a missing adapter or a changed adapter contract), or null. */
    @Nullable
    private String recoveryProblem() {
        if (ledger == null) return null;
        ListTag steps = ledger.snapshot().getList("steps", Tag.TAG_COMPOUND);
        for (int i = 0; i < steps.size(); i++) {
            CompoundTag st = steps.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(st.getString("adapter"));
            BrewingAdapter adapter = id == null ? null : AdapterRegistry.get(id, BrewingDefinitions.server());
            if (adapter == null) return "recovery.immersive_brewing.adapter_missing";
            if (adapter.version() != st.getInt("adapterVersion")) return "recovery.immersive_brewing.adapter_changed";
        }
        for (ItemStack s : ledger.inputs()) if (s.isEmpty()) return "recovery.immersive_brewing.missing_item";
        return null;
    }

    @Override
    public void revalidateRecovery() {
        if (ledger == null || ledger.operation() != Operation.BREW && ledger.operation()!=Operation.FINISH || ledger.unknownSchema() != null) return;
        if (ledger.physical() && ledger.operation() == Operation.BREW) {
            KettleProcess live = liveProcess();
            if (live != null && live.finalized()) {
                // The outputs are final: only a full rack can hold bottling up, and it never needs the adapters again.
                if (ledger.state() == JobState.RECOVERY && ledger.recoveryReason() != null && ledger.recoveryReason().startsWith("recovery.immersive_brewing.adapter")) {
                    ledger.clearRecovery();
                    ledger.setState(JobState.SEALED);
                    changed();
                }
                return;
            }
        }
        String problem = recoveryProblem();
        if(ledger.operation()==Operation.FINISH&&ledger.state()==JobState.RECOVERY
                &&"recovery.immersive_brewing.output_blocked".equals(ledger.recoveryReason())&&byproductRoom(ledgerRemainders(ledger))) {
            ledger.clearRecovery(); ledger.setState(JobState.SEALED); changed();
        }
        if (problem != null && ledger.state() != JobState.RECOVERY) {
            ledger.hold(problem);
            changed();
        } else if (problem == null && ledger.state() == JobState.RECOVERY && ledger.recoveryReason() != null
                && ledger.recoveryReason().startsWith("recovery.immersive_brewing.adapter")) {
            ledger.clearRecovery();
            ledger.setState(JobState.SEALED);
            changed();
        }
    }

    @Override
    protected void cancelReservation() {
        if (ledger == null) return;
        JobLedger l = ledger;
        ledger = null;
        process = null;
        finishingProcess = null;
        pourQueue = fillQueue = 0;
        paddleTrack.reset();
        leases.clear();
        markWorkshopDirty(true);
        int slot = RACK;
        for (ItemStack bottle : l.inputs()) {
            while (slot < RACK + RACK_SIZE && !inventory.getStackInSlot(slot).isEmpty()) slot++;
            if (slot < RACK + RACK_SIZE) putDirect(slot++, bottle.copy());
            else dropSafely(bottle);
        }
        for (SlotStack r : l.reserved()) {
            ItemStack live = inventory.getStackInSlot(r.slot());
            if (live.isEmpty()) putDirect(r.slot(), r.stack().copy());
            else if (ItemStack.isSameItemSameTags(live, r.stack()) && live.getCount() + r.stack().getCount() <= live.getMaxStackSize()) {
                putDirect(r.slot(), live.copyWithCount(live.getCount() + r.stack().getCount()));
            } else {
                dropSafely(r.stack());
            }
        }
        fuelCharges += l.fuelCharges();
        changed();
    }

    /** Last resort for a return with no room (should not happen: reserved slots refuse insertion). Logged. */
    private void dropSafely(ItemStack stack) {
        if (level == null || stack.isEmpty()) return;
        ImmersiveBrewing.LOGGER.warn("Immersive Brewing: no room to return {} at {}; dropped", stack, worldPosition);
        Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack.copy());
    }

    // ---- commit ----

    /**
     * Current snapshots carry an explicit role bit. Schema-1 snapshots do not, so prove their container-only shape
     * against both saved endpoints before asking QuickFinishing's potion/effect proof to preserve foreign stack data.
     */
    private static ItemStack preserveContainerStep(CompoundTag step, ItemStack before, ItemStack ingredient, ItemStack result) {
        boolean container;
        if (step.contains("container", Tag.TAG_BYTE)) {
            container = step.getBoolean("container");
        } else {
            ItemStack savedBefore = LedgerCodec.loadStack(step.getCompound("before"));
            ItemStack savedAfter = LedgerCodec.loadStack(step.getCompound("after"));
            container = !savedBefore.isEmpty() && !savedAfter.isEmpty()
                    && com.otectus.immersivebrewing.recipe.QuickFinishing.containers(savedBefore, savedAfter)
                    && PotionContentsService.sameCanonical(savedBefore, before)
                    && savedAfter.getItem() == result.getItem()
                    && PotionUtils.getPotion(savedAfter) == PotionUtils.getPotion(result);
        }
        return container ? com.otectus.immersivebrewing.recipe.QuickFinishing.preserve(before, ingredient, result) : result;
    }

    @Override
    @Nullable
    public CompoundTag completeJob(@Nullable ServerPlayer player, SimHandle sim) {
        if (ledger == null || ledger.operation() != Operation.BREW || ledger.state() != JobState.RUNNING) return null;
        if (!(sim instanceof SimHandles.Brewing brewing)) return null;
        BrewScore score = brewing.sim().score();
        ListTag steps = ledger.snapshot().getList("steps", Tag.TAG_COMPOUND);

        // Preflight: adapters, contracts and every serving's actual result against the snapshot.
        String problem = recoveryProblem();
        if (problem != null) {
            ledger.setSimState(sim.saveState());
            ledger.hold(problem);
            changed();
            return null;
        }
        List<ItemStack> ingredients = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < steps.size(); i++) {
            int units = steps.getCompound(i).getInt("units");
            if (units <= 0 || cursor >= ledger.reserved().size()) {
                return holdWith(sim, "recovery.immersive_brewing.reagents_missing");
            }
            ItemStack reagent = ledger.reserved().get(cursor).stack();
            ingredients.add(KitData.read(reagent).map(KitData::source).orElse(reagent).copyWithCount(1));
            cursor += units;
        }
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack bottle : ledger.inputs()) {
            for (int n = 0; n < bottle.getCount(); n++) {
                ItemStack state = PotionContentsService.canonical(bottle).copyWithCount(1);
                for (int i = 0; i < steps.size(); i++) {
                    CompoundTag st = steps.getCompound(i);
                    BrewingAdapter adapter = AdapterRegistry.get(new ResourceLocation(st.getString("adapter")), BrewingDefinitions.server());
                    ItemStack expected = LedgerCodec.loadStack(st.getCompound("after"));
                    ItemStack result = adapter == null ? ItemStack.EMPTY : adapter.commit(state, ingredients.get(i));
                    if(!result.isEmpty()) result=preserveContainerStep(st,state,ingredients.get(i),result);
                    if (result.isEmpty() || !PotionContentsService.sameCanonical(result, expected)) {
                        return holdWith(sim, "recovery.immersive_brewing.source_changed");
                    }
                    state = result;
                }
                outputs.add(state);
            }
        }

        boolean createsEffect = false;
        for (int i = 0; i < steps.size(); i++) createsEffect |= steps.getCompound(i).getBoolean("effect");
        BrewingMetadata inputMeta = ledger.snapshot().contains("inputMeta", Tag.TAG_COMPOUND)
                ? BrewingMetadata.fromTag(ledger.snapshot().getCompound("inputMeta")).orElse(null) : null;
        ItemStack canonicalInput = PotionContentsService.canonical(ledger.inputs().get(0));
        QualityInheritance.Outcome outcome = QualityInheritance.brew(canonicalInput, inputMeta, createsEffect, ledger.extraction(), score.control(),
                ledger.lineage(), ledger.brewerId(), ledger.brewerName());
        int servings = outputs.size();

        BrewResultEvent.Pre pre = new BrewResultEvent.Pre(player, worldPosition, outputs.get(0), servings, outcome.metadata().extraction(),
                score.control(), outcome.metadata().quality());
        if (MinecraftForge.EVENT_BUS.post(pre)) return holdWith(sim, "recovery.immersive_brewing.vetoed");
        BrewingMetadata meta = outcome.metadata();
        if (pre.quality() < meta.quality()) meta = meta.withScores(meta.extraction(), meta.control(), pre.quality());

        // ---- the transition: outputs into the rack, remainders to the shelf, ledger closed ----
        JobLedger l = ledger;
        ledger = null;
        for (int i = 0; i < outputs.size() && i < RACK_SIZE; i++) {
            ItemStack out = outputs.get(i);
            BrewingMetadata.write(out, meta);
            putDirect(RACK + i, out);
            finished[RACK + i] = true;
        }
        for (ItemStack remainder : ledgerRemainders(l)) addByproduct(remainder);
        wearPaddle();
        lastLineage = meta.lineage();
        ItemStack shown = outputs.get(0).copyWithCount(1);
        BrewScore.Tip tip = score.tip();
        lastResult = new StationResult(Operation.BREW, shown, servings, meta.quality(), meta.extraction(), score.control(), tip.key(),
                tip.args(), l.brewerName());
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), sim.step(), 0, 0);

        MinecraftForge.EVENT_BUS.post(new BrewResultEvent.Post(player, worldPosition, shown, servings, meta.extraction(), score.control(), meta.quality()));
        CompoundTag result = resultTag(score, meta, outcome, shown, servings, l);
        if (player != null) {
            ModAdvancements.milestone(player, Milestone.FIRST_BREW);
            if (meta.grade().ordinal() >= Grade.FINE.ordinal()) ModAdvancements.milestone(player, Milestone.FINE_BATCH);
            if (meta.grade() == Grade.MASTERWORK) ModAdvancements.milestone(player, Milestone.MASTERWORK_BATCH);
            if (servings >= 6) ModAdvancements.milestone(player, Milestone.SIX_SERVINGS);
            PlayerGuide.recordResult(player, result);
            int xp = ServerConfig.get(ServerConfig.COMPLETION_EXPERIENCE);
            if (xp > 0 && level instanceof ServerLevel sl) ExperienceOrb.award(sl, player.position(), xp);
        }
        return result;
    }

    @Nullable
    private CompoundTag holdWith(SimHandle sim, String reason) {
        if (ledger != null) {
            ledger.setSimState(sim.saveState());
            ledger.hold(reason);
            changed();
        }
        return null;
    }

    private CompoundTag resultTag(BrewScore score, BrewingMetadata meta, QualityInheritance.Outcome outcome, ItemStack output, int servings, JobLedger l) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "brewing");
        tag.putString("plan", l.snapshot().getString("plan"));
        tag.putFloat("assistTime", l.snapshot().getFloat("assistTime"));
        tag.putFloat("assistBand", l.snapshot().getFloat("assistBand"));
        tag.putString("profile", l.snapshot().getString("profile"));
        tag.put("input", l.snapshot().getList("steps", Tag.TAG_COMPOUND).getCompound(0).getCompound("before").copy());
        tag.put("output", LedgerCodec.saveStack(output));
        tag.putInt("count", servings);
        tag.putInt("E", meta.extraction());
        tag.putInt("C", score.control());
        tag.putInt("Q", meta.quality());
        tag.putInt("candidate", outcome.candidate());
        tag.putBoolean("capped", outcome.capped());
        tag.putDouble("thermal", score.thermal());
        tag.putDouble("circulation", score.circulation());
        tag.putDouble("dosing", score.dosing());
        ListTag outcomes = new ListTag();
        for (int i = 0; i < score.outcomes().length; i++) {
            CompoundTag o = new CompoundTag();
            o.putString("item", score.additionItems()[i]);
            o.putString("outcome", score.outcomes()[i].key());
            o.putDouble("credit", score.additionCredits()[i]);
            outcomes.add(o);
        }
        tag.put("additions", outcomes);
        ListTag phases = new ListTag();
        for (int i = 0; i < score.phases().length; i++) {
            CompoundTag p = new CompoundTag();
            p.putString("phase", "phase.immersive_brewing." + score.phases()[i].serialized());
            p.putDouble("thermal", score.phaseThermal()[i]);
            p.putDouble("circulation", score.phaseCirculation()[i]);
            p.putBoolean("counted", score.phaseCounted()[i]);
            phases.add(p);
        }
        tag.put("phases", phases);
        BrewScore.Tip tip = score.tip();
        tag.putString("tip", tip.key());
        ListTag args = new ListTag();
        for (String a : tip.args()) args.add(StringTag.valueOf(a));
        tag.put("tipArgs", args);
        tag.putInt("resumes", l.resumes());
        return tag;
    }

    private void addByproduct(ItemStack stack) {
        ItemStack left = stack;
        for (int i = 0; i < BYPRODUCT_SIZE && !left.isEmpty(); i++) {
            ItemStack s = inventory.getStackInSlot(BYPRODUCT + i);
            if (s.isEmpty()) {
                putDirect(BYPRODUCT + i, left);
                left = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameTags(s, left) && s.getCount() < s.getMaxStackSize()) {
                int move = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
                putDirect(BYPRODUCT + i, s.copyWithCount(s.getCount() + move));
                left = left.copyWithCount(left.getCount() - move);
            }
        }
        if (!left.isEmpty()) dropSafely(left);
    }

    private void wearPaddle() {
        ItemStack paddle = inventory.getStackInSlot(PADDLE);
        if (paddle.isEmpty() || !paddle.isDamageableItem() || level == null) return;
        ItemStack worn = paddle.copy();
        if (worn.hurt(1, level.getRandom(), null)) {
            putDirect(PADDLE, ItemStack.EMPTY);
            level.playSound(null, worldPosition, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
        } else {
            putDirect(PADDLE, worn);
        }
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (ledger == null && level != null && setup.tick(level.getGameTime())) markWorkshopDirty(true);
        if(ledger!=null&&ledger.state()==JobState.RECOVERY&&level!=null&&level.getGameTime()%20==0) revalidateRecovery();
        if(ledger==null||ledger.operation()!=Operation.FINISH||ledger.state()!=JobState.RUNNING||ledger.physical()) return;
        long done=ledger.simState().length==0?1:ledger.simState()[0]+1;
        ledger.setSimState(new long[]{done}); setChanged();
        int duration=Math.max(1,ledger.snapshot().getInt("duration"));
        if(done%10==0) setVisual(new StationVisual(2,0,.4F,0,0,Math.min(1,(float)done/duration),true));
        if(done>=duration) completeFinishing();
    }

    private void completeFinishing() {
        JobLedger l=ledger;
        if(l==null||l.operation()!=Operation.FINISH||l.state()!=JobState.RUNNING) return;
        String problem=recoveryProblem();
        if(problem!=null) { l.hold(problem); changed(); return; }
        ListTag steps=l.snapshot().getList("steps",Tag.TAG_COMPOUND);
        List<ItemStack> outputs=new ArrayList<>();
        for(ItemStack original:l.inputs()) {
            ItemStack state=original.copyWithCount(1); int cursor=0;
            for(int i=0;i<steps.size();i++) {
                CompoundTag step=steps.getCompound(i);
                if(cursor>=l.reserved().size()) { l.hold("recovery.immersive_brewing.reagents_missing"); changed(); return; }
                ItemStack held=l.reserved().get(cursor).stack(); cursor+=step.getInt("units");
                ItemStack reagent=KitData.read(held).map(KitData::source).orElse(held).copyWithCount(1);
                BrewingAdapter adapter=AdapterRegistry.get(new ResourceLocation(step.getString("adapter")),BrewingDefinitions.server());
                ItemStack actual=adapter==null?ItemStack.EMPTY:adapter.commit(PotionContentsService.canonical(state),reagent);
                if(actual.isEmpty()) { l.hold("recovery.immersive_brewing.source_changed"); changed(); return; }
                actual=com.otectus.immersivebrewing.recipe.QuickFinishing.preserve(state,reagent,actual);
                if(!PotionContentsService.sameCanonical(actual,LedgerCodec.loadStack(step.getCompound("after")))
                        || !java.util.Objects.equals(PotionContentsService.canonicalTag(state),PotionContentsService.canonicalTag(actual))) {
                    l.hold("recovery.immersive_brewing.source_changed"); changed(); return;
                }
                state=actual;
            }
            outputs.add(state);
        }
        List<ItemStack> returns=ledgerRemainders(l);
        if(!byproductRoom(returns)) { l.hold("recovery.immersive_brewing.output_blocked"); changed(); return; }
        ledger=null;
        for(int i=0;i<outputs.size();i++) { putDirect(RACK+i,outputs.get(i)); finished[RACK+i]=true; }
        for(ItemStack remainder:returns) addByproduct(remainder);
        setVisual(StationVisual.IDLE); changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), 0, 0, 0);
        // Finishing grants no brewing score, milestone, XP, or replayable completion event.
    }

    private static List<ItemStack> ledgerRemainders(JobLedger ledger) {
        List<ItemStack> consumed = new ArrayList<>();
        for (SlotStack reserved : ledger.reserved()) consumed.add(reserved.stack());
        return owedRemainders(consumed);
    }

    /** Labels the batch just finished in the rack (all finished bottles of the last lineage). */
    public void labelLastBatch(ServerPlayer player, @Nullable String label, @Nullable String note) {
        int labelled = 0;
        for (int i = 0; i < RACK_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(RACK + i);
            if (!finished[RACK + i]) continue;
            Optional<BrewingMetadata> meta = BrewingMetadata.read(s);
            if (meta.isEmpty() || meta.get().lineage() != lastLineage) continue;
            ItemStack copy = s.copy();
            BrewingMetadata.write(copy, meta.get().withLabel(label, note));
            putDirect(RACK + i, copy);
            finished[RACK + i] = true;
            labelled++;
        }
        notice(player, Component.translatable(labelled > 0 ? "notice.immersive_brewing.label.batch" : "notice.immersive_brewing.label.nothing", labelled), labelled == 0);
    }

    // ---- interactions ----

    @Override
    protected InteractionResult useWith(Player player, InteractionHand hand, ItemStack held) {
        if (held.is(Items.WATER_BUCKET)) {
            if (level == null || level.isClientSide) return InteractionResult.SUCCESS;
            int filled = fillWater(4, false);
            if (filled == 0) {
                notice(player, Component.translatable(ServerConfig.get(ServerConfig.ALLOW_WATER_FLUID_FILLING) ? "notice.immersive_brewing.water.no_bottles"
                        : "notice.immersive_brewing.water.disabled"), true);
                return InteractionResult.CONSUME;
            }
            if (!player.getAbilities().instabuild) player.setItemInHand(hand, new ItemStack(Items.BUCKET));
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.WATER, StationVisual.NONE, 0, 0, 0);
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof BatchCarrierItem) {
            if (level == null || level.isClientSide) return InteractionResult.SUCCESS;
            carrierExchange(player, held);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** Converts up to {@code bottles} real empty glass bottles in an idle rack into water bottles. */
    int fillWater(int bottles, boolean simulate) {
        if (ledger != null || !ServerConfig.get(ServerConfig.ALLOW_WATER_FLUID_FILLING)) return 0;
        int filled = 0;
        for (int i = 0; i < RACK_SIZE && filled < bottles; i++) {
            ItemStack s = inventory.getStackInSlot(RACK + i);
            if (!s.is(Items.GLASS_BOTTLE)) continue;
            if (!simulate) putDirect(RACK + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
            filled++;
        }
        return filled;
    }

    /** Batch Carrier at the Kettle: collect finished bottles, or load potions into an idle rack's empty slots. */
    private void carrierExchange(Player player, ItemStack carrier) {
        if (ledger != null) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        List<ItemStack> contents = CarrierContents.read(carrier);
        if (anyFinished()) {
            int moved = collectFinished(contents, RACK, RACK_SIZE);
            CarrierContents.write(carrier, contents);
            notice(player, Component.translatable("notice.immersive_brewing.carrier.collected", moved), moved == 0);
            return;
        }
        int moved = loadFromCarrier(contents, RACK, RACK_SIZE, s -> isBottle(s) && !s.is(Items.GLASS_BOTTLE));
        CarrierContents.write(carrier, contents);
        notice(player, Component.translatable("notice.immersive_brewing.carrier.loaded", moved), moved == 0);
    }

    // ---- status ----

    @Override
    protected Component status() {
        if (ledger != null) {
            if(ledger.operation()==Operation.FINISH&&ledger.state()==JobState.RUNNING) return Component.translatable("finishing.immersive_brewing.progress",
                    ledger.simState().length==0?0:Math.min(100,100*ledger.simState()[0]/Math.max(1,ledger.snapshot().getInt("duration"))));
            if (ledger.physical() && ledger.state() == JobState.RUNNING) {
                if (ledger.operation() == Operation.FINISH) return Component.translatable("status.immersive_brewing.kettle.finishing_physical");
                KettleProcess live = liveProcess();
                if (live != null) return Component.translatable("status.immersive_brewing.kettle.stage." + live.stage().name().toLowerCase(java.util.Locale.ROOT),
                        Component.translatable("heat.immersive_brewing.band." + KettleProcess.heatBand(live.temperature())));
            }
            return switch (ledger.state()) {
                case RESERVED -> Component.translatable("status.immersive_brewing.kettle.reserved");
                case RUNNING -> Component.translatable("status.immersive_brewing.kettle.running");
                case SEALED -> Component.translatable("status.immersive_brewing.kettle.sealed");
                case RECOVERY -> Component.translatable("status.immersive_brewing.recovery", Component.translatable(
                        ledger.recoveryReason() == null ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
            };
        }
        if (anyFinished()) {
            int count = 0;
            ItemStack shown = ItemStack.EMPTY;
            for (int i = 0; i < RACK_SIZE; i++) {
                if (!finished[RACK + i]) continue;
                ItemStack s = inventory.getStackInSlot(RACK + i);
                count += s.getCount();
                if (shown.isEmpty()) shown = s;
            }
            Grade grade = BrewingMetadata.read(shown).map(BrewingMetadata::grade).orElse(Grade.STANDARD);
            boolean full = true;
            for (int i = 0; i < RACK_SIZE; i++) full &= !inventory.getStackInSlot(RACK + i).isEmpty();
            return Component.translatable(full ? "status.immersive_brewing.kettle.finished_full" : "status.immersive_brewing.kettle.finished",
                    count, Component.translatable(grade.key()), shown.getHoverName());
        }
        Rack rack = rack();
        if (rack.problem() != null) {
            return loaded() ? rack.problem() : Component.translatable("status.immersive_brewing.kettle.empty");
        }
        return Component.translatable("status.immersive_brewing.kettle.loaded", rack.servings(), rack.input().getHoverName());
    }

    @Override
    protected boolean litNow() {
        if (ledger != null && ledger.physical()) {
            KettleProcess live = ledger.operation() == Operation.BREW ? liveProcess() : null;
            return ledger.state() == JobState.RUNNING && live != null && live.burner() > 0;
        }
        return ledger != null && (ledger.state() == JobState.RUNNING || ledger.state() == JobState.RESERVED);
    }

    // ---- menu ----

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.immersive_brewing.brewers_kettle");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new KettleMenu(id, inventory, worldPosition);
    }

    // ---- hands-on workshop ----

    @Override
    public WorkshopKind workshopKind() {
        return WorkshopKind.KETTLE;
    }

    /** The live brewing process, decoded on first use; null (and the job held) when its payload can not be read. */
    @Nullable
    public KettleProcess liveProcess() {
        if (ledger == null || !ledger.physical() || ledger.operation() != Operation.BREW) return null;
        if (process == null) {
            try {
                process = KettleProcess.load(ledger.process());
            } catch (RuntimeException e) {
                // Never overwrite a payload we can not read: hold it as it is.
                if (ledger.state() != JobState.RECOVERY) {
                    ledger.hold("recovery.immersive_brewing.process_unreadable");
                    changed();
                }
                return null;
            }
        }
        return process;
    }

    @Nullable
    private FinishingProcess liveFinishing() {
        if (ledger == null || !ledger.physical() || ledger.operation() != Operation.FINISH) return null;
        if (finishingProcess == null) {
            try {
                finishingProcess = FinishingProcess.load(ledger.process());
            } catch (RuntimeException e) {
                if (ledger.state() != JobState.RECOVERY) {
                    ledger.hold("recovery.immersive_brewing.process_unreadable");
                    changed();
                }
                return null;
            }
        }
        return finishingProcess;
    }

    @Override
    protected void flushProcess() {
        if (ledger == null || !ledger.physical()) return;
        if (process != null && ledger.operation() == Operation.BREW) ledger.setProcess(process.save());
        if (finishingProcess != null && ledger.operation() == Operation.FINISH) ledger.setProcess(finishingProcess.save());
    }

    private boolean reservedRackSlot(int rackIndex) {
        KettleProcess live = liveProcess();
        if (live != null) return live.portionAtSlot(rackIndex) >= 0;
        FinishingProcess f = liveFinishing();
        if (f != null) for (int s : f.slots()) if (s == rackIndex) return true;
        return false;
    }

    @Override
    protected List<java.util.UUID> workshopParticipants() {
        KettleProcess live = liveProcess();
        if (live != null) return live.participants();
        if (ledger != null && ledger.brewerId() != null) {
            List<java.util.UUID> ids = new ArrayList<>();
            ids.add(ledger.brewerId());
            if (lastActor != null && !lastActor.equals(ledger.brewerId())) ids.add(lastActor);
            return ids;
        }
        return List.of();
    }

    @Override
    protected void participate(ServerPlayer player) {
        lastActor = player.getUUID();
        KettleProcess live = liveProcess();
        if (live != null) live.participate(player.getUUID());
        if (ledger != null && !player.getUUID().equals(ledger.brewerId())) ledger.addAssistant(player.getGameProfile().getName());
    }

    @Override
    protected void onPhysicalSealed() {
        KettleProcess live = liveProcess();
        if (live != null) live.seal();
        paddleTrack.reset();
        pourQueue = fillQueue = 0;
        queueAt = 0;
        carryPose.clear();
    }

    @Override
    protected void onLeasesLapsed() {
        if (level == null) return;
        long now = level.getGameTime();
        if (leases.holder(WorkshopParts.K_PADDLE, now) == null) paddleTrack.reset();
        // A carried bottle or cup whose holder let go (or went quiet) is back at its place: nothing was transferred.
        carryPose.keySet().removeIf(part -> leases.holder(part, now) == null);
    }

    @Override
    @Nullable
    protected String physicalResumeProblem() {
        if (ledger != null && ledger.operation() == Operation.BREW) {
            KettleProcess live = liveProcess();
            if (live == null) return "recovery.immersive_brewing.process_unreadable";
            if (!live.finalized()) {
                String problem = recoveryProblem();
                if (problem != null) return problem;
                if (!hasPaddle()) return "plan.immersive_brewing.missing.paddle";
            }
        }
        return null;
    }

    @Override
    protected void resumeLegacy(ServerPlayer player) {
        resume(player);
    }

    @Override
    protected void physicalStep(long now) {
        if (ledger == null) return;
        if (ledger.operation() == Operation.FINISH) {
            FinishingProcess f = liveFinishing();
            if (f == null) return;
            f.tick();
            if (f.done()) completeFinishingPhysical(f);
            markWorkshopDirty(false);
            return;
        }
        KettleProcess p = liveProcess();
        if (p == null) return;
        if (queueCooldown > 0) {
            queueCooldown--;
        } else if (pourQueue > 0) {
            // A bottle someone is holding is theirs to tip: the batch pours the others around it.
            int next = nextQueued(p, KettleProcess.Portion.STAGED, now);
            if (next >= 0 && p.pour(next)) {
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.POUR, ledger.jobId(), (int) p.ticks(), p.slotOf(next), 0);
                markWorkshopDirty(true);
            }
            pourQueue = next < 0 ? 0 : pourQueue - 1;
            queueCooldown = POUR_PACE;
            queueAt = pourQueue > 0 ? now + POUR_PACE + 1 : 0;
        } else if (fillQueue > 0) {
            int next = nextQueued(p, KettleProcess.Portion.READY, now);
            if (next < 0 || fill(null, next) != WorkshopResult.ACCEPTED) fillQueue = 0;
            else fillQueue--;
            queueCooldown = FILL_PACE;
            queueAt = fillQueue > 0 ? now + FILL_PACE + 1 : 0;
            if (ledger == null) return;
        }
        boolean paddleHeld = leases.holder(WorkshopParts.K_PADDLE, now) != null;
        double stir = paddleHeld ? paddleTrack.take(now) : 0;
        p.tick(stir, paddleTrack.broadness(), paddleTrack.depth());
        if (p.readyToFinalize()) finalizeBrew(p);
        markWorkshopDirty(false);
    }

    /** The first portion in this state whose bottle nobody is holding, or -1. */
    private int nextQueued(KettleProcess p, KettleProcess.Portion state, long now) {
        for (int i = 0; i < p.portions(); i++) {
            if (p.portion(i) != state) continue;
            if (leases.holder(WorkshopParts.K_RACK + p.slotOf(i), now) != null) continue;
            if (state == KettleProcess.Portion.READY && !inventory.getStackInSlot(RACK + p.slotOf(i)).isEmpty()) continue;
            return i;
        }
        return -1;
    }

    /**
     * Chemical completion (handoff 14): replays every committed step through its adapter for every serving, checks the
     * results against the plan snapshot, freezes quality, fires the result events and awards completion once, and only
     * then exposes ready servings for bottling. A veto or a changed recipe holds the batch without debiting anything.
     */
    private void finalizeBrew(KettleProcess p) {
        JobLedger l = ledger;
        if (l == null || p.finalized()) return;
        String problem = recoveryProblem();
        if (problem != null) {
            holdPhysical(problem);
            return;
        }
        ListTag steps = l.snapshot().getList("steps", Tag.TAG_COMPOUND);
        List<ItemStack> ingredients = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < steps.size(); i++) {
            int units = steps.getCompound(i).getInt("units");
            if (units <= 0 || cursor >= l.reserved().size()) {
                holdPhysical("recovery.immersive_brewing.reagents_missing");
                return;
            }
            ItemStack reagent = l.reserved().get(cursor).stack();
            ingredients.add(KitData.read(reagent).map(KitData::source).orElse(reagent).copyWithCount(1));
            cursor += units;
        }
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack bottle : l.inputs()) {
            ItemStack state = PotionContentsService.canonical(bottle).copyWithCount(1);
            for (int i = 0; i < steps.size(); i++) {
                CompoundTag st = steps.getCompound(i);
                BrewingAdapter adapter = AdapterRegistry.get(new ResourceLocation(st.getString("adapter")), BrewingDefinitions.server());
                ItemStack expected = LedgerCodec.loadStack(st.getCompound("after"));
                ItemStack result = adapter == null ? ItemStack.EMPTY : adapter.commit(state, ingredients.get(i));
                if (!result.isEmpty()) result = preserveContainerStep(st, state, ingredients.get(i), result);
                if (result.isEmpty() || !PotionContentsService.sameCanonical(result, expected)) {
                    holdPhysical("recovery.immersive_brewing.source_changed");
                    return;
                }
                state = result;
            }
            outputs.add(state);
        }
        if (outputs.size() != p.portions()) {
            holdPhysical("recovery.immersive_brewing.state_unreadable");
            return;
        }
        boolean createsEffect = false;
        for (int i = 0; i < steps.size(); i++) createsEffect |= steps.getCompound(i).getBoolean("effect");
        BrewingMetadata inputMeta = l.snapshot().contains("inputMeta", Tag.TAG_COMPOUND)
                ? BrewingMetadata.fromTag(l.snapshot().getCompound("inputMeta")).orElse(null) : null;
        ItemStack canonicalInput = PotionContentsService.canonical(l.inputs().get(0));
        int control = p.finalizeControl();
        QualityInheritance.Outcome outcome = QualityInheritance.brew(canonicalInput, inputMeta, createsEffect, l.extraction(), control,
                l.lineage(), l.brewerId(), l.brewerName());
        int servings = outputs.size();
        ServerPlayer actor = actingPlayer(l);
        BrewResultEvent.Pre pre = new BrewResultEvent.Pre(actor, worldPosition, outputs.get(0), servings, outcome.metadata().extraction(),
                control, outcome.metadata().quality());
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            holdPhysical("recovery.immersive_brewing.vetoed");
            return;
        }
        BrewingMetadata meta = outcome.metadata();
        if (pre.quality() < meta.quality()) meta = meta.withScores(meta.extraction(), meta.control(), pre.quality());

        // ---- the transition: the servings become their finished stacks, still owned by the batch until bottled ----
        List<ItemStack> remainders = ledgerRemainders(l);
        for (int i = 0; i < outputs.size(); i++) {
            ItemStack out = outputs.get(i);
            BrewingMetadata.write(out, meta);
            l.inputs().set(i, out);
        }
        l.reserved().clear(); // consumed by the reaction; their containers are paid below, exactly once
        p.markFinalized();
        for (ItemStack remainder : remainders) addByproduct(remainder);
        wearPaddle();
        lastLineage = meta.lineage();
        ItemStack shown = outputs.get(0).copyWithCount(1);
        String tip = p.tipKey();
        lastResult = new StationResult(Operation.BREW, shown, servings, meta.quality(), meta.extraction(), control, tip, List.of(), l.brewerName());
        flushProcess();
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), (int) p.ticks(), 0, 0);
        markWorkshopDirty(true);

        MinecraftForge.EVENT_BUS.post(new BrewResultEvent.Post(actor, worldPosition, shown, servings, meta.extraction(), control, meta.quality()));
        CompoundTag result = physicalResultTag(p, meta, outcome, shown, servings, l, control, tip);
        if (actor != null) {
            ModAdvancements.milestone(actor, Milestone.FIRST_BREW);
            if (meta.grade().ordinal() >= Grade.FINE.ordinal()) ModAdvancements.milestone(actor, Milestone.FINE_BATCH);
            if (meta.grade() == Grade.MASTERWORK) ModAdvancements.milestone(actor, Milestone.MASTERWORK_BATCH);
            if (servings >= 6) ModAdvancements.milestone(actor, Milestone.SIX_SERVINGS);
            PlayerGuide.recordResult(actor, result);
            int xp = ServerConfig.get(ServerConfig.COMPLETION_EXPERIENCE);
            if (xp > 0 && level instanceof ServerLevel sl) ExperienceOrb.award(sl, actor.position(), xp);
            notice(actor, Component.translatable("workshop.immersive_brewing.kettle.ready", servings, shown.getHoverName(),
                    Component.translatable(meta.grade().key()), Component.translatable(tip)), false);
        }
    }

    /** The player credited with completing this step of work: the last productive actor nearby, else the brewer. */
    @Nullable
    private ServerPlayer actingPlayer(JobLedger l) {
        if (level == null || level.getServer() == null) return null;
        if (lastActor != null) {
            ServerPlayer p = com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), lastActor);
            if (p != null && p.level() == level) return p;
        }
        return l.brewerId() == null ? null : com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), l.brewerId());
    }

    private void holdPhysical(String reason) {
        if (ledger == null) return;
        flushProcess();
        leases.clear();
        ledger.hold(reason);
        changed();
        markWorkshopDirty(true);
    }

    private CompoundTag physicalResultTag(KettleProcess p, BrewingMetadata meta, QualityInheritance.Outcome outcome, ItemStack output, int servings,
                                          JobLedger l, int control, String tip) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "brewing");
        tag.putString("plan", l.snapshot().getString("plan"));
        tag.putString("profile", l.snapshot().getString("profile"));
        tag.putString("workshopProfile", l.snapshot().getString("workshopProfile"));
        tag.put("input", l.snapshot().getList("steps", Tag.TAG_COMPOUND).getCompound(0).getCompound("before").copy());
        tag.put("output", LedgerCodec.saveStack(output));
        tag.putInt("count", servings);
        tag.putInt("E", meta.extraction());
        tag.putInt("C", control);
        tag.putInt("Q", meta.quality());
        tag.putInt("candidate", outcome.candidate());
        tag.putBoolean("capped", outcome.capped());
        tag.putDouble("thermal", p.thermal());
        tag.putDouble("circulation", p.incorporation());
        tag.putDouble("dosing", p.dosing());
        tag.putString("tip", tip);
        tag.put("tipArgs", new ListTag());
        tag.putInt("resumes", l.resumes());
        tag.putBoolean("physical", true);
        ListTag assistants = new ListTag();
        for (String a : l.assistants()) assistants.add(StringTag.valueOf(a));
        tag.put("assistants", assistants);
        return tag;
    }

    /**
     * Fills one reserved bottle from a ready serving (handoff 7, 14): the finished stack moves into its own rack
     * position, the rest stay owned by the batch. An occupied position refuses without debiting anything; the last fill
     * closes the batch.
     */
    private WorkshopResult fill(@Nullable ServerPlayer player, int portion) {
        KettleProcess p = liveProcess();
        if (p == null || ledger == null || !p.finalized()) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.not_ready");
        if (portion < 0 || portion >= p.portions() || p.portion(portion) != KettleProcess.Portion.READY) return WorkshopResult.IGNORED;
        int slot = RACK + p.slotOf(portion);
        if (!inventory.getStackInSlot(slot).isEmpty()) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.slot_occupied");
        if (portion >= ledger.inputs().size()) return WorkshopResult.IGNORED;
        ItemStack out = ledger.inputs().remove(portion);
        p.removePortion(portion);
        putDirect(slot, out);
        finished[slot] = true;
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.FILL, ledger.jobId(), (int) p.ticks(), slot - RACK, 0);
        if (p.portions() == 0) {
            closePhysical();
        } else {
            flushProcess();
            changed();
        }
        markWorkshopDirty(true);
        return WorkshopResult.ACCEPTED;
    }

    /** Ends a job whose every serving was bottled: the ledger closes, the rack holds ordinary finished stacks. */
    private void closePhysical() {
        ledger = null;
        process = null;
        finishingProcess = null;
        pourQueue = fillQueue = 0;
        queueAt = 0;
        paddleTrack.reset();
        leases.clear();
        carryPose.clear();
        setVisual(StationVisual.IDLE);
        changed();
        markWorkshopDirty(true);
    }

    /** Container finishing commit: QuickFinishing's proof per bottle, each back to its own rack position. */
    private void completeFinishingPhysical(FinishingProcess f) {
        JobLedger l = ledger;
        if (l == null) return;
        String problem = recoveryProblem();
        if (problem != null) {
            holdPhysical(problem);
            return;
        }
        ListTag steps = l.snapshot().getList("steps", Tag.TAG_COMPOUND);
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack original : l.inputs()) {
            ItemStack state = original.copyWithCount(1);
            int cursor = 0;
            for (int i = 0; i < steps.size(); i++) {
                CompoundTag step = steps.getCompound(i);
                if (cursor >= l.reserved().size()) {
                    holdPhysical("recovery.immersive_brewing.reagents_missing");
                    return;
                }
                ItemStack held = l.reserved().get(cursor).stack();
                cursor += step.getInt("units");
                ItemStack reagent = KitData.read(held).map(KitData::source).orElse(held).copyWithCount(1);
                BrewingAdapter adapter = AdapterRegistry.get(new ResourceLocation(step.getString("adapter")), BrewingDefinitions.server());
                ItemStack actual = adapter == null ? ItemStack.EMPTY : adapter.commit(PotionContentsService.canonical(state), reagent);
                if (actual.isEmpty()) {
                    holdPhysical("recovery.immersive_brewing.source_changed");
                    return;
                }
                actual = com.otectus.immersivebrewing.recipe.QuickFinishing.preserve(state, reagent, actual);
                if (!PotionContentsService.sameCanonical(actual, LedgerCodec.loadStack(step.getCompound("after")))
                        || !java.util.Objects.equals(PotionContentsService.canonicalTag(state), PotionContentsService.canonicalTag(actual))) {
                    holdPhysical("recovery.immersive_brewing.source_changed");
                    return;
                }
                state = actual;
            }
            outputs.add(state);
        }
        List<ItemStack> returns = ledgerRemainders(l);
        if (!byproductRoom(returns)) {
            holdPhysical("recovery.immersive_brewing.output_blocked");
            return;
        }
        int[] slots = f.slots();
        for (int i = 0; i < outputs.size(); i++) {
            int slot = RACK + (i < slots.length ? slots[i] : i);
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                holdPhysical("recovery.immersive_brewing.output_blocked");
                return;
            }
        }
        ledger = null;
        finishingProcess = null;
        for (int i = 0; i < outputs.size(); i++) {
            int slot = RACK + (i < slots.length ? slots[i] : i);
            putDirect(slot, outputs.get(i));
            finished[slot] = true;
        }
        for (ItemStack remainder : returns) addByproduct(remainder);
        leases.clear();
        setVisual(StationVisual.IDLE);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        // Finishing grants no brewing score, milestone, XP, or replayable completion event.
    }

    @Override
    @Nullable
    protected StationVisual physicalVisual() {
        KettleProcess p = liveProcess();
        if (p == null) return null;
        int[] colors = ledger == null ? new int[] {0x385DC6} : StationPresentations.stageColors(ledger);
        int current = colors[Math.max(0, Math.min(colors.length - 1, Math.max(0, p.doses() - 1)))];
        int next = colors[Math.max(0, Math.min(colors.length - 1, p.doses()))];
        int signature = switch (p.setup().family()) {
            case GENERIC -> 0;
            case LIGHT_FOAM -> 3;
            case DENSE_SEDIMENT -> 4;
            case DELICATE_SHIMMER -> 5;
            case MAGMA_STREAKS -> 6;
            case SUSPENDED_BUBBLES -> 7;
            case DARK_WHORLS -> 8;
            case ALIGNING_GLINTS -> 9;
            case FLOATING_MOTES -> 10;
        };
        StationVisual base = new StationVisual(Math.min(2, p.doses()), signature, (float) p.temperature(), (float) p.circulation(), (float) p.trouble(),
                (float) p.progress(), true);
        return base.details(StationVisual.Mortar.EMPTY, new StationVisual.Kettle(p.burner(), (float) p.flame(), p.doses(), 0, current, next, (float) p.progress()),
                StationVisual.Alembic.EMPTY, (int) p.ticks(), Operation.BREW);
    }

    @Override
    protected int physicalComparator() {
        KettleProcess live = liveProcess();
        if (live == null) return 8;
        return switch (live.stage()) {
            case READY -> 14;
            case CHARGING, HEATING, WAITING_DOSE, RESTING -> 9;
            case REACTING -> 8;
        };
    }

    @Override
    protected InteractionResult directInsert(Player player, InteractionHand hand, ItemStack held, @Nullable WorkshopPart part, int limit) {
        boolean client = level == null || level.isClientSide;
        if (isBottle(held) && !held.is(ModTags.CARRIER_EXCLUDED)) {
            if (client) return InteractionResult.SUCCESS;
            if (ledger != null) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.rack_reserved"), true);
                return InteractionResult.CONSUME;
            }
            int target = part != null && part.id() >= WorkshopParts.K_RACK && part.id() < WorkshopParts.K_RACK + RACK_SIZE
                    && inventory.getStackInSlot(RACK + part.id() - WorkshopParts.K_RACK).isEmpty() ? RACK + part.id() - WorkshopParts.K_RACK : -1;
            for (int i = 0; i < RACK_SIZE && target < 0; i++) if (inventory.getStackInSlot(RACK + i).isEmpty()) target = RACK + i;
            if (target < 0) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.rack_full"), true);
                return InteractionResult.CONSUME;
            }
            putDirect(target, held.copyWithCount(1));
            finished[target] = false;
            if (!player.getAbilities().instabuild) held.shrink(1);
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0, WorkshopParts.K_RACK + target - RACK, 1);
            changed();
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        if (held.is(ModTags.BURNER_FUEL)) {
            if (client) return InteractionResult.SUCCESS;
            ItemStack fuel = inventory.getStackInSlot(FUEL);
            if (!fuel.isEmpty() && !ItemStack.isSameItemSameTags(fuel, held) || fuel.getCount() >= fuel.getMaxStackSize()) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.burner_full"), true);
                return InteractionResult.CONSUME;
            }
            int move = Math.min(Math.max(1, limit), Math.min(held.getCount(), held.getMaxStackSize() - fuel.getCount()));
            putDirect(FUEL, held.copyWithCount(fuel.getCount() + move));
            if (!player.getAbilities().instabuild) held.shrink(move);
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0, WorkshopParts.K_FUEL, move);
            changed();
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        if (held.is(ModTags.BREWING_PADDLES)) {
            if (client) return InteractionResult.SUCCESS;
            if (!inventory.getStackInSlot(PADDLE).isEmpty() || paddleInUse()) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.paddle_present"), true);
                return InteractionResult.CONSUME;
            }
            putDirect(PADDLE, held.copyWithCount(1));
            if (!player.getAbilities().instabuild) held.shrink(1);
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0, WorkshopParts.K_PADDLE, 1);
            changed();
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        if (isIngredient(held)) {
            if (client) return InteractionResult.SUCCESS;
            if (ledger != null) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.tray_reserved"), true);
                return InteractionResult.CONSUME;
            }
            int preferred = part != null && part.id() >= WorkshopParts.K_TRAY && part.id() < WorkshopParts.K_TRAY + TRAY_SIZE ? TRAY + part.id() - WorkshopParts.K_TRAY : -1;
            int offered = Math.min(held.getCount(), Math.max(1, limit));
            ItemStack left = held.copyWithCount(offered);
            int landed = -1;
            if (preferred >= 0) {
                int before = left.getCount();
                left = inventory.insertItem(preferred, left, false);
                if (left.getCount() < before) landed = preferred;
            }
            for (int i = 0; i < TRAY_SIZE && !left.isEmpty(); i++) {
                int before = left.getCount();
                left = inventory.insertItem(TRAY + i, left, false);
                if (left.getCount() < before && landed < 0) landed = TRAY + i;
            }
            int moved = offered - left.getCount();
            if (moved == 0) {
                notice(player, Component.translatable("workshop.immersive_brewing.kettle.tray_full"), true);
                return InteractionResult.CONSUME;
            }
            if (!player.getAbilities().instabuild) held.shrink(moved);
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0,
                    landed < 0 ? WorkshopParts.K_TRAY : WorkshopParts.K_TRAY + landed - TRAY, moved);
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** An item the tray takes directly: a registered brewing ingredient, a prepared kit, or a profiled reagent. */
    public static boolean isIngredient(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof PotionItem || stack.is(ModTags.BREWING_PADDLES) || stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        if (KitData.read(stack).isPresent()) return true;
        if (BrewingRecipeRegistry.isValidIngredient(stack.copyWithCount(1))) return true;
        return BrewingDefinitions.server().reagentProfile(stack.getItem(), null, null) != null;
    }

    @Override
    protected WorkshopResult act(ServerPlayer player, WorkshopIntent intent, long now) {
        int part = intent.part();
        boolean physical = ledger != null && ledger.physical();
        boolean running = physical && ledger.state() == JobState.RUNNING;
        switch (intent.action()) {
            case BOOK -> {
                // The optional expanded planner (searchable catalog); ordinary batches start from the recipe page.
                if (ledger != null && ledger.legacy()) return WorkshopResult.refuse("workshop.immersive_brewing.legacy.finish_first");
                com.otectus.immersivebrewing.recipe.CatalogSearch.submit(player,
                        new com.otectus.immersivebrewing.network.packet.CatalogQueryC2S(worldPosition, 0, "", List.of(), 0, 0, false, 3));
                return WorkshopResult.ACCEPTED;
            }
            case SETUP_PAGE -> {
                if (ledger != null) return WorkshopResult.IGNORED;
                setup.refresh();
                if (!setup.turnTo(intent.argument())) return WorkshopResult.IGNORED;
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case BEGIN -> {
                // Sets out the page's batch without tipping a bottle yet (the keyboard's single-key path).
                return ledger == null ? acceptSetup(player, intent, -1) : WorkshopResult.IGNORED;
            }
            case SEAL -> {
                if (!running) return WorkshopResult.IGNORED;
                sealPhysical(player, "workshop.immersive_brewing.seal.manual");
                return WorkshopResult.ACCEPTED;
            }
            case UNSEAL -> {
                return physical ? unsealPhysical(player) : WorkshopResult.IGNORED;
            }
            case CANCEL -> {
                return cancelPhysical(player);
            }
            case INSERT -> {
                ItemStack held = player.getItemInHand(intent.hand());
                if (held.isEmpty()) return WorkshopResult.IGNORED;
                InteractionResult r = directInsert(player, intent.hand(), held, WorkshopParts.get(WorkshopKind.KETTLE, part), intent.batch() ? 64 : 1);
                return r == InteractionResult.PASS ? WorkshopResult.refuse("workshop.immersive_brewing.refuse.not_here") : WorkshopResult.ACCEPTED;
            }
            case TAKE -> {
                return take(player, part, intent.batch());
            }
            case STOP -> {
                pourQueue = fillQueue = 0;
                queueAt = 0;
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            default -> { }
        }
        // A bottle or dose cup lifted over the apparatus (handoff 7.3): a presentation lease others can see. Nothing is
        // transferred until it is tipped; letting go, or going quiet, returns it to its place.
        if (carryPart(part) && (intent.action() == WorkshopAction.GRAB || intent.action() == WorkshopAction.SAMPLE
                || intent.action() == WorkshopAction.RELEASE)) {
            return carry(player, intent, now);
        }
        // Idle: tipping a lifted bottle accepts the displayed recipe page and pours that bottle (handoff 6.2 step 6).
        if (ledger == null && intent.action() == WorkshopAction.POUR && intent.setup() && rackPart(part)) {
            WorkshopResult r = acceptSetup(player, intent, intent.batch() ? -1 : part - WorkshopParts.K_RACK);
            releaseCarry(part, player);
            KettleProcess started = r.accepted() && intent.batch() ? liveProcess() : null;
            if (started != null) {
                // The batch shortcut: set out, then every bottle in turn at the batch pace.
                pourQueue = started.count(KettleProcess.Portion.STAGED);
                queueCooldown = POUR_LEAD;
                queueAt = now + POUR_LEAD + 1;
                markWorkshopDirty(true);
            }
            return r;
        }
        if (ledger != null && ledger.physical() && ledger.state() == JobState.SEALED) return WorkshopResult.refuse("workshop.immersive_brewing.sealed.open_lid");
        if (ledger != null && ledger.state() == JobState.RECOVERY) {
            return WorkshopResult.refuse("notice.immersive_brewing.held", Component.translatable(ledger.recoveryReason() == null
                    ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
        }
        if (!running) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.no_batch");
        if (ledger.operation() == Operation.FINISH) return finishingAct(player, intent, now);
        KettleProcess p = liveProcess();
        if (p == null) return WorkshopResult.refuse("recovery.immersive_brewing.process_unreadable");
        switch (intent.action()) {
            case POUR -> {
                if (intent.batch() || !rackPart(part) && intent.argument() < 0) {
                    pourQueue = p.count(KettleProcess.Portion.STAGED);
                    // A short lead lets every tracking client lift the first bottle so it tips as this commits it.
                    queueCooldown = POUR_LEAD;
                    queueAt = now + POUR_LEAD + 1;
                    participate(player);
                    markWorkshopDirty(true);
                    return pourQueue > 0 ? WorkshopResult.ACCEPTED : WorkshopResult.IGNORED;
                }
                // A serving is named by its rack position, never by an index that shifts as others are bottled.
                if (!rackPart(part)) return WorkshopResult.IGNORED;
                if (leases.heldByOther(part, player.getUUID(), now)) return heldBy(part, now);
                int index = p.portionAtSlot(part - WorkshopParts.K_RACK);
                if (index < 0 || p.portion(index) != KettleProcess.Portion.STAGED) {
                    releaseCarry(part, player);
                    return WorkshopResult.IGNORED;
                }
                p.pour(index);
                releaseCarry(part, player);
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.POUR, ledger.jobId(), (int) p.ticks(), p.slotOf(index), 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case DOSE -> {
                int step = part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS ? part - WorkshopParts.K_CUP : intent.argument();
                int cup = WorkshopParts.K_CUP + step;
                if (leases.heldByOther(cup, player.getUUID(), now)) return heldBy(cup, now);
                String problem = p.doseProblem(step);
                if (problem != null) {
                    releaseCarry(cup, player);
                    return WorkshopResult.refuse(problem);
                }
                p.dose(step);
                releaseCarry(cup, player);
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.ADDITION, ledger.jobId(), (int) p.ticks(), step, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case SET_CONTROL -> {
                if (part != WorkshopParts.K_BURNER) return WorkshopResult.IGNORED;
                if (leases.heldByOther(WorkshopParts.K_BURNER, player.getUUID(), now)) return heldBy(WorkshopParts.K_BURNER, now);
                if (!p.setBurner(intent.argument())) return WorkshopResult.IGNORED;
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.VALVE, ledger.jobId(), (int) p.ticks(), p.burner(), 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case GRAB -> {
                if (part == WorkshopParts.K_BURNER) {
                    var other = leases.acquire(WorkshopParts.K_BURNER, player.getUUID(), player.getGameProfile().getName(), now);
                    return other == null ? WorkshopResult.ACCEPTED : heldBy(WorkshopParts.K_BURNER, now);
                }
                if (part != WorkshopParts.K_PADDLE) return WorkshopResult.IGNORED;
                if (!hasPaddle()) return WorkshopResult.refuse("plan.immersive_brewing.missing.paddle");
                var other = leases.acquire(WorkshopParts.K_PADDLE, player.getUUID(), player.getGameProfile().getName(), now);
                if (other != null) return heldBy(WorkshopParts.K_PADDLE, now);
                paddleTrack.reset();
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.GRAB, ledger.jobId(), 0, part, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case RELEASE -> {
                if (!leases.release(part, player.getUUID())) return WorkshopResult.IGNORED;
                if (part == WorkshopParts.K_PADDLE) {
                    paddleTrack.reset();
                    effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.DROP, ledger.jobId(), 0, part, 0);
                }
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case SAMPLE -> {
                if (part != WorkshopParts.K_PADDLE || !leases.holds(WorkshopParts.K_PADDLE, player.getUUID(), now)) return WorkshopResult.IGNORED;
                paddleTrack.sample(intent.x(), intent.z(), intent.contact(), intent.deep(), now);
                if (intent.contact()) participate(player);
                markWorkshopDirty(false);
                return WorkshopResult.ACCEPTED;
            }
            case FILL -> {
                if (!p.finalized()) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.not_ready");
                // The batch action fills every ready bottle in turn; the spout alone opens over the next one.
                if (intent.batch()) {
                    fillQueue = p.count(KettleProcess.Portion.READY);
                    queueCooldown = FILL_LEAD;
                    queueAt = now + FILL_LEAD + 1;
                    markWorkshopDirty(true);
                    return fillQueue > 0 ? WorkshopResult.ACCEPTED : WorkshopResult.IGNORED;
                }
                int index = -1;
                if (rackPart(part)) {
                    index = p.portionAtSlot(part - WorkshopParts.K_RACK);
                } else {
                    // The next bottle that can take it; an occupied one is only chosen (and refused, with the reason) if no other is free.
                    int firstReady = -1;
                    for (int i = 0; i < p.portions() && index < 0; i++) {
                        if (p.portion(i) != KettleProcess.Portion.READY) continue;
                        if (firstReady < 0) firstReady = i;
                        if (inventory.getStackInSlot(RACK + p.slotOf(i)).isEmpty()) index = i;
                    }
                    if (index < 0) index = firstReady;
                }
                return fill(player, index);
            }
            default -> {
                return WorkshopResult.IGNORED;
            }
        }
    }

    private static boolean rackPart(int part) {
        return part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + RACK_SIZE;
    }

    private static boolean cupPart(int part) {
        return part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS;
    }

    /** The parts that can be lifted and carried: the bottles on the rail and the dose cups. */
    public static boolean carryPart(int part) {
        return rackPart(part) || cupPart(part);
    }

    /** Lift, move or let go of a bottle or dose cup (see {@link #act}). */
    private WorkshopResult carry(ServerPlayer player, WorkshopIntent intent, long now) {
        int part = intent.part();
        switch (intent.action()) {
            case GRAB -> {
                String problem = carryProblem(part, now);
                if (problem != null) {
                    KettleSetupService.Preview page = setup.preview();
                    if (problem.isEmpty() && page.problem() != null) return WorkshopResult.refuse(page.problem());
                    return WorkshopResult.refuse(problem.isEmpty() ? "workshop.immersive_brewing.setup.not_ready" : problem);
                }
                var other = leases.acquire(part, player.getUUID(), player.getGameProfile().getName(), now);
                if (other != null) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.held_by", other.name());
                carryPose.put(part, new float[] {(float) Mth.clamp(intent.x(), -1.5, 1.5), (float) Mth.clamp(intent.z(), -1.5, 1.5)});
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.GRAB, ledger == null ? StationVisual.NONE : ledger.jobId(), 0, part, 1);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case SAMPLE -> {
                if (!leases.holds(part, player.getUUID(), now)) return WorkshopResult.IGNORED;
                carryPose.put(part, new float[] {(float) Mth.clamp(intent.x(), -1.5, 1.5), (float) Mth.clamp(intent.z(), -1.5, 1.5)});
                markWorkshopDirty(false);
                return WorkshopResult.ACCEPTED;
            }
            default -> {
                if (!releaseCarry(part, player)) return WorkshopResult.IGNORED;
                return WorkshopResult.ACCEPTED;
            }
        }
    }

    /**
     * Why this part can not be lifted now: a translation key, the empty string for "the recipe page is not ready", or
     * null when it can. Lifting never reserves anything; this only keeps a player from carrying what can not be tipped.
     */
    @Nullable
    private String carryProblem(int part, long now) {
        if (rackPart(part)) {
            int slot = part - WorkshopParts.K_RACK;
            if (ledger == null) {
                if (inventory.getStackInSlot(RACK + slot).isEmpty() || finished[RACK + slot]) return "workshop.immersive_brewing.kettle.bottle.nothing";
                setup.refresh();
                return setup.preview().acceptable() ? null : "";
            }
            KettleProcess p = liveProcess();
            if (p == null || ledger.state() != JobState.RUNNING) return "workshop.immersive_brewing.kettle.no_batch";
            int index = p.portionAtSlot(slot);
            return index >= 0 && p.portion(index) == KettleProcess.Portion.STAGED ? null : "workshop.immersive_brewing.kettle.bottle.nothing";
        }
        if (cupPart(part)) {
            int step = part - WorkshopParts.K_CUP;
            if (ledger == null || ledger.state() != JobState.RUNNING) return "workshop.immersive_brewing.kettle.no_batch";
            if (ledger.operation() == Operation.FINISH) {
                FinishingProcess f = liveFinishing();
                return f == null ? "recovery.immersive_brewing.process_unreadable" : f.doseProblem(step);
            }
            KettleProcess p = liveProcess();
            return p == null ? "recovery.immersive_brewing.process_unreadable" : p.doseProblem(step);
        }
        return "workshop.immersive_brewing.refuse.not_here";
    }

    /** Lets go of a carried part held by this player; true when there was one. */
    private boolean releaseCarry(int part, ServerPlayer player) {
        if (!leases.release(part, player.getUUID())) return false;
        carryPose.remove(part);
        markWorkshopDirty(true);
        return true;
    }

    private WorkshopResult heldBy(int part, long now) {
        var lease = leases.holder(part, now);
        return WorkshopResult.refuse("workshop.immersive_brewing.refuse.held_by", lease == null ? "?" : lease.name());
    }

    private WorkshopResult finishingAct(ServerPlayer player, WorkshopIntent intent, long now) {
        FinishingProcess f = liveFinishing();
        if (f == null) return WorkshopResult.refuse("recovery.immersive_brewing.process_unreadable");
        int part = intent.part();
        switch (intent.action()) {
            case DOSE -> {
                int step = part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS ? part - WorkshopParts.K_CUP : intent.argument();
                int cup = WorkshopParts.K_CUP + step;
                if (leases.heldByOther(cup, player.getUUID(), now)) return heldBy(cup, now);
                String problem = f.doseProblem(step);
                if (problem != null) {
                    releaseCarry(cup, player);
                    return WorkshopResult.refuse(problem);
                }
                f.dose(step);
                releaseCarry(cup, player);
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.ADDITION, ledger.jobId(), 0, step, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case SET_CONTROL -> {
                if (part != WorkshopParts.K_LID) return WorkshopResult.IGNORED;
                boolean close = intent.argument() != 0;
                if (close && f.doses() < f.steps()) return WorkshopResult.refuse("workshop.immersive_brewing.finishing.dose_first");
                if (!f.setClosed(close)) return WorkshopResult.IGNORED;
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.SEAL, ledger.jobId(), 0, close ? 1 : 0, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            default -> {
                return WorkshopResult.IGNORED;
            }
        }
    }

    /** Releases a batch before any irreversible step (no dose tipped): every staged resource returns exactly. */
    private WorkshopResult cancelPhysical(ServerPlayer player) {
        if (ledger == null || !ledger.physical() || ledger.state() == JobState.RECOVERY) return WorkshopResult.IGNORED;
        if (ledger.operation() == Operation.BREW) {
            KettleProcess p = liveProcess();
            if (p == null || p.doses() > 0 || p.finalized()) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.cancel_too_late");
        } else {
            FinishingProcess f = liveFinishing();
            if (f == null || f.doses() > 0) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.cancel_too_late");
        }
        cancelReservation();
        notice(player, Component.translatable("notice.immersive_brewing.workshop.released"), false);
        return WorkshopResult.ACCEPTED;
    }

    /** Takes a public stack shown on a part into the player's inventory (exact stack, nothing dropped). */
    private WorkshopResult take(ServerPlayer player, int part, boolean batch) {
        List<Integer> slots = new ArrayList<>();
        if (part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + RACK_SIZE) {
            int slot = RACK + part - WorkshopParts.K_RACK;
            if (batch) {
                ItemStack template = inventory.getStackInSlot(slot);
                for (int i = 0; i < RACK_SIZE; i++) {
                    ItemStack s = inventory.getStackInSlot(RACK + i);
                    if (!s.isEmpty() && finished[RACK + i] == finished[slot] && ItemStack.isSameItemSameTags(s, template)) slots.add(RACK + i);
                }
            } else slots.add(slot);
        } else if (part >= WorkshopParts.K_TRAY && part < WorkshopParts.K_TRAY + TRAY_SIZE) {
            if (ledger != null) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.tray_reserved");
            slots.add(TRAY + part - WorkshopParts.K_TRAY);
        } else if (part == WorkshopParts.K_BYPRODUCTS) {
            for (int i = 0; i < BYPRODUCT_SIZE; i++) slots.add(BYPRODUCT + i);
        } else if (part == WorkshopParts.K_PADDLE) {
            if (paddleInUse()) return WorkshopResult.refuse("workshop.immersive_brewing.kettle.paddle_in_use");
            slots.add(PADDLE);
        } else if (part == WorkshopParts.K_FUEL) {
            slots.add(FUEL);
        } else {
            return WorkshopResult.IGNORED;
        }
        int moved = 0;
        for (int slot : slots) {
            ItemStack s = inventory.getStackInSlot(slot);
            if (s.isEmpty() || !canTake(slot)) continue;
            if (!com.otectus.immersivebrewing.interaction.RackWorkshop.fits(player.getInventory(), s)) {
                if (moved == 0) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.inventory_full");
                break;
            }
            ItemStack taken = s.copy();
            putDirect(slot, ItemStack.EMPTY);
            finished[slot] = false;
            moved += com.otectus.immersivebrewing.interaction.RackWorkshop.give(player, taken);
        }
        if (moved == 0) return WorkshopResult.IGNORED;
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COLLECT, StationVisual.NONE, 0, 0, 0);
        markWorkshopDirty(true);
        return WorkshopResult.ACCEPTED;
    }

    @Override
    protected void writeToolPose(int part, CompoundTag tag) {
        if (part == WorkshopParts.K_PADDLE) {
            tag.putFloat("x", (float) paddleTrack.u());
            tag.putFloat("z", (float) paddleTrack.v());
            tag.putBoolean("contact", paddleTrack.contact());
            tag.putBoolean("deep", paddleTrack.deep());
            return;
        }
        float[] pose = carryPose.get(part);
        if (pose != null) {
            // A lifted bottle or cup: where its holder has it over the apparatus (observers draw it there).
            tag.putFloat("x", pose[0]);
            tag.putFloat("z", pose[1]);
            tag.putBoolean("carry", true);
        }
    }

    @Override
    protected void writeWorkshopSnapshot(CompoundTag t) {
        t.putBoolean("paddle", hasPaddle());
        t.putInt("fuel", fuelCharges + inventory.getStackInSlot(FUEL).getCount() * ServerConfig.fuelChargesPerBlazePowder());
        if (pourQueue > 0 || fillQueue > 0) {
            // A batch transfer in progress: clients time the next bottle's lift so it tips as the server commits it.
            t.putByte("queue", (byte) (pourQueue > 0 ? 1 : 2));
            t.putByte("queued", (byte) Math.min(RACK_SIZE, Math.max(pourQueue, fillQueue)));
            t.putLong("queueAt", queueAt > 0 ? queueAt : level == null ? 0 : level.getGameTime() + queueCooldown);
        }
        if (ledger == null) {
            setup.write(t);
            return;
        }
        CompoundTag output = LedgerCodec.saveStack(ledger.outputSnapshot().copyWithCount(1));
        output.remove("FullCount");
        t.put("jobOutput", output);
        String presentation = ledger.snapshot().getString("presentation");
        if (!presentation.isEmpty()) t.putString("presentation", presentation);
        ListTag steps = ledger.snapshot().getList("steps", Tag.TAG_COMPOUND);
        ListTag doseItems = new ListTag();
        for (int i = 0; i < Math.min(WorkshopParts.MAX_CUPS, steps.size()); i++) doseItems.add(StringTag.valueOf(steps.getCompound(i).getString("reagent")));
        t.put("doseItems", doseItems);
        t.putIntArray("stageColors", StationPresentations.stageColors(ledger));
        ListTag portions = new ListTag();
        for (int i = 0; i < Math.min(RACK_SIZE, ledger.inputs().size()); i++) {
            CompoundTag stack = LedgerCodec.saveStack(ledger.inputs().get(i).copyWithCount(1));
            stack.remove("FullCount");
            portions.add(stack);
        }
        t.put("portionItems", portions);
        if (ledger.operation() == Operation.BREW) {
            KettleProcess p = liveProcess();
            if (p != null) {
                CompoundTag k = new CompoundTag();
                p.writeSnapshot(k);
                t.put("kettle", k);
            }
        } else if (ledger.operation() == Operation.FINISH) {
            FinishingProcess f = liveFinishing();
            if (f != null) {
                CompoundTag k = new CompoundTag();
                f.writeSnapshot(k);
                t.put("finishing", k);
            }
        }
    }

    // ---- fluid capability ----

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER && !remove && side != Direction.DOWN) return fluid.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        fluid.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        fluid = LazyOptional.of(WaterHandler::new);
    }

    /**
     * Water in, never anything out (spec 5.1): each 250 mB fills one real empty bottle in an idle rack; anything that does
     * not fill a whole bottle is refused, so simulation and execution always agree. It reports no tanks: the Kettle stores
     * no water, it only fills bottles, and overlays must not show an empty reservoir.
     */
    private final class WaterHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 0;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return 0;
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return isWater(stack);
        }

        private static boolean isWater(FluidStack stack) {
            return stack.getFluid().isSame(net.minecraft.world.level.material.Fluids.WATER) && !stack.hasTag();
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty() || !isWater(resource)) return 0;
            int bottles = resource.getAmount() / MB_PER_BOTTLE;
            if (bottles <= 0) return 0;
            int filled = fillWater(bottles, action.simulate());
            return filled * MB_PER_BOTTLE;
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("LastLineage", lastLineage);
    }

    /** The apparatus's real extent for culling: the bottle rail reaches toward the player, the paddle rises above. */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition.getX() - 0.25, worldPosition.getY(), worldPosition.getZ() - 0.25,
                worldPosition.getX() + 1.25, worldPosition.getY() + 1.25, worldPosition.getZ() + 1.25);
    }

    /** Every staged change (rail, tray, fuel, paddle, by-products) may change the recipe page. */
    @Override
    public void changed() {
        super.changed();
        setup.markDirty();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("LastLineage", Tag.TAG_LONG)) lastLineage = tag.getLong("LastLineage");
        if (tag.contains("Ledger", Tag.TAG_COMPOUND) || tag.contains("Inventory", Tag.TAG_COMPOUND) && level != null && !level.isClientSide) {
            process = null;
            finishingProcess = null;
            paddleTrack.reset();
        }
    }

    @Override
    protected void afterRecoveryInstalled() {
        process = null;
        finishingProcess = null;
        paddleTrack.reset();
        leases.clear();
        super.afterRecoveryInstalled();
        markWorkshopDirty(true);
    }
}
