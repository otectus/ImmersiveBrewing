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
        // A resumed count-in still has a SEALED ledger, but already owns the paddle through its controller lock.
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
        return isRack(slot) && ledger == null && finished[slot];
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
        KettleParams params=KettleSetup.build(setup,0,ServerConfig.get(ServerConfig.MAX_BREW_DURATION_SECONDS),KettleSetup.Assist.NONE);
        boolean ready=check.missing()==null && hasPaddle() && rack().problem()==null && rack().servings()==servings;
        boolean finishReady = finishing && check.missing()==null && rack(true).problem()==null && rack(true).servings()==servings;
        return new PlanListS2C.Entry(plan.key(),plan.output().copy(),steps,check.costs(),check.fuelNeeded(),check.fuelAvailable(),
                check.remainders(),ready,check.missing(),plan.contextual(),KettleSetup.tier(setup),
                Math.round(params.totalSteps()/20F),PotionContentsService.durationCanScale(plan.output(),BrewingDefinitions.server()),plan.servingLimit(),expectedExtraction(plan,servings),
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

    private boolean hasPaddle() {
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
                KettleParams params = KettleSetup.build(setup, 0, ServerConfig.get(ServerConfig.MAX_BREW_DURATION_SECONDS), KettleSetup.Assist.NONE);
                boolean effects = AdapterRegistry.getAny(plan.steps().get(plan.steps().size() - 1).adapter()) != null
                        && AdapterRegistry.getAny(plan.steps().get(plan.steps().size() - 1).adapter()).contentsPolicy(plan.output()) == BrewingAdapter.ContentsPolicy.EFFECTS;
                entries.add(new PlanListS2C.Entry(plan.key(), plan.output().copy(), steps, check.costs(), check.fuelNeeded(), check.fuelAvailable(),
                        check.remainders(), check.missing() == null, check.missing(), plan.contextual(), tier,
                        Math.round(params.totalSteps() / 20F), effects, plan.servingLimit(), expectedExtraction(plan, rack.servings())));
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
        if (level == null || level.getServer() == null) return;
        if (ServerConfig.disabled()) {
            notice(player, Component.translatable("notice.immersive_brewing.disabled"), true);
            return;
        }
        if (ledger != null || SessionManager.stationBusy(this)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        if (SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.already_working"), true);
            return;
        }
        Rack rack = rack(finishing);
        if (rack.problem() != null) {
            notice(player, rack.problem(), true);
            return;
        }
        if (rack.servings() > ServerConfig.maxBatchServings()) {
            notice(player, Component.translatable("plan.immersive_brewing.missing.servings", ServerConfig.maxBatchServings()), true);
            return;
        }
        BrewPlan plan = PlanIndex.get(level.getServer().getRecipeManager()).resolve(key, rack.input(), trayItems());
        if (plan == null) {
            notice(player, Component.translatable("notice.immersive_brewing.plan_invalid"), true);
            sendPlans(player);
            return;
        }
        if (finishing && (!ServerConfig.get(ServerConfig.QUICK_FINISHING) || !com.otectus.immersivebrewing.recipe.QuickFinishing.eligible(plan))) {
            notice(player,Component.translatable("finishing.immersive_brewing.ineligible"),true); return;
        }
        Check check = check(plan, rack.servings(),new TrayStock(),!finishing);
        if (check.missing() != null) {
            notice(player, check.missing(), true);
            return;
        }
        if (MinecraftForge.EVENT_BUS.post(new BrewPlanEvent(player, worldPosition, key.serialize(), rack.input(), plan.output(), rack.servings(), false))) {
            notice(player, Component.translatable("notice.immersive_brewing.plan_refused"), true);
            return;
        }
        Selection selection = select(plan, rack.servings(), false);
        if (selection == null) {
            notice(player, Component.translatable("notice.immersive_brewing.plan_invalid"), true);
            return;
        }
        if (!byproductRoom(owedRemainders(selectionStacks(selection)))) {
            notice(player, Component.translatable("plan.immersive_brewing.missing.byproduct_room"), true);
            return;
        }
        BrewingDefinitions defs = BrewingDefinitions.server();
        boolean assist = ServerConfig.get(ServerConfig.ALLOW_ASSISTED_CONTROLS);
        KettleSetup.Assist approved = assist ? new KettleSetup.Assist(Math.min(assistTime, ServerConfig.get(ServerConfig.MAX_ASSIST_TIME_MULTIPLIER)),
                Math.min(assistBand, ServerConfig.get(ServerConfig.MAX_ASSIST_BAND_MULTIPLIER))) : KettleSetup.Assist.NONE;
        long seed = ((ServerLevel) level).getRandom().nextLong();
        KettleParams params = KettleSetup.build(setupPlan(plan, rack.servings(), defs), seed, ServerConfig.get(ServerConfig.MAX_BREW_DURATION_SECONDS), approved);

        // ---- reserve: every owned stack moves into the ledger in this one transition ----
        List<ItemStack> bottles = new ArrayList<>();
        for (int i = 0; i < RACK_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(RACK + i);
            if (s.isEmpty()) continue;
            bottles.add(s.copy());
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
        if(finishing) snapshot.putInt("duration",20*ServerConfig.get(ServerConfig.FINISHING_SECONDS));
        ledger = new JobLedger(java.util.UUID.randomUUID(), finishing?Operation.FINISH:Operation.BREW, finishing?JobState.RUNNING:JobState.RESERVED, bottles, reserved, needed, snapshot,
                plan.output().copy(), params.encode(), new long[0], seed, selection.extraction(), player.getUUID(),
                player.getGameProfile().getName(), List.of(), null, 0, defs.generation(), newLineage());
        changed();

        if(finishing) { notice(player,Component.translatable("finishing.immersive_brewing.started"),false); return; }

        SimHandle sim = SimHandles.create(SessionKind.BREWING, ledger.params(), null);
        player.closeContainer();
        SessionManager.begin(player, this, sim, false, display(plan.output(), plan, approved));
    }

    private CompoundTag display(ItemStack output, @Nullable BrewPlan plan, KettleSetup.Assist assist) {
        CompoundTag tag = new CompoundTag();
        tag.put("output", LedgerCodec.saveStack(output.copyWithCount(1)));
        if (ledger != null) {
            tag.putInt("servings", ledger.inputs().size());
            tag.putInt("extraction", ledger.extraction());
            tag.putString("profile", ledger.snapshot().getString("profile"));
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
            case RESUME -> resume(player);
            case CANCEL_PASSIVE -> {
                if(ledger!=null&&ledger.operation()==Operation.FINISH) {
                    ledger.setState(ledger.state()==JobState.RUNNING?JobState.SEALED:ledger.state()); changed();
                }
            }
            default -> super.handleAction(player, action, argument);
        }
    }

    /** Resumes a sealed brew with its stored state and seed (spec 20.4: count-in, held input neutral, no reroll). */
    public void resume(ServerPlayer player) {
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
        if (level != null) level.playSound(null, worldPosition, ModSounds.BREW_COMPLETE.get(), SoundSource.BLOCKS, 0.8F, 1.0F);

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
        if(ledger!=null&&ledger.operation()==Operation.FINISH&&ledger.state()==JobState.RECOVERY&&level!=null&&level.getGameTime()%20==0) revalidateRecovery();
        if(ledger==null||ledger.operation()!=Operation.FINISH||ledger.state()!=JobState.RUNNING) return;
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
        if(level!=null) level.playSound(null,worldPosition,ModSounds.BREW_COMPLETE.get(),SoundSource.BLOCKS,.8F,1F);
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
            level.playSound(null, worldPosition, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
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

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("LastLineage", Tag.TAG_LONG)) lastLineage = tag.getLong("LastLineage");
    }
}
