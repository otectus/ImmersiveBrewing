package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.advancement.Milestone;
import com.otectus.immersivebrewing.advancement.ModAdvancements;
import com.otectus.immersivebrewing.api.event.BatchRefinedEvent;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.menu.AlembicMenu;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.distillation.DistillationScore;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.potion.EffectPolicies;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.recipe.DistillationRecipe;
import com.otectus.immersivebrewing.recipe.ReclamationRecipe;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import com.otectus.immersivebrewing.registry.ModSounds;
import com.otectus.immersivebrewing.registry.ModTags;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import com.otectus.immersivebrewing.workshop.WorkshopProfiles;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Alembic (spec 9): purification and reclamation, deliberately optional.
 * <ul>
 * <li><b>Purification</b>: one or two groups of identical, unrefined potions below the quality cap. The game's {@code D}
 * decides {@code Q_out}; each group returns {@code yield} potions and the sacrificed containers. The lineage's
 * purification count rises whatever {@code D} was, so the one-time limit can not be gamed by failing on purpose.</li>
 * <li><b>Reclamation</b>: passive. Full groups of accepted potions become the recipe's low-value result plus their
 * containers; nothing valuable is returned.</li>
 * </ul>
 */
public class AlembicBlockEntity extends StationBlockEntity {
    public static final int INPUT = 0;
    public static final int INPUT_SIZE = 6;
    public static final int FUEL = 6;
    public static final int OUTPUT = 7;
    public static final int OUTPUT_SIZE = 6;
    public static final int SLOTS = 13;

    /** Water in the coolant jacket, in millibuckets (handoff 9: a real water-only tank, separate from any potion). */
    private int coolantMb;
    /**
     * The charge fitting's setting (handoff 9.2): purify the loaded bottles, or reclaim their salt. A public setting of
     * the idle still, saved with it; the charge commits whichever it shows, after its preview.
     */
    private boolean reclaimMode;
    @Nullable
    private AlembicProcess process;
    @Nullable
    private UUID lastActor;
    private LazyOptional<IFluidHandler> coolantCap = LazyOptional.of(CoolantHandler::new);

