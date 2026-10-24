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

    public PurifyPlan purifyPlan() {
        if (!ServerConfig.get(ServerConfig.ALLOW_PURIFICATION)) return refuse("alembic.immersive_brewing.purify.disabled");
        List<ItemStack> in = inputs();
        if (in.isEmpty()) return refuse("alembic.immersive_brewing.purify.empty");
        ItemStack first = in.get(0);
        if (BrewingDefinitions.server().exclusions().excludesItem(first)) return refuse("alembic.immersive_brewing.purify.unsupported");
        Optional<BrewingMetadata> meta = BrewingMetadata.read(first);
        for (ItemStack s : in) {
            if (!PotionContentsService.sameCanonical(first, s) || !BrewingMetadata.read(s).equals(meta)) return refuse("alembic.immersive_brewing.purify.mixed");
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

    private static PurifyPlan refuse(String key) {
        return new PurifyPlan(null, ItemStack.EMPTY, 0, 0, Component.translatable(key));
    }

    @Nullable
    private DistillationRecipe distillationFor(ItemStack stack) {
        if (level == null) return null;
        RecipeManager recipes = level.getRecipeManager();
        BrewingDefinitions defs = BrewingDefinitions.server();
        return recipes.getAllRecipesFor(ModRecipeTypes.DISTILLATION.get()).stream()
                .filter(r -> !defs.exclusions().excludesRecipe(r.getId()) && r.input().matches(stack))
                .min(Comparator.comparing(r -> r.getId().toString())).orElse(null);
    }

    @Nullable
    private ReclamationRecipe reclamationFor(ItemStack stack) {
        if (level == null) return null;
        BrewingDefinitions defs = BrewingDefinitions.server();
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
        if (level == null) return;
        if (ServerConfig.disabled()) {
            notice(player, Component.translatable("notice.immersive_brewing.disabled"), true);
            return;
        }
        if (ledger != null || SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        PurifyPlan plan = purifyPlan();
        if (plan.problem() != null || plan.recipe() == null) {
            notice(player, plan.problem() == null ? Component.translatable("alembic.immersive_brewing.purify.unsupported") : plan.problem(), true);
            return;
        }
        DistillationRecipe recipe = plan.recipe();
        int fuel = recipe.fuelCharges() * plan.groups();
        spendFuel(fuel);
        List<ItemStack> taken = takeInputs();
        long seed = ((ServerLevel) level).getRandom().nextLong();
        double[] assist = approvedAssist(argument);
        long[] params = DistillationParams.create(seed, assist[0], assist[1]).encode();
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("recipe", recipe.getId().toString());
        snapshot.putInt("groups", plan.groups());
        snapshot.putInt("groupSize", recipe.groupSize());
        snapshot.putInt("yield", recipe.yield());
        snapshot.putInt("cap", Math.min(recipe.qualityCap(), ServerConfig.get(ServerConfig.PURIFICATION_QUALITY_CAP)));
        snapshot.putString("container", String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(recipe.container())));
        snapshot.putInt("qualityIn", plan.qualityIn());
        ledger = new JobLedger(UUID.randomUUID(), Operation.PURIFY, JobState.RESERVED, taken, List.of(), fuel, snapshot,
                plan.sample().copyWithCount(1), params, new long[0], seed, 0, player.getUUID(), player.getGameProfile().getName(),
                List.of(), null, 0, BrewingDefinitions.server().generation(), 0);
        changed();
        SimHandle sim = SimHandles.create(SessionKind.DISTILLATION, params, null);
        player.closeContainer();
        SessionManager.begin(player, this, sim, false, display(plan.sample(), plan.groups(), plan.qualityIn()));
    }

    private CompoundTag display(ItemStack sample, int groups, int qualityIn) {
        CompoundTag tag = new CompoundTag();
        tag.put("input", LedgerCodec.saveStack(sample.copyWithCount(1)));
        tag.putInt("groups", groups);
        tag.putInt("qualityIn", qualityIn);
        return tag;
    }

    public void resume(ServerPlayer player) {
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
        SessionManager.begin(player, this, sim, true, display(ledger.outputSnapshot(), ledger.snapshot().getInt("groups"),
                ledger.snapshot().getInt("qualityIn")));
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
        if (level != null) level.playSound(null, worldPosition, ModSounds.BOTTLE_CLINK.get(), SoundSource.BLOCKS, 0.8F, 1.0F);
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

    // ---- reclamation (passive) ----

    public void startReclamation(ServerPlayer player) {
        if (level == null) return;
        if (!ServerConfig.get(ServerConfig.ALLOW_RECLAMATION) || ServerConfig.disabled()) {
            notice(player, Component.translatable("alembic.immersive_brewing.reclaim.disabled"), true);
            return;
        }
        if (ledger != null) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        List<ItemStack> in = inputs();
        if (in.isEmpty()) {
            notice(player, Component.translatable("alembic.immersive_brewing.purify.empty"), true);
            return;
        }
        ReclamationRecipe recipe = reclamationFor(in.get(0));
        if (recipe == null || in.stream().anyMatch(s -> !recipe.input().matches(s) || BrewingMetadata.hasUnreadableCompound(s)
                || BrewingDefinitions.server().exclusions().excludesItem(s))) {
            notice(player, Component.translatable("alembic.immersive_brewing.reclaim.unsupported"), true);
            return;
        }
        int groups = in.size() / recipe.groupSize();
        if (groups < 1) {
            notice(player, Component.translatable("alembic.immersive_brewing.purify.group", recipe.groupSize()), true);
            return;
        }
        int fuel = recipe.fuelCharges() * groups;
        if (fuelAvailable() < fuel) {
            notice(player, Component.translatable("plan.immersive_brewing.missing.fuel", fuel, fuelAvailable()), true);
            return;
        }
        int used = groups * recipe.groupSize();
        if (freeOutputs() < 2) {
            notice(player, Component.translatable("alembic.immersive_brewing.output_full"), true);
            return;
        }
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
        notice(player, Component.translatable("alembic.immersive_brewing.reclaim.started", used), false);
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
        if (level != null) level.playSound(null, worldPosition, ModSounds.CONDENSATE_DROP.get(), SoundSource.BLOCKS, 0.8F, 1.0F);
        ServerPlayer player = level != null && level.getServer() != null && l.brewerId() != null ? level.getServer().getPlayerList().getPlayer(l.brewerId()) : null;
        MinecraftForge.EVENT_BUS.post(new BatchRefinedEvent(player, worldPosition, result, result.getCount() * groups, false, 0, 0));
        if (player != null) ModAdvancements.milestone(player, Milestone.RECLAIM);
    }

    @Override
    protected void cancelReservation() {
        if (ledger == null) return;
        JobLedger l = ledger;
        ledger = null;
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