    public AlembicBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ALEMBIC.get(), pos, state, SLOTS);
    }

    @Override
    public ResourceLocation stationId() {
        return ImmersiveBrewing.id("alembic");
    }

    static boolean isInput(int slot) {
        return slot >= INPUT && slot < INPUT + INPUT_SIZE;
    }

    static boolean isOutput(int slot) {
        return slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE;
    }

    @Override
    protected int slotLimit(int slot) {
        return isInput(slot) ? 1 : 64;
    }

    @Override
    protected boolean canPlace(int slot, ItemStack stack) {
        if (stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        if (isInput(slot)) return ledger == null && stack.getItem() instanceof PotionItem;
        return slot == FUEL && stack.is(ModTags.BURNER_FUEL);
    }

    @Override
    protected boolean automationInsert(@Nullable Direction side, int slot, ItemStack stack) {
        if (!canPlace(slot, stack) || side == Direction.DOWN) return false;
        if (side != null && side.getAxis().isHorizontal()) return slot == FUEL;
        return isInput(slot);
    }

    @Override
    protected boolean automationExtract(@Nullable Direction side, int slot) {
        return (side == null || side == Direction.DOWN) && isOutput(slot);
    }

    @Override
    protected boolean isOutputSlot(int slot) {
        return isOutput(slot);
    }

    @Override
    protected boolean loaded() {
        for (int i = 0; i < INPUT_SIZE; i++) if (!inventory.getStackInSlot(INPUT + i).isEmpty()) return true;
        return false;
    }

    @Override
    protected boolean emptyForRecovery() {
        return !loaded();
    }

    private List<ItemStack> inputs() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < INPUT_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(INPUT + i);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ---- purification ----

    /** What a purification would do with the loaded bottles, or why it can not. */
    public record PurifyPlan(@Nullable DistillationRecipe recipe, ItemStack sample, int groups, int qualityIn, @Nullable Component problem) {}

    /** Read-only operation comparison used by the screen; every count comes from the same recipes start uses. */
    public record OperationPreview(ItemStack sample, int inputCount, int purifyOutput, ItemStack returnedContainer, int returnedContainers, int purifyFuel,
                                   int qualityIn, int qualityMinimum, int qualityMaximum, int eligibleEffects, int totalEffects,
                                   int attemptsRemaining, ItemStack reclaimResult, int reclaimOutput, int reclaimContainers,
                                   int reclaimFuel, @Nullable Component problem) {}

    public PurifyPlan purifyPlan() {
        if (!ServerConfig.get(ServerConfig.ALLOW_PURIFICATION)) return refuse("alembic.immersive_brewing.purify.disabled");
        List<ItemStack> in = inputs();
        if (in.isEmpty()) return refuse("alembic.immersive_brewing.purify.empty");
        ItemStack first = in.get(0);
        BrewingDefinitions defs = definitions();
        if (defs.exclusions().excludesItem(first)) return refuse("alembic.immersive_brewing.purify.unsupported");
        Optional<BrewingMetadata> meta = BrewingMetadata.read(first);
        for (ItemStack s : in) {
            Component mismatch = mismatch(first, meta, s);
            if (mismatch != null) return new PurifyPlan(null, first, 0, meta.map(BrewingMetadata::quality).orElse(QualityMath.BASELINE), mismatch);
        }
        if (BrewingMetadata.hasUnreadableCompound(first) || !PotionContentsService.effectsSupported(first)) {
            return refuse("alembic.immersive_brewing.purify.foreign");
        }
        DistillationRecipe recipe = distillationFor(first);
        if (recipe == null) return refuse("alembic.immersive_brewing.purify.unsupported");
        int groups = in.size() / recipe.groupSize();
        if (groups < 1 || groups > 2 || in.size() % recipe.groupSize() != 0) {
            return new PurifyPlan(recipe, first, 0, 0, Component.translatable("alembic.immersive_brewing.purify.group", recipe.groupSize()));
        }
        int qualityIn = meta.map(BrewingMetadata::quality).orElse(QualityMath.BASELINE);
        int cap = Math.min(recipe.qualityCap(), ServerConfig.get(ServerConfig.PURIFICATION_QUALITY_CAP));
        if (qualityIn >= cap) return new PurifyPlan(recipe, first, groups, qualityIn, Component.translatable("alembic.immersive_brewing.purify.too_good", cap));
        int done = meta.map(BrewingMetadata::purifications).orElse(0);
        if (done >= ServerConfig.get(ServerConfig.MAX_PURIFICATIONS_PER_LINEAGE)) {
            return new PurifyPlan(recipe, first, groups, qualityIn, Component.translatable("alembic.immersive_brewing.purify.once"));
        }
        int fuel = recipe.fuelCharges() * groups;
        if (fuelAvailable() < fuel) return new PurifyPlan(recipe, first, groups, qualityIn, Component.translatable("plan.immersive_brewing.missing.fuel", fuel, fuelAvailable()));
        int slotsNeeded = recipe.yield() * groups + 1;
        if (freeOutputs() < slotsNeeded) return new PurifyPlan(recipe, first, groups, qualityIn, Component.translatable("alembic.immersive_brewing.output_full"));
        return new PurifyPlan(recipe, first, groups, qualityIn, null);
    }

    @Nullable
    private static Component mismatch(ItemStack first, Optional<BrewingMetadata> firstMeta, ItemStack other) {
        if (BrewingMetadata.hasUnreadableCompound(first) != BrewingMetadata.hasUnreadableCompound(other)) {
            return Component.translatable("alembic.immersive_brewing.purify.mismatch.newer");
        }
        if (first.getItem() != other.getItem()) return Component.translatable("alembic.immersive_brewing.purify.mismatch.container");
        if (!PotionContentsService.sameCanonical(first, other)) return Component.translatable("alembic.immersive_brewing.purify.mismatch.contents");
        Optional<BrewingMetadata> otherMeta = BrewingMetadata.read(other);
        if (firstMeta.isEmpty() != otherMeta.isEmpty()) return Component.translatable("alembic.immersive_brewing.purify.mismatch.metadata");
        if (firstMeta.isEmpty()) return null;
        BrewingMetadata a = firstMeta.get();
        BrewingMetadata b = otherMeta.get();
        if (a.quality() != b.quality()) return Component.translatable("alembic.immersive_brewing.purify.mismatch.quality");
        if (a.purifications() != b.purifications()) return Component.translatable("alembic.immersive_brewing.purify.mismatch.history");
        if (a.lineage() != b.lineage()) return Component.translatable("alembic.immersive_brewing.purify.mismatch.lineage");
        if (!a.equals(b)) return Component.translatable("alembic.immersive_brewing.purify.mismatch.metadata");
        return null;
    }

    private BrewingDefinitions definitions() {
        return BrewingDefinitions.forSide(level != null && level.isClientSide);
    }

    private static PurifyPlan refuse(String key) {
        return new PurifyPlan(null, ItemStack.EMPTY, 0, 0, Component.translatable(key));
    }

    public OperationPreview operationPreview() {
        List<ItemStack> in = inputs();
        PurifyPlan plan = purifyPlan();
        ItemStack sample = !plan.sample().isEmpty() ? plan.sample().copyWithCount(1) : in.isEmpty() ? ItemStack.EMPTY : in.get(0).copyWithCount(1);
        int count = in.size();
        int qIn = BrewingMetadata.read(sample).map(BrewingMetadata::quality).orElse(QualityMath.BASELINE);
        int output = 0;
        ItemStack returnedContainer = ItemStack.EMPTY;
        int containers = 0;
        int fuel = 0;
        int qMax = qIn;
        int attempts = 0;
        DistillationRecipe previewRecipe = plan.recipe() != null ? plan.recipe() : sample.isEmpty() ? null : distillationFor(sample);
        if (previewRecipe != null) {
            DistillationRecipe recipe = previewRecipe;
            int groups = count / recipe.groupSize();
            output = groups * recipe.yield();
            returnedContainer = new ItemStack(recipe.container());
            containers = groups * (recipe.groupSize() - recipe.yield());
            fuel = groups * recipe.fuelCharges();
            int cap = Math.min(recipe.qualityCap(), ServerConfig.get(ServerConfig.PURIFICATION_QUALITY_CAP));
            qMax = QualityMath.purified(qIn, QualityMath.MAX, cap);
            int used = BrewingMetadata.read(sample).map(BrewingMetadata::purifications).orElse(0);
            attempts = Math.max(0, ServerConfig.get(ServerConfig.MAX_PURIFICATIONS_PER_LINEAGE) - used);
        }
        BrewingDefinitions defs = definitions();
        int totalEffects = PotionUtils.getMobEffects(sample).size();
        int eligibleEffects = 0;
        if (!sample.isEmpty() && PotionContentsService.effectsSupported(sample) && !EffectPolicies.isBundle(PotionUtils.getPotion(sample))) {
            for (var effect : PotionUtils.getPotion(sample).getEffects()) {
                if (!effect.isInfiniteDuration() && effect.getDuration() > 0 && EffectPolicies.treatment(effect.getEffect(), defs).scales()) eligibleEffects++;
            }
        }

        ItemStack reclaimResult = ItemStack.EMPTY;
        int reclaimOutput = 0;
        int reclaimContainers = 0;
        int reclaimFuel = 0;
        if (!sample.isEmpty()) {
            ReclamationRecipe reclaim = reclamationFor(sample);
            if (reclaim != null) {
                int groups = count / reclaim.groupSize();
                reclaimResult = reclaim.result().copyWithCount(1);
                reclaimOutput = groups * reclaim.result().getCount();
                reclaimContainers = groups * reclaim.groupSize();
                reclaimFuel = groups * reclaim.fuelCharges();
            }
        }
        return new OperationPreview(sample, count, output, returnedContainer, containers, fuel, qIn, qIn, qMax, eligibleEffects, totalEffects,
                attempts, reclaimResult, reclaimOutput, reclaimContainers, reclaimFuel, plan.problem());
    }

    /**
     * Keeps the largest exact-compatible complete purification group and returns every other bottle unchanged. This is
     * all-or-nothing when the player's main inventory lacks enough empty slots.
     */
    public int selectCompatibleGroup(Player player) {
        if (ledger != null) return 0;
        List<Integer> occupied = new ArrayList<>();
        for (int i = 0; i < INPUT_SIZE; i++) if (!inventory.getStackInSlot(INPUT + i).isEmpty()) occupied.add(INPUT + i);
        if (occupied.isEmpty()) return 0;
        List<Integer> best = List.of();
        for (int anchor : occupied) {
            ItemStack first = inventory.getStackInSlot(anchor);
            Optional<BrewingMetadata> meta = BrewingMetadata.read(first);
            DistillationRecipe recipe = distillationFor(first);
            if (recipe == null) continue;
            List<Integer> compatible = new ArrayList<>();
            for (int slot : occupied) if (mismatch(first, meta, inventory.getStackInSlot(slot)) == null) compatible.add(slot);
            int usable = compatible.size() - compatible.size() % recipe.groupSize();
            if (usable > best.size()) best = List.copyOf(compatible.subList(0, usable));
        }
        if (best.isEmpty()) return 0;
        List<Integer> move = new ArrayList<>(occupied);
        move.removeAll(best);
        int empty = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) empty++;
        if (empty < move.size()) return -1;
        int moved = 0;
        for (int slot : move) {
            ItemStack stack = inventory.getStackInSlot(slot).copy();
            if (player.addItem(stack)) {
                putDirect(slot, ItemStack.EMPTY);
                moved++;
            }
        }
        return moved;
    }

    @Nullable
    private DistillationRecipe distillationFor(ItemStack stack) {
        if (level == null) return null;
        RecipeManager recipes = level.getRecipeManager();
        BrewingDefinitions defs = definitions();
        return recipes.getAllRecipesFor(ModRecipeTypes.DISTILLATION.get()).stream()
                .filter(r -> !defs.exclusions().excludesRecipe(r.getId()) && r.input().matches(stack))
                .min(Comparator.comparing(r -> r.getId().toString())).orElse(null);
    }

    @Nullable
    private ReclamationRecipe reclamationFor(ItemStack stack) {
        if (level == null) return null;
        BrewingDefinitions defs = definitions();
        return level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.RECLAMATION.get()).stream()
                .filter(r -> !defs.exclusions().excludesRecipe(r.getId()) && r.input().matches(stack))
                .min(Comparator.comparing(r -> r.getId().toString())).orElse(null);
    }

    private int fuelAvailable() {
        return fuelCharges + inventory.getStackInSlot(FUEL).getCount() * ServerConfig.fuelChargesPerBlazePowder();
    }

    private void spendFuel(int charges) {
        int per = ServerConfig.fuelChargesPerBlazePowder();
        while (fuelCharges < charges && !inventory.getStackInSlot(FUEL).isEmpty()) {
            ItemStack f = inventory.getStackInSlot(FUEL);
            putDirect(FUEL, f.copyWithCount(f.getCount() - 1));
            fuelCharges += per;
        }
        fuelCharges -= charges;
    }

    private int freeOutputs() {
        int n = 0;
        for (int i = 0; i < OUTPUT_SIZE; i++) if (inventory.getStackInSlot(OUTPUT + i).isEmpty()) n++;
        return n;
    }

    private List<ItemStack> takeInputs() {
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < INPUT_SIZE; i++) {
            ItemStack s = inventory.getStackInSlot(INPUT + i);
            if (s.isEmpty()) continue;
            taken.add(s.copy());
            putDirect(INPUT + i, ItemStack.EMPTY);
        }
        return taken;
    }

    @Override
    public void handleAction(ServerPlayer player, StationActionC2S.Action action, int argument) {
        switch (action) {
            case START_PURIFICATION -> startPurification(player, argument);
            case START_RECLAMATION -> startReclamation(player);
            case CANCEL_PASSIVE -> cancelReclamation(player);
            case RESUME -> resume(player);
            default -> super.handleAction(player, action, argument);
        }
    }

    public void startPurification(ServerPlayer player, int argument) {
        WorkshopResult r = charge(player);
        if (r.reason() != null) notice(player, r.reason(), !r.accepted());
        if (r.accepted()) {
            player.closeContainer();
            openWorkingView(player);
        }
    }

    /**
     * Pours the charge (handoff 9): validates the homogeneous group exactly as before, then reserves the potions, the
     * fuel charges and the full coolant cost at once. Yield, returned containers and the quality cap are those the
     * preview showed.
     */
    public WorkshopResult charge(ServerPlayer player) {
        if (level == null) return WorkshopResult.IGNORED;
        if (ServerConfig.disabled()) return WorkshopResult.refuse("notice.immersive_brewing.disabled");
        if (ledger != null || SessionManager.stationBusy(this)) return WorkshopResult.refuse("notice.immersive_brewing.busy");
        PurifyPlan plan = purifyPlan();
        if (plan.problem() != null || plan.recipe() == null) {
            return plan.problem() == null ? WorkshopResult.refuse("alembic.immersive_brewing.purify.unsupported") : WorkshopResult.refuse(plan.problem());
        }
        DistillationRecipe recipe = plan.recipe();
        int coolant = ServerConfig.coolantFor(plan.groups());
        if (coolantMb < coolant) return WorkshopResult.refuse("workshop.immersive_brewing.alembic.coolant_needed", coolant, coolantMb);
        int fuel = recipe.fuelCharges() * plan.groups();
        spendFuel(fuel);
        coolantMb -= coolant;
        List<ItemStack> taken = takeInputs();
        long seed = ((ServerLevel) level).getRandom().nextLong();
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("recipe", recipe.getId().toString());
        snapshot.putInt("groups", plan.groups());
        snapshot.putInt("groupSize", recipe.groupSize());
        snapshot.putInt("yield", recipe.yield());
        int cap = Math.min(recipe.qualityCap(), ServerConfig.get(ServerConfig.PURIFICATION_QUALITY_CAP));
        snapshot.putInt("cap", cap);
        snapshot.putString("container", String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(recipe.container())));
        snapshot.putInt("qualityIn", plan.qualityIn());
        snapshot.putInt("coolant", coolant);
        var profile = WorkshopProfiles.distillation(recipe.getId());
        snapshot.putString("workshopProfile", profile.source());
        ResourceLocation presentation = WorkshopProfiles.presentationOf(profile.source());
        if (presentation != null) snapshot.putString("presentation", presentation.toString());
        AlembicProcess p = AlembicProcess.begin(new AlembicProcess.Setup(plan.groups(), recipe.groupSize(), recipe.yield(), cap, plan.qualityIn(),
                coolant, profile.value().headsEnd(), profile.value().tailsStart(), profile.value().runSeconds(), profile.source()), player.getUUID());
        ledger = new JobLedger(UUID.randomUUID(), Operation.PURIFY, JobState.RUNNING, taken, List.of(), fuel, snapshot,
                plan.sample().copyWithCount(1), new long[0], new long[0], seed, 0, player.getUUID(), player.getGameProfile().getName(),
                List.of(), null, 0, BrewingDefinitions.server().generation(), 0);
        ledger.setProcess(p.save());
        process = p;
        lastActor = player.getUUID();
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.POUR, ledger.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        return WorkshopResult.ACCEPTED;
    }

    private CompoundTag display(ItemStack sample, int groups, int qualityIn, double assistTime, double assistBand) {
        CompoundTag tag = new CompoundTag();
        tag.put("input", LedgerCodec.saveStack(sample.copyWithCount(1)));
        tag.putInt("groups", groups);
        tag.putInt("qualityIn", qualityIn);
        tag.putDouble("assistTime", assistTime);
        tag.putDouble("assistBand", assistBand);
        return tag;
    }

    public void resume(ServerPlayer player) {
        if (ledger != null && ledger.physical()) {
            WorkshopResult r = unsealPhysical(player);
            if (r.reason() != null) notice(player, r.reason(), true);
            player.closeContainer();
            openWorkingView(player);
            return;
        }
        if (ledger == null || ledger.state() != JobState.SEALED || ledger.operation() != Operation.PURIFY) {
            notice(player, Component.translatable("notice.immersive_brewing.nothing_to_resume"), true);
            return;
        }
        if (SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        SimHandle sim;
        try {
            sim = SimHandles.create(SessionKind.DISTILLATION, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            ledger.hold("recovery.immersive_brewing.state_unreadable");
            changed();
            return;
        }
        player.closeContainer();
        CompoundTag snap = ledger.snapshot();
        SessionManager.begin(player, this, sim, true, display(ledger.outputSnapshot(), snap.getInt("groups"), snap.getInt("qualityIn"),
                snap.contains("assistTime", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? snap.getDouble("assistTime") : 1.0,
                snap.contains("assistBand", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC) ? snap.getDouble("assistBand") : 1.0));
    }

    @Override
    @Nullable
    public CompoundTag completeJob(@Nullable ServerPlayer player, SimHandle sim) {
        if (ledger == null || ledger.operation() != Operation.PURIFY || ledger.state() != JobState.RUNNING) return null;
        if (!(sim instanceof SimHandles.Distillation distillation)) return null;
        DistillationScore score = distillation.sim().score();
        CompoundTag snap = ledger.snapshot();
        int groups = snap.getInt("groups");
        int groupSize = snap.getInt("groupSize");
        int yield = snap.getInt("yield");
        int cap = snap.getInt("cap");
        int qualityIn = snap.getInt("qualityIn");
        ResourceLocation containerId = ResourceLocation.tryParse(snap.getString("container"));
        net.minecraft.world.item.Item container = containerId == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(containerId);
        if (container == null || freeOutputs() < yield * groups + 1 || ledger.inputs().size() != groups * groupSize) {
            ledger.setSimState(sim.saveState());
            ledger.hold(container == null ? "recovery.immersive_brewing.missing_item" : "recovery.immersive_brewing.no_room");
            changed();
            return null;
        }
        int qualityOut = QualityMath.purified(qualityIn, score.distillation(), cap);
        String refiner = player != null ? player.getGameProfile().getName() : ledger.brewerName();
        JobLedger l = ledger;
        ledger = null;
        int placed = 0;
        ItemStack shown = ItemStack.EMPTY;
        // Inputs were checked to have identical metadata. Ordinary bottles acquire one lineage for the whole batch.
        BrewingMetadata refined = BrewingMetadata.read(l.inputs().get(0)).orElseGet(() -> BrewingMetadata.brewed(
                QualityMath.RAW_EXTRACTION, QualityMath.BASELINE, QualityMath.BASELINE, newLineage(), null, null))
                .withPurification(qualityOut, refiner);
        for (int g = 0; g < groups; g++) {
            for (int y = 0; y < yield; y++) {
                ItemStack out = l.inputs().get(g * groupSize + y).copy();
                BrewingMetadata.write(out, refined);
                placeOutput(out);
                if (shown.isEmpty()) shown = out.copyWithCount(1);
                placed++;
            }
        }
        int sacrificed = groups * (groupSize - yield);
        if (sacrificed > 0) placeOutput(new ItemStack(container, sacrificed));
        lastResult = new StationResult(Operation.PURIFY, shown, placed, qualityOut, 0, score.distillation(), score.tipKey(), List.of(), refiner);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), sim.step(), 0, 0);
        MinecraftForge.EVENT_BUS.post(new BatchRefinedEvent(player, worldPosition, shown, placed, true, qualityIn, qualityOut));
        if (player != null && qualityOut > qualityIn) ModAdvancements.milestone(player, Milestone.PURIFY);
        CompoundTag result = new CompoundTag();
        result.putString("kind", "distillation");
        result.put("output", LedgerCodec.saveStack(shown));
        result.putInt("count", placed);
        result.putInt("sacrificed", sacrificed);
        result.putInt("D", score.distillation());
        result.putInt("Qin", qualityIn);
        result.putInt("Q", qualityOut);
        result.putDouble("collection", score.collection());
        result.putDouble("thermal", score.thermal());
        result.putDouble("cooling", score.cooling());
        result.putBoolean("improved", qualityOut > qualityIn);
        result.putString("tip", score.tipKey());
        return result;
    }

    private void placeOutput(ItemStack stack) {
        ItemStack left = stack;
        for (int i = 0; i < OUTPUT_SIZE && !left.isEmpty(); i++) {
            ItemStack s = inventory.getStackInSlot(OUTPUT + i);
            if (s.isEmpty()) {
                int move = Math.min(left.getCount(), left.getMaxStackSize());
                putDirect(OUTPUT + i, left.copyWithCount(move));
                finished[OUTPUT + i] = true;
                left = left.copyWithCount(left.getCount() - move);
            } else if (ItemStack.isSameItemSameTags(s, left) && s.getCount() < s.getMaxStackSize()) {
                int move = Math.min(left.getCount(), s.getMaxStackSize() - s.getCount());
                putDirect(OUTPUT + i, s.copyWithCount(s.getCount() + move));
                finished[OUTPUT + i] = true;
                left = left.copyWithCount(left.getCount() - move);
            }
        }
        if (!left.isEmpty() && level != null) {
            ImmersiveBrewing.LOGGER.warn("Immersive Brewing: Alembic output full at {}; dropped {}", worldPosition, left);
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, left);
        }
    }

    // ---- hands-on workshop ----

    @Override
    public WorkshopKind workshopKind() {
        return WorkshopKind.ALEMBIC;
    }

    @Nullable
    public AlembicProcess liveProcess() {
        if (ledger == null || !ledger.physical() || ledger.operation() != Operation.PURIFY) return null;
        if (process == null) {
            try {
                process = AlembicProcess.load(ledger.process());
            } catch (RuntimeException e) {
                if (ledger.state() != JobState.RECOVERY) {
                    ledger.hold("recovery.immersive_brewing.process_unreadable");
                    changed();
                }
                return null;
            }
        }
        return process;
    }

    public int coolantMb() {
        return coolantMb;
    }

    /** Coolant still reserved by a purification that has not completed (it returns on a cancel). */
    private int reservedCoolant() {
        AlembicProcess p = liveProcess();
        return p == null ? 0 : p.setup().coolantMb();
    }

    /** Room left in the jacket: a running purification's reservation still counts against the capacity. */
    private int coolantRoom() {
        return Math.max(0, ServerConfig.get(ServerConfig.COOLANT_CAPACITY_MB) - coolantMb - reservedCoolant());
    }

    @Override
    protected void flushProcess() {
        if (ledger != null && ledger.physical() && process != null) ledger.setProcess(process.save());
    }

    @Override
    protected java.util.List<UUID> workshopParticipants() {
        AlembicProcess p = liveProcess();
        return p == null ? java.util.List.of() : p.participants();
    }

    @Override
    protected void participate(ServerPlayer player) {
        lastActor = player.getUUID();
        AlembicProcess p = liveProcess();
        if (p != null) p.participate(player.getUUID());
        if (ledger != null && !player.getUUID().equals(ledger.brewerId())) ledger.addAssistant(player.getGameProfile().getName());
    }

    @Override
    protected void onPhysicalSealed() {
        AlembicProcess p = liveProcess();
        if (p != null) p.seal();
    }

    @Override
    @Nullable
    protected String physicalResumeProblem() {
        return liveProcess() == null ? "recovery.immersive_brewing.process_unreadable" : null;
    }

    @Override
    protected void resumeLegacy(ServerPlayer player) {
        resume(player);
    }

    @Override
    protected void physicalStep(long now) {
        AlembicProcess p = liveProcess();
        if (p == null) return;
        p.tick();
        if (p.finished()) completePurificationPhysical(p);
        markWorkshopDirty(false);
    }

    /** The run has passed through the condenser: commit the previewed yield once, with the earned purification. */
    private void completePurificationPhysical(AlembicProcess p) {
        JobLedger l = ledger;
        if (l == null) return;
        CompoundTag snap = l.snapshot();
        int groups = snap.getInt("groups");
        int groupSize = snap.getInt("groupSize");
        int yield = snap.getInt("yield");
        int cap = snap.getInt("cap");
        int qualityIn = snap.getInt("qualityIn");
        ResourceLocation containerId = ResourceLocation.tryParse(snap.getString("container"));
        net.minecraft.world.item.Item container = containerId == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(containerId);
        if (container == null || freeOutputs() < yield * groups + 1 || l.inputs().size() != groups * groupSize) {
            flushProcess();
            leases.clear();
            l.hold(container == null ? "recovery.immersive_brewing.missing_item" : "recovery.immersive_brewing.no_room");
            changed();
            markWorkshopDirty(true);
            return;
        }
        int d = p.distillation();
        int qualityOut = QualityMath.purified(qualityIn, d, cap);
        ServerPlayer actor = null;
        if (level != null && level.getServer() != null) {
            UUID who = lastActor != null ? lastActor : l.brewerId();
            if (who != null) actor = com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), who);
        }
        String refiner = actor != null ? actor.getGameProfile().getName() : l.brewerName();
        ledger = null;
        process = null;
        leases.clear();
        int placed = 0;
        ItemStack shown = ItemStack.EMPTY;
        BrewingMetadata refined = BrewingMetadata.read(l.inputs().get(0)).orElseGet(() -> BrewingMetadata.brewed(
                QualityMath.RAW_EXTRACTION, QualityMath.BASELINE, QualityMath.BASELINE, newLineage(), null, null))
                .withPurification(qualityOut, refiner);
        for (int g = 0; g < groups; g++) {
            for (int y = 0; y < yield; y++) {
                ItemStack out = l.inputs().get(g * groupSize + y).copy();
                BrewingMetadata.write(out, refined);
                placeOutput(out);
                if (shown.isEmpty()) shown = out.copyWithCount(1);
                placed++;
            }
        }
        int sacrificed = groups * (groupSize - yield);
        if (sacrificed > 0) placeOutput(new ItemStack(container, sacrificed));
        String tip = p.tipKey();
        lastResult = new StationResult(Operation.PURIFY, shown, placed, qualityOut, 0, d, tip, List.of(), refiner);
        setVisual(StationVisual.IDLE);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        MinecraftForge.EVENT_BUS.post(new BatchRefinedEvent(actor, worldPosition, shown, placed, true, qualityIn, qualityOut));
        if (actor != null) {
            if (qualityOut > qualityIn) ModAdvancements.milestone(actor, Milestone.PURIFY);
            CompoundTag result = new CompoundTag();
            result.putString("kind", "distillation");
            result.put("output", LedgerCodec.saveStack(shown));
            result.putInt("count", placed);
            result.putInt("sacrificed", sacrificed);
            result.putInt("D", d);
            result.putInt("Qin", qualityIn);
            result.putInt("Q", qualityOut);
            result.putDouble("collection", p.collection());
            result.putDouble("thermal", p.thermal());
            result.putDouble("cooling", p.cooling());
            result.putBoolean("improved", qualityOut > qualityIn);
            result.putString("tip", tip);
            result.putBoolean("physical", true);
            com.otectus.immersivebrewing.guide.PlayerGuide.recordResult(actor, result);
            notice(actor, Component.translatable("workshop.immersive_brewing.alembic.done", placed, shown.getHoverName(), qualityOut,
                    Component.translatable(tip)), false);
        }
    }

    @Override
    protected InteractionResult directInsert(Player player, InteractionHand hand, ItemStack held, @Nullable WorkshopPart part, int limit) {
        boolean client = level == null || level.isClientSide;
        boolean water = held.is(Items.POTION) && PotionUtils.getPotion(held) == Potions.WATER && PotionUtils.getCustomEffects(held).isEmpty();
        if (water && part != null && part.id() == WorkshopParts.A_COOLANT) {
            if (client) return InteractionResult.SUCCESS;
            if (coolantRoom() < BrewersKettleBlockEntity.MB_PER_BOTTLE) {
                notice(player, Component.translatable("workshop.immersive_brewing.alembic.coolant_full"), true);
                return InteractionResult.CONSUME;
            }
            coolantMb += BrewersKettleBlockEntity.MB_PER_BOTTLE;
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
                ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
                if (held.isEmpty()) player.setItemInHand(hand, bottle);
                else if (!player.getInventory().add(bottle)) player.drop(bottle, false);
            }
            changed();
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.WATER, StationVisual.NONE, 0, 0, 0);
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof PotionItem && !held.is(ModTags.CARRIER_EXCLUDED)) {
            if (client) return InteractionResult.SUCCESS;
            if (ledger != null) {
                notice(player, Component.translatable("workshop.immersive_brewing.alembic.busy"), true);
                return InteractionResult.CONSUME;
            }
            int placed = 0;
            for (int n = 0; n < Math.max(1, limit) && !held.isEmpty(); n++) {
                int target = -1;
                for (int i = 0; i < INPUT_SIZE && target < 0; i++) if (inventory.getStackInSlot(INPUT + i).isEmpty()) target = INPUT + i;
                if (target < 0) break;
                putDirect(target, held.copyWithCount(1));
                placed++;
                if (player.getAbilities().instabuild) break;
                held.shrink(1);
            }
            if (placed == 0) {
                notice(player, Component.translatable("workshop.immersive_brewing.alembic.charge_full"), true);
                return InteractionResult.CONSUME;
            }
            changed();
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0, WorkshopParts.A_INPUT, placed);
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
            changed();
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected WorkshopResult act(ServerPlayer player, WorkshopIntent intent, long now) {
        int part = intent.part();
        switch (intent.action()) {
            case FINISH -> {
                if (part != WorkshopParts.A_CHARGE) return WorkshopResult.IGNORED;
                // The charge commits what the fitting shows, never anything inferred from leaving the still.
                WorkshopResult r = reclaimMode ? reclaim(player) : charge(player);
                if (r.accepted() && !reclaimMode) participate(player);
                return r;
            }
            case INSERT -> {
                ItemStack held = player.getItemInHand(intent.hand());
                if (held.isEmpty()) return WorkshopResult.IGNORED;
                InteractionResult r = directInsert(player, intent.hand(), held, WorkshopParts.get(WorkshopKind.ALEMBIC, part), intent.batch() ? 64 : 1);
                return r == InteractionResult.PASS ? WorkshopResult.refuse("workshop.immersive_brewing.refuse.not_here") : WorkshopResult.ACCEPTED;
            }
            case TAKE -> {
                return take(player, part, intent.batch());
            }
            case SEAL -> {
                if (ledger == null || !ledger.physical() || ledger.state() != JobState.RUNNING) return WorkshopResult.IGNORED;
                sealPhysical(player, "workshop.immersive_brewing.seal.manual");
                return WorkshopResult.ACCEPTED;
            }
            case UNSEAL -> {
                return unsealPhysical(player);
            }
            case CANCEL -> {
                AlembicProcess p = liveProcess();
                if (p == null || ledger == null || ledger.state() == JobState.RECOVERY) return WorkshopResult.IGNORED;
                if (!p.untouched()) return WorkshopResult.refuse("workshop.immersive_brewing.alembic.cancel_too_late");
                int coolant = p.setup().coolantMb();
                cancelReservation();
                coolantMb = Math.min(ServerConfig.get(ServerConfig.COOLANT_CAPACITY_MB) + coolant, coolantMb + coolant);
                notice(player, Component.translatable("notice.immersive_brewing.workshop.released"), false);
                return WorkshopResult.ACCEPTED;
            }
            default -> { }
        }
        if (ledger == null && intent.action() == WorkshopAction.SET_CONTROL && part == WorkshopParts.A_MODE) {
            boolean reclaim = intent.argument() != 0;
            if (reclaim == reclaimMode) return WorkshopResult.IGNORED;
            if (reclaim && !ServerConfig.get(ServerConfig.ALLOW_RECLAMATION)) return WorkshopResult.refuse("alembic.immersive_brewing.reclaim.disabled");
            reclaimMode = reclaim;
            setChanged();
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.VALVE, StationVisual.NONE, 0, reclaim ? 1 : 0, part);
            markWorkshopDirty(true);
            return WorkshopResult.ACCEPTED;
        }
        if (ledger == null) return WorkshopResult.refuse("workshop.immersive_brewing.alembic.no_charge");
        if (ledger.state() == JobState.SEALED) {
            return ledger.physical() ? WorkshopResult.refuse("workshop.immersive_brewing.sealed.open_clamp") : WorkshopResult.refuse("workshop.immersive_brewing.legacy.finish_first");
        }
        if (ledger.state() == JobState.RECOVERY) {
            return WorkshopResult.refuse("notice.immersive_brewing.held", Component.translatable(ledger.recoveryReason() == null
                    ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
        }
        AlembicProcess p = liveProcess();
        if (p == null || ledger.state() != JobState.RUNNING) return WorkshopResult.refuse("workshop.immersive_brewing.alembic.no_charge");
        switch (intent.action()) {
            case GRAB -> {
                if (part != WorkshopParts.A_BURNER && part != WorkshopParts.A_VALVE && part != WorkshopParts.A_DIVERTER) return WorkshopResult.IGNORED;
                var other = leases.acquire(part, player.getUUID(), player.getGameProfile().getName(), now);
                return other == null ? WorkshopResult.ACCEPTED : WorkshopResult.refuse("workshop.immersive_brewing.refuse.held_by", other.name());
            }
            case RELEASE -> {
                return leases.release(part, player.getUUID()) ? WorkshopResult.ACCEPTED : WorkshopResult.IGNORED;
            }
            case SET_CONTROL -> {
                if (leases.heldByOther(part, player.getUUID(), now)) {
                    var lease = leases.holder(part, now);
                    return WorkshopResult.refuse("workshop.immersive_brewing.refuse.held_by", lease == null ? "?" : lease.name());
                }
                boolean changed;
                if (part == WorkshopParts.A_CLAMP) changed = p.clamped() != (intent.argument() != 0) && setClamp(p, intent.argument() != 0);
                else if (part == WorkshopParts.A_BURNER) changed = p.setBurner(intent.argument());
                else if (part == WorkshopParts.A_VALVE) changed = p.setValve(intent.argument());
                else if (part == WorkshopParts.A_DIVERTER) changed = p.setDiverter(intent.argument() != 0);
                else return WorkshopResult.IGNORED;
                if (!changed) return WorkshopResult.IGNORED;
                participate(player);
                effect(part == WorkshopParts.A_DIVERTER ? com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.RECEIVER
                        : com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.VALVE, ledger.jobId(), 0, intent.argument(), part);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            default -> {
                return WorkshopResult.IGNORED;
            }
        }
    }

    private static boolean setClamp(AlembicProcess p, boolean value) {
        p.setClamped(value);
        return true;
    }

    private WorkshopResult take(ServerPlayer player, int part, boolean batch) {
        List<Integer> slots = new java.util.ArrayList<>();
        if (part == WorkshopParts.A_RECEIVER || part == WorkshopParts.A_WASTE) {
            for (int i = 0; i < OUTPUT_SIZE; i++) if (!inventory.getStackInSlot(OUTPUT + i).isEmpty()) {
                slots.add(OUTPUT + i);
                if (!batch) break;
            }
        } else if (part == WorkshopParts.A_INPUT && ledger == null) {
            for (int i = INPUT_SIZE - 1; i >= 0; i--) if (!inventory.getStackInSlot(INPUT + i).isEmpty()) {
                slots.add(INPUT + i);
                if (!batch) break;
            }
        } else if (part == WorkshopParts.A_FUEL) {
            slots.add(FUEL);
        } else return WorkshopResult.IGNORED;
        int moved = 0;
        for (int slot : slots) {
            ItemStack s = inventory.getStackInSlot(slot);
            if (s.isEmpty()) continue;
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

    private double lastFraction;

    @Override
    @Nullable
    protected StationVisual physicalVisual() {
        AlembicProcess p = liveProcess();
        if (p == null) return null;
        double rate = Math.max(0, p.fraction() - lastFraction) / 10.0;
        lastFraction = p.fraction();
        return new StationVisual(0, p.condensate().ordinal(), (float) p.temperature(), (float) p.coolant(), 0, (float) p.fraction(), true)
                .details(StationVisual.Mortar.EMPTY, StationVisual.Kettle.EMPTY,
                        new StationVisual.Alembic(p.burner(), p.valve(), (float) p.receiverFill(), p.toReceiver(), (float) rate),
                        (int) (level == null ? 0 : level.getGameTime() / 10), Operation.PURIFY);
    }

    @Override
    protected int physicalComparator() {
        AlembicProcess p = liveProcess();
        if (p == null) return 8;
        return !p.clamped() || p.burner() == 0 ? 9 : 8;
    }

    @Override
    protected void writeWorkshopSnapshot(CompoundTag t) {
        t.putInt("coolantMb", coolantMb);
        t.putBoolean("reclaimMode", reclaimMode);
        if (ledger != null && !ledger.snapshot().getString("presentation").isEmpty()) t.putString("presentation", ledger.snapshot().getString("presentation"));
        t.putBoolean("reclaimAllowed", ServerConfig.get(ServerConfig.ALLOW_RECLAMATION));
        t.putInt("coolantCapacity", ServerConfig.get(ServerConfig.COOLANT_CAPACITY_MB));
        t.putInt("coolantNeed", ServerConfig.coolantFor(Math.max(1, purifyGroupsForView())));
        if (ledger != null && !ledger.inputs().isEmpty()) {
            CompoundTag item = LedgerCodec.saveStack(ledger.inputs().get(0).copyWithCount(1));
            item.remove("FullCount");
            t.put("charge", item);
            t.putInt("chargeCount", ledger.inputs().size());
        }
        if (ledger != null && ledger.operation() == Operation.RECLAIM) {
            long[] progress = ledger.simState();
            int duration = Math.max(1, ledger.snapshot().getInt("duration"));
            t.putByte("reclaimProgress", (byte) Math.min(100, Math.round(100F * (progress.length > 0 ? progress[0] : 0) / duration)));
        }
        AlembicProcess p = liveProcess();
        if (p != null) {
            CompoundTag a = new CompoundTag();
            p.writeSnapshot(a);
            t.put("alembic", a);
        } else if (ledger == null && loaded()) {
            // What a charge would do, before anything is consumed (handoff 9): yield, returned containers, or why not.
            PurifyPlan plan = purifyPlan();
            Component problem = plan.problem();
            int coolant = ServerConfig.coolantFor(Math.max(1, plan.groups()));
            if (problem == null && coolantMb < coolant) problem = Component.translatable("workshop.immersive_brewing.alembic.coolant_needed", coolant, coolantMb);
            if (problem != null) {
                t.putString("previewProblem", Component.Serializer.toJson(problem));
            } else if (plan.recipe() != null) {
                t.putInt("previewIn", plan.groups() * plan.recipe().groupSize());
                t.putInt("previewOut", plan.groups() * plan.recipe().yield());
                t.putInt("previewReturned", plan.groups() * (plan.recipe().groupSize() - plan.recipe().yield()));
                t.putInt("previewCoolant", coolant);
                t.putInt("previewQualityMax", operationPreview().qualityMaximum());
            }
            // The other operation's outcome, shown before the fitting is turned to it (handoff 9.2: costs before commitment).
            Component reclaimWhy = reclaimProblem();
            if (reclaimWhy != null) {
                t.putString("reclaimProblem", Component.Serializer.toJson(reclaimWhy));
            } else {
                OperationPreview preview = operationPreview();
                CompoundTag result = LedgerCodec.saveStack(preview.reclaimResult().copyWithCount(1));
                result.remove("FullCount");
                t.put("reclaimResult", result);
                t.putInt("reclaimOut", preview.reclaimOutput());
                t.putInt("reclaimUsed", preview.reclaimContainers());
                t.putInt("reclaimFuel", preview.reclaimFuel());
            }
        }
    }

    private int purifyGroupsForView() {
        int n = inputs().size();
        return n >= 6 ? 2 : 1;
    }

    // ---- coolant capability (water only, conserved through simulation and partial fills) ----

    @Override
    public <T> @org.jetbrains.annotations.NotNull LazyOptional<T> getCapability(@org.jetbrains.annotations.NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.FLUID_HANDLER && !remove && side != Direction.DOWN) return coolantCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        coolantCap.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        coolantCap = LazyOptional.of(CoolantHandler::new);
    }

    private static boolean isWater(FluidStack stack) {
        return stack.getFluid().isSame(net.minecraft.world.level.material.Fluids.WATER) && !stack.hasTag();
    }

    private final class CoolantHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @org.jetbrains.annotations.NotNull FluidStack getFluidInTank(int tank) {
            return coolantMb <= 0 ? FluidStack.EMPTY : new FluidStack(net.minecraft.world.level.material.Fluids.WATER, coolantMb);
        }

        @Override
        public int getTankCapacity(int tank) {
            return Math.max(0, ServerConfig.get(ServerConfig.COOLANT_CAPACITY_MB) - reservedCoolant());
        }

        @Override
        public boolean isFluidValid(int tank, @org.jetbrains.annotations.NotNull FluidStack stack) {
            return isWater(stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty() || !isWater(resource)) return 0;
            int accepted = Math.min(resource.getAmount(), coolantRoom());
            if (accepted > 0 && action.execute()) {
                coolantMb += accepted;
                changed();
                markWorkshopDirty(true);
            }
            return accepted;
        }

        @Override
        public @org.jetbrains.annotations.NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource.isEmpty() || !isWater(resource)) return FluidStack.EMPTY;
            return drain(resource.getAmount(), action);
        }

        @Override
        public @org.jetbrains.annotations.NotNull FluidStack drain(int maxDrain, FluidAction action) {
            int drained = Math.min(Math.max(0, maxDrain), coolantMb);
            if (drained <= 0) return FluidStack.EMPTY;
            if (action.execute()) {
                coolantMb -= drained;
                changed();
                markWorkshopDirty(true);
            }
            return new FluidStack(net.minecraft.world.level.material.Fluids.WATER, drained);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Coolant", coolantMb);
        tag.putBoolean("ReclaimMode", reclaimMode);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Coolant", Tag.TAG_ANY_NUMERIC)) coolantMb = Math.max(0, tag.getInt("Coolant"));
        reclaimMode = tag.getBoolean("ReclaimMode");
        if (level == null || !level.isClientSide) process = null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putInt("Coolant", coolantMb);
        return tag;
    }

    @Override
    protected void afterRecoveryInstalled() {
        process = null;
        leases.clear();
        super.afterRecoveryInstalled();
        markWorkshopDirty(true);
    }

    // ---- reclamation (passive) ----

    public void startReclamation(ServerPlayer player) {
        WorkshopResult r = reclaim(player);
        if (r.reason() != null && !r.accepted()) notice(player, r.reason(), true);
    }

    /** The reclamation problem for the loaded bottles, or null when it can start (pure: the preview and the commit share it). */
    @Nullable
    private Component reclaimProblem() {
        if (!ServerConfig.get(ServerConfig.ALLOW_RECLAMATION) || ServerConfig.disabled()) return Component.translatable("alembic.immersive_brewing.reclaim.disabled");
        if (ledger != null) return Component.translatable("notice.immersive_brewing.busy");
        List<ItemStack> in = inputs();
        if (in.isEmpty()) return Component.translatable("alembic.immersive_brewing.purify.empty");
        ReclamationRecipe recipe = reclamationFor(in.get(0));
        if (recipe == null || in.stream().anyMatch(s -> !recipe.input().matches(s) || BrewingMetadata.hasUnreadableCompound(s)
                || BrewingDefinitions.server().exclusions().excludesItem(s))) {
            return Component.translatable("alembic.immersive_brewing.reclaim.unsupported");
        }
        int groups = in.size() / recipe.groupSize();
        if (groups < 1) return Component.translatable("alembic.immersive_brewing.purify.group", recipe.groupSize());
        int fuel = recipe.fuelCharges() * groups;
        if (fuelAvailable() < fuel) return Component.translatable("plan.immersive_brewing.missing.fuel", fuel, fuelAvailable());
        if (freeOutputs() < 2) return Component.translatable("alembic.immersive_brewing.output_full");
        return null;
    }

    /**
     * Starts reclamation (passive: the salt is recovered over the recipe's time). The same checks as the preview; the
     * whole full groups are reserved at once. Never started by leaving the still or by anything but this deliberate charge.
     */
    public WorkshopResult reclaim(ServerPlayer player) {
        if (level == null) return WorkshopResult.IGNORED;
        Component problem = reclaimProblem();
        if (problem != null) return WorkshopResult.refuse(problem);
        List<ItemStack> in = inputs();
        ReclamationRecipe recipe = reclamationFor(in.get(0));
        int groups = in.size() / recipe.groupSize();
        int fuel = recipe.fuelCharges() * groups;
        int used = groups * recipe.groupSize();
        spendFuel(fuel);
        List<ItemStack> taken = new ArrayList<>();
        int moved = 0;
        for (int i = 0; i < INPUT_SIZE && moved < used; i++) {
            ItemStack s = inventory.getStackInSlot(INPUT + i);
            if (s.isEmpty()) continue;
            taken.add(s.copy());
            putDirect(INPUT + i, ItemStack.EMPTY);
            moved++;
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("recipe", recipe.getId().toString());
        snapshot.putInt("groups", groups);
        snapshot.put("result", LedgerCodec.saveStack(recipe.result()));
        snapshot.putString("container", String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(recipe.container())));
        snapshot.putInt("duration", recipe.durationTicks());
        ledger = new JobLedger(UUID.randomUUID(), Operation.RECLAIM, JobState.RUNNING, taken, List.of(), fuel, snapshot,
                recipe.result(), new long[0], new long[] {0}, 0, 0, player.getUUID(), player.getGameProfile().getName(), List.of(), null, 0,
                BrewingDefinitions.server().generation(), 0);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, ledger.jobId(), 0, WorkshopParts.A_CHARGE, used);
        markWorkshopDirty(true);
        notice(player, Component.translatable("alembic.immersive_brewing.reclaim.started", used), false);
        return WorkshopResult.ACCEPTED;
    }

    private void cancelReclamation(ServerPlayer player) {
        if (ledger == null || ledger.operation() != Operation.RECLAIM) return;
        cancelReservation();
        notice(player, Component.translatable("alembic.immersive_brewing.reclaim.cancelled"), false);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (ledger == null || ledger.operation() != Operation.RECLAIM || ledger.state() != JobState.RUNNING) return;
        long[] progress = ledger.simState();
        long done = progress.length > 0 ? progress[0] + 1 : 1;
        int duration = ledger.snapshot().getInt("duration");
        if (done < duration) {
            ledger.setSimState(new long[] {done});
            if (done % 20 == 0) {
                setVisual(new StationVisual(0, 0, 0.7F, 0.6F, (float) done / duration, (float) done / duration, true));
                setChanged();
                markWorkshopDirty(false);
            }
            return;
        }
        completeReclamation();
    }

    private void completeReclamation() {
        JobLedger l = ledger;
        if (l == null) return;
        int groups = l.snapshot().getInt("groups");
        ItemStack result = LedgerCodec.loadStack(l.snapshot().getCompound("result"));
        ResourceLocation containerId = ResourceLocation.tryParse(l.snapshot().getString("container"));
        net.minecraft.world.item.Item container = containerId == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(containerId);
        if (result.isEmpty() || container == null) {
            l.hold("recovery.immersive_brewing.missing_item");
            changed();
            return;
        }
        ledger = null;
        placeOutput(result.copyWithCount(result.getCount() * groups));
        placeOutput(new ItemStack(container, l.inputs().size()));
        lastResult = new StationResult(Operation.RECLAIM, result.copyWithCount(1), result.getCount() * groups, 0, 0, 0,
                "tip.immersive_brewing.reclaim.done", List.of(), l.brewerName());
        setVisual(StationVisual.IDLE);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), 0, 0, 0);
        ServerPlayer player = level != null && level.getServer() != null && l.brewerId() != null ? level.getServer().getPlayerList().getPlayer(l.brewerId()) : null;
        MinecraftForge.EVENT_BUS.post(new BatchRefinedEvent(player, worldPosition, result, result.getCount() * groups, false, 0, 0));
        if (player != null) ModAdvancements.milestone(player, Milestone.RECLAIM);
    }

    @Override
    protected void cancelReservation() {
        if (ledger == null) return;
        JobLedger l = ledger;
        ledger = null;
        process = null;
        leases.clear();
        markWorkshopDirty(true);
        int slot = INPUT;
        for (ItemStack s : l.inputs()) {
            while (slot < INPUT + INPUT_SIZE && !inventory.getStackInSlot(slot).isEmpty()) slot++;
            if (slot < INPUT + INPUT_SIZE) putDirect(slot++, s.copy());
            else if (level != null) Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, s.copy());
        }
        fuelCharges += l.fuelCharges();
        setVisual(StationVisual.IDLE);
        changed();
    }

    // ---- batch carrier ----

    @Override
    protected InteractionResult useWith(Player player, InteractionHand hand, ItemStack held) {
        if (held.is(Items.WATER_BUCKET) || held.is(Items.BUCKET) && coolantMb >= 1000) {
            if (level == null || level.isClientSide) return InteractionResult.SUCCESS;
            boolean filling = held.is(Items.WATER_BUCKET);
            if (filling && coolantRoom() < 1000) {
                notice(player, Component.translatable(ServerConfig.get(ServerConfig.COOLANT_CAPACITY_MB) <= 0
                        ? "workshop.immersive_brewing.alembic.no_jacket" : "workshop.immersive_brewing.alembic.coolant_full"), true);
                return InteractionResult.CONSUME;
            }
            coolantMb += filling ? 1000 : -1000;
            if (!player.getAbilities().instabuild) {
                ItemStack result = new ItemStack(filling ? Items.BUCKET : Items.WATER_BUCKET);
                if (held.getCount() == 1) player.setItemInHand(hand, result);
                else {
                    held.shrink(1);
                    if (!player.getInventory().add(result)) player.drop(result, false);
                }
            }
            changed();
            effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.WATER, StationVisual.NONE, 0, 0, 0);
            markWorkshopDirty(true);
            return InteractionResult.CONSUME;
        }
        if (!(held.getItem() instanceof BatchCarrierItem)) return InteractionResult.PASS;
        if (level == null || level.isClientSide) return InteractionResult.SUCCESS;
        carrierExchange(player, held);
        return InteractionResult.CONSUME;
    }

    /** Batch Carrier at the Alembic: collect finished output, or load potions into an idle still's empty slots. */
    private void carrierExchange(Player player, ItemStack carrier) {
        List<ItemStack> contents = CarrierContents.read(carrier);
        if (anyFinished()) {
            int moved = collectFinished(contents, OUTPUT, OUTPUT_SIZE);
            CarrierContents.write(carrier, contents);
            notice(player, Component.translatable("notice.immersive_brewing.carrier.collected", moved), moved == 0);
            return;
        }
        if (ledger != null) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        int moved = loadFromCarrier(contents, INPUT, INPUT_SIZE, s -> canPlace(INPUT, s));
        CarrierContents.write(carrier, contents);
        notice(player, Component.translatable("notice.immersive_brewing.carrier.loaded", moved), moved == 0);
    }

    @Override
    protected Component status() {
        if (ledger != null) {
            if (ledger.operation() == Operation.RECLAIM && ledger.state() == JobState.RUNNING) {
                long done = ledger.simState().length > 0 ? ledger.simState()[0] : 0;
                int duration = Math.max(1, ledger.snapshot().getInt("duration"));
                return Component.translatable("status.immersive_brewing.alembic.reclaiming", Math.round(100F * done / duration));
            }
            if (ledger.physical() && ledger.state() == JobState.RUNNING) {
                AlembicProcess p = liveProcess();
                if (p != null) return Component.translatable("status.immersive_brewing.alembic.working", Component.translatable(p.cueKey()),
                        Math.round(p.fraction() * 100));
            }
            return switch (ledger.state()) {
                case RESERVED, RUNNING -> Component.translatable("status.immersive_brewing.alembic.running");
                case SEALED -> Component.translatable("status.immersive_brewing.alembic.sealed");
                case RECOVERY -> Component.translatable("status.immersive_brewing.recovery", Component.translatable(
                        ledger.recoveryReason() == null ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
            };
        }
        if (anyFinished()) return Component.translatable("status.immersive_brewing.alembic.finished");
        return loaded() ? Component.translatable("status.immersive_brewing.alembic.loaded", inputs().size())
                : Component.translatable("status.immersive_brewing.alembic.empty");
    }

    @Override
    protected boolean litNow() {
        if (ledger != null && ledger.physical()) {
            AlembicProcess p = liveProcess();
            return ledger.state() == JobState.RUNNING && p != null && p.clamped() && p.burner() > 0;
        }
        return ledger != null && ledger.state() == JobState.RUNNING;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.immersive_brewing.alembic");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new AlembicMenu(id, inventory, worldPosition);
    }
}
