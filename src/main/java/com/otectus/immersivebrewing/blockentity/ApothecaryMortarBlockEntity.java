package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.advancement.Milestone;
import com.otectus.immersivebrewing.advancement.ModAdvancements;
import com.otectus.immersivebrewing.api.event.ReagentPreparedEvent;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.menu.MortarMenu;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationScore;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModSounds;
import com.otectus.immersivebrewing.registry.ModTags;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.interaction.WorkshopIntent;
import com.otectus.immersivebrewing.interaction.WorkshopResult;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import com.otectus.immersivebrewing.workshop.WorkshopProfiles;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The Apothecary's Mortar (spec 6): one reagent bowl and a four-slot kit shelf. A preparation moves up to four
 * profiled reagents into its ledger; the commit turns exactly those into the same number of kits, all with the earned
 * extraction. Unprofiled reagents are refused: they brew at the raw extraction of 50 without preparation.
 */
public class ApothecaryMortarBlockEntity extends StationBlockEntity {
    public static final int INPUT = 0;
    public static final int OUTPUT = 1;
    public static final int OUTPUT_SIZE = 4;
    public static final int SLOTS = 5;

    /** Most reagent units one hands-on preparation works together (handoff 6). */
    public static final int BOWL_UNITS = MortarProcess.MAX_UNITS;

    // ---- hands-on workshop (server transient) ----
    @Nullable
    private MortarProcess process;
    private final ToolTrack pestleTrack = new ToolTrack(WorkshopParts.MORTAR_R, WorkshopParts.MORTAR_R);
    private final ToolTrack scraperTrack = new ToolTrack(WorkshopParts.MORTAR_R, WorkshopParts.MORTAR_R);
    private Effort pestleEffort = Effort.NORMAL;
    @Nullable
    private UUID lastActor;

    public ApothecaryMortarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.APOTHECARY_MORTAR.get(), pos, state, SLOTS);
    }

    @Override
    public ResourceLocation stationId() {
        return ImmersiveBrewing.id("apothecary_mortar");
    }

    @Override
    protected int slotLimit(int slot) {
        return 64;
    }

    @Override
    protected boolean canPlace(int slot, ItemStack stack) {
        if (slot != INPUT || ledger != null) return false;
        if (stack.is(ModTags.CARRIER_EXCLUDED) || stack.getItem() instanceof PotionItem || stack.is(ModItems.PREPARED_REAGENT_KIT.get())) return false;
        return true;
    }

    @Override
    protected boolean automationInsert(@Nullable Direction side, int slot, ItemStack stack) {
        return side != Direction.DOWN && slot == INPUT && canPlace(slot, stack);
    }

    @Override
    protected boolean automationExtract(@Nullable Direction side, int slot) {
        return (side == null || side == Direction.DOWN) && slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE;
    }

    @Override
    protected boolean isOutputSlot(int slot) {
        return slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE;
    }

    @Override
    protected boolean loaded() {
        return !inventory.getStackInSlot(INPUT).isEmpty();
    }

    @Override
    protected boolean emptyForRecovery() {
        return inventory.getStackInSlot(INPUT).isEmpty() && freeOutput() >= 0;
    }

    /** The preparation profile for the bowl's reagent, or null when it is unprofiled or excluded. */
    @Nullable
    public ReagentProfile profileFor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ProfileSelector.Choice<ReagentProfile> choice = BrewingDefinitions.server().reagentProfile(stack.getItem(), null, null);
        return choice != null && choice.value().preparation() != null ? choice.value() : null;
    }

    @Override
    public void handleAction(ServerPlayer player, StationActionC2S.Action action, int argument) {
        switch (action) {
            case START_PREPARATION -> {
                // The menu's start button now sets the reagents out in the bowl; the work happens at the apparatus.
                WorkshopResult r = beginPreparation(player, Math.max(1, StationActionC2S.base(argument)));
                if (r.reason() != null) notice(player, r.reason(), !r.accepted());
                if (r.accepted()) {
                    player.closeContainer();
                    openWorkingView(player);
                }
            }
            case RESUME -> {
                if (ledger != null && ledger.physical()) {
                    WorkshopResult r = unsealPhysical(player);
                    if (r.reason() != null) notice(player, r.reason(), true);
                    player.closeContainer();
                    openWorkingView(player);
                } else resume(player);
            }
            default -> super.handleAction(player, action, argument);
        }
    }

    /**
     * Sets out a preparation of {@code kits} units (the argument may carry a legacy assistance request, ignored now). New
     * preparations are always hands-on; the legacy minigame is reachable only to finish a batch saved by 1.1.
     */
    public void start(ServerPlayer player, int kits) {
        WorkshopResult r = beginPreparation(player, Math.max(1, StationActionC2S.base(kits)));
        if (r.reason() != null) notice(player, r.reason(), !r.accepted());
        if (r.accepted()) {
            player.closeContainer();
            openWorkingView(player);
        }
    }

    private CompoundTag display(ItemStack reagent, ResourceLocation profile, int kits) {
        CompoundTag tag = new CompoundTag();
        tag.put("reagent", LedgerCodec.saveStack(reagent.copyWithCount(1)));
        tag.putString("profile", profile.toString());
        tag.putInt("kits", kits);
        if(ledger!=null) {
            tag.putFloat("assistTime",Math.max(1,ledger.snapshot().getFloat("assistTime")));
            tag.putFloat("assistBand",Math.max(1,ledger.snapshot().getFloat("assistBand")));
        }
        return tag;
    }

    public void resume(ServerPlayer player) {
        if (ledger != null && ledger.physical()) {
            WorkshopResult r = unsealPhysical(player);
            if (r.reason() != null) notice(player, r.reason(), true);
            return;
        }
        revalidateRecovery();
        if (ledger == null || ledger.state() != JobState.SEALED || ledger.operation() != Operation.PREPARE) {
            notice(player, Component.translatable("notice.immersive_brewing.nothing_to_resume"), true);
            return;
        }
        if (SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        if (freeOutput() < 0) {
            notice(player, Component.translatable("notice.immersive_brewing.mortar.shelf_full"), true);
            return;
        }
        SimHandle sim;
        try {
            sim = SimHandles.create(SessionKind.PREPARATION, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            ledger.hold("recovery.immersive_brewing.state_unreadable");
            changed();
            return;
        }
        player.closeContainer();
        ItemStack reagent = ledger.inputs().isEmpty() ? ItemStack.EMPTY : ledger.inputs().get(0);
        SessionManager.begin(player, this, sim, true, display(reagent, new ResourceLocation(ledger.snapshot().getString("profile")),
                ledger.snapshot().getInt("kits")));
    }

    private int freeOutput() {
        for (int i = 0; i < OUTPUT_SIZE; i++) if (inventory.getStackInSlot(OUTPUT + i).isEmpty()) return OUTPUT + i;
        return -1;
    }

    /** Releases old completed jobs held only by a full shelf; unreadable jobs remain safely held. */
    @Override
    public void revalidateRecovery() {
        if (ledger == null || ledger.unknownSchema() != null || ledger.operation() != Operation.PREPARE
                || ledger.state() != JobState.RECOVERY || !"recovery.immersive_brewing.no_room".equals(ledger.recoveryReason())
                || freeOutput() < 0) return;
        if (ledger.physical()) {
            ledger.clearRecovery();
            ledger.setState(JobState.SEALED);
            changed();
            return;
        }
        if (ledger.inputs().isEmpty() || ResourceLocation.tryParse(ledger.snapshot().getString("profile")) == null) return;
        try {
            SimHandles.create(SessionKind.PREPARATION, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            return;
        }
        ledger.clearRecovery();
        ledger.setState(JobState.SEALED);
        changed();
    }

    @Override
    protected void onInventoryChanged(int slot) {
        super.onInventoryChanged(slot);
        if (slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE && level != null && !level.isClientSide) revalidateRecovery();
    }

    @Override
    protected void cancelReservation() {
        if (ledger == null) return;
        JobLedger l = ledger;
        ledger = null;
        for (ItemStack s : l.inputs()) {
            ItemStack live = inventory.getStackInSlot(INPUT);
            if (live.isEmpty()) putDirect(INPUT, s.copy());
            else if (ItemStack.isSameItemSameTags(live, s)) putDirect(INPUT, live.copyWithCount(live.getCount() + s.getCount()));
            else if (level != null) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, s.copy());
        }
        changed();
    }

    @Override
    @Nullable
    public CompoundTag completeJob(@Nullable ServerPlayer player, SimHandle sim) {
        if (ledger == null || ledger.operation() != Operation.PREPARE || ledger.state() != JobState.RUNNING) return null;
        if (!(sim instanceof SimHandles.Preparation prep)) return null;
        PreparationScore score = prep.sim().score();
        int slot = freeOutput();
        ResourceLocation profile = ResourceLocation.tryParse(ledger.snapshot().getString("profile"));
        if (slot < 0 || profile == null || ledger.inputs().isEmpty()) {
            ledger.setSimState(sim.saveState());
            ledger.hold(slot < 0 ? "recovery.immersive_brewing.no_room" : "recovery.immersive_brewing.state_unreadable");
            changed();
            return null;
        }
        JobLedger l = ledger;
        ledger = null;
        ItemStack source = l.inputs().get(0);
        int kits = source.getCount();
        ItemStack output = new KitData(source.copyWithCount(1), 1, profile, score.extraction()).toStack(kits);
        putDirect(slot, output);
        finished[slot] = true;
        lastResult = new StationResult(Operation.PREPARE, output.copyWithCount(1), kits, score.extraction(), score.extraction(), 0,
                score.tipKey(), List.of(), l.brewerName());
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), sim.step(), 0, 0);
        MinecraftForge.EVENT_BUS.post(new ReagentPreparedEvent(player, worldPosition, output, score.extraction()));
        if (player != null) ModAdvancements.milestone(player, Milestone.PREPARE_KIT);
        CompoundTag result = new CompoundTag();
        result.putString("kind", "preparation");
        result.put("output", LedgerCodec.saveStack(output.copyWithCount(1)));
        result.putInt("count", kits);
        result.putInt("E", score.extraction());
        result.putDouble("accuracy", score.accuracy());
        result.putDouble("restraint", score.restraint());
        result.putString("texture", "texture.immersive_brewing." + score.texture().name().toLowerCase(java.util.Locale.ROOT));
        result.putInt("folds", score.folds());
        result.putString("tip", score.tipKey());
        return result;
    }

    @Override
    protected Component status() {
        if (ledger != null) {
            if (ledger.physical() && ledger.state() == JobState.RUNNING) {
                MortarProcess p = liveProcess();
                if (p != null) return Component.translatable("status.immersive_brewing.mortar.working", Component.translatable(p.cueKey()));
            }
            return switch (ledger.state()) {
                case RESERVED, RUNNING -> Component.translatable("status.immersive_brewing.mortar.running");
                case SEALED -> Component.translatable("status.immersive_brewing.mortar.sealed");
                case RECOVERY -> Component.translatable("status.immersive_brewing.recovery", Component.translatable(
                        ledger.recoveryReason() == null ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
            };
        }
        ItemStack input = inventory.getStackInSlot(INPUT);
        if (input.isEmpty()) return anyFinished() ? Component.translatable("status.immersive_brewing.mortar.kits_ready")
                : Component.translatable("status.immersive_brewing.mortar.empty");
        if (profileFor(input) == null) return Component.translatable("status.immersive_brewing.mortar.unprofiled", input.getHoverName());
        return Component.translatable("status.immersive_brewing.mortar.loaded", input.getCount(), input.getHoverName());
    }

    @Override
    protected boolean litNow() {
        return false;
    }

    // ---- hands-on workshop ----

    @Override
    public WorkshopKind workshopKind() {
        return WorkshopKind.MORTAR;
    }

    @Nullable
    public MortarProcess liveProcess() {
        if (ledger == null || !ledger.physical() || ledger.operation() != Operation.PREPARE) return null;
        if (process == null) {
            try {
                process = MortarProcess.load(ledger.process());
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

    @Override
    protected void flushProcess() {
        if (ledger != null && ledger.physical() && process != null) ledger.setProcess(process.save());
    }

    @Override
    protected java.util.List<UUID> workshopParticipants() {
        MortarProcess p = liveProcess();
        return p == null ? java.util.List.of() : p.participants();
    }

    @Override
    protected void participate(ServerPlayer player) {
        lastActor = player.getUUID();
        MortarProcess p = liveProcess();
        if (p != null) p.participate(player.getUUID());
        if (ledger != null && !player.getUUID().equals(ledger.brewerId())) ledger.addAssistant(player.getGameProfile().getName());
    }

    @Override
    protected void onPhysicalSealed() {
        pestleTrack.reset();
        scraperTrack.reset();
    }

    @Override
    protected void onLeasesLapsed() {
        if (level == null) return;
        long now = level.getGameTime();
        if (leases.holder(WorkshopParts.M_PESTLE, now) == null) pestleTrack.reset();
        if (leases.holder(WorkshopParts.M_SCRAPER, now) == null) scraperTrack.reset();
    }

    @Override
    @Nullable
    protected String physicalResumeProblem() {
        if (liveProcess() == null) return "recovery.immersive_brewing.process_unreadable";
        return null;
    }

    @Override
    protected void resumeLegacy(ServerPlayer player) {
        resume(player);
    }

    @Override
    protected void physicalStep(long now) {
        MortarProcess p = liveProcess();
        if (p == null) return;
        if (leases.holder(WorkshopParts.M_PESTLE, now) != null) {
            double px = pestleTrack.take(now);
            if (px > 0) {
                p.grind(px, pestleTrack.u(), pestleTrack.v(), pestleEffort.value);
                recentWork += px;
            }
        }
        if (leases.holder(WorkshopParts.M_SCRAPER, now) != null) {
            double fx = scraperTrack.take(now);
            if (fx > 0) p.fold(fx);
        }
        p.tick();
        markWorkshopDirty(false);
    }

    /**
     * Sets the bowl's reagents out as one preparation (handoff 6): up to four units move into the ledger, with the
     * resolved physical profile snapshotted. Unprofiled reagents are refused (they brew at the raw baseline).
     */
    public WorkshopResult beginPreparation(ServerPlayer player, int wanted) {
        if (level == null) return WorkshopResult.IGNORED;
        if (ServerConfig.disabled()) return WorkshopResult.refuse("notice.immersive_brewing.disabled");
        if (ledger != null || SessionManager.stationBusy(this)) return WorkshopResult.refuse("notice.immersive_brewing.busy");
        ItemStack input = inventory.getStackInSlot(INPUT);
        ReagentProfile profile = profileFor(input);
        if (profile == null) {
            return WorkshopResult.refuse(input.isEmpty() ? "notice.immersive_brewing.mortar.empty" : "notice.immersive_brewing.mortar.unprofiled");
        }
        var prep = WorkshopProfiles.preparation(input.getItem(), profile);
        if (prep == null) return WorkshopResult.refuse("notice.immersive_brewing.mortar.unprofiled");
        if (freeOutput() < 0) return WorkshopResult.refuse("notice.immersive_brewing.mortar.shelf_full");
        int count = Math.max(1, Math.min(Math.min(BOWL_UNITS, wanted), input.getCount()));
        ItemStack taken = input.copyWithCount(count);
        putDirect(INPUT, input.copyWithCount(input.getCount() - count));
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("profile", profile.id().toString());
        snapshot.putInt("kits", count);
        snapshot.putString("workshopProfile", prep.source());
        snapshot.putBoolean("workshopFallback", prep.fallback());
        long seed = ((ServerLevel) level).getRandom().nextLong();
        int generation = BrewingDefinitions.server().generation();
        MortarProcess p = MortarProcess.begin(WorkshopProfiles.mortarSetup(prep, count, generation), player.getUUID());
        ledger = new JobLedger(UUID.randomUUID(), Operation.PREPARE, JobState.RUNNING, List.of(taken), List.of(), 0, snapshot,
                new KitData(taken, 1, profile.id(), 0).toStack(count), new long[0], new long[0], seed, 0, player.getUUID(),
                player.getGameProfile().getName(), List.of(), null, 0, generation, 0);
        ledger.setProcess(p.save());
        process = p;
        lastActor = player.getUUID();
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, ledger.jobId(), 0, count, 0);
        markWorkshopDirty(true);
        notice(player, Component.translatable("workshop.immersive_brewing.mortar.begun", count, taken.getHoverName()), false);
        return WorkshopResult.ACCEPTED;
    }

    /** Packaging (handoff 6): scrape the prepared material into the dose cup; commits the kits exactly once. */
    private WorkshopResult packageKits(@Nullable ServerPlayer player) {
        MortarProcess p = liveProcess();
        if (p == null || ledger == null) return WorkshopResult.IGNORED;
        MortarProcess.Evaluation e = p.evaluate();
        if (!e.usable()) return WorkshopResult.refuse("workshop.immersive_brewing.mortar.not_usable", Component.translatable(e.textureKey()));
        int slot = freeOutput();
        ResourceLocation profile = ResourceLocation.tryParse(ledger.snapshot().getString("profile"));
        if (slot < 0) return WorkshopResult.refuse("notice.immersive_brewing.mortar.shelf_full");
        if (profile == null || ledger.inputs().isEmpty()) {
            flushProcess();
            ledger.hold("recovery.immersive_brewing.state_unreadable");
            changed();
            return WorkshopResult.refuse("recovery.immersive_brewing.state_unreadable");
        }
        JobLedger l = ledger;
        ledger = null;
        process = null;
        leases.clear();
        pestleTrack.reset();
        scraperTrack.reset();
        ItemStack source = l.inputs().get(0);
        int kits = source.getCount();
        int extraction = e.extraction();
        ItemStack output = new KitData(source.copyWithCount(1), 1, profile, extraction).toStack(kits);
        putDirect(slot, output);
        finished[slot] = true;
        String tip = p.tipKey();
        lastResult = new StationResult(Operation.PREPARE, output.copyWithCount(1), kits, extraction, extraction, 0, tip, List.of(), l.brewerName());
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COMPLETE, l.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        ServerPlayer actor = player;
        if (actor == null && lastActor != null && level != null && level.getServer() != null) {
            actor = com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), lastActor);
        }
        MinecraftForge.EVENT_BUS.post(new ReagentPreparedEvent(actor, worldPosition, output, extraction));
        if (actor != null) {
            ModAdvancements.milestone(actor, Milestone.PREPARE_KIT);
            CompoundTag result = new CompoundTag();
            result.putString("kind", "preparation");
            result.put("output", LedgerCodec.saveStack(output.copyWithCount(1)));
            result.putInt("count", kits);
            result.putInt("E", extraction);
            result.putDouble("accuracy", e.accuracy());
            result.putDouble("restraint", e.restraint());
            result.putString("texture", e.textureKey());
            result.putInt("folds", 0);
            result.putString("tip", tip);
            result.putBoolean("physical", true);
            com.otectus.immersivebrewing.guide.PlayerGuide.recordResult(actor, result);
            notice(actor, Component.translatable("workshop.immersive_brewing.mortar.packaged", kits, source.getHoverName(), extraction,
                    Component.translatable(tip)), false);
        }
        return WorkshopResult.ACCEPTED;
    }

    @Override
    protected InteractionResult directInsert(net.minecraft.world.entity.player.Player player, InteractionHand hand, ItemStack held, @Nullable WorkshopPart part) {
        boolean client = level == null || level.isClientSide;
        boolean preparable = held.getItem() != ModItems.PREPARED_REAGENT_KIT.get() && !(held.getItem() instanceof PotionItem)
                && (client ? com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.isIngredient(held) : profileFor(held) != null
                || com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.isIngredient(held));
        if (!preparable) return InteractionResult.PASS;
        if (client) return InteractionResult.SUCCESS;
        if (profileFor(held) == null) {
            notice(player, Component.translatable("notice.immersive_brewing.mortar.unprofiled"), true);
            return InteractionResult.CONSUME;
        }
        if (ledger != null) {
            notice(player, Component.translatable("workshop.immersive_brewing.mortar.bowl_busy"), true);
            return InteractionResult.CONSUME;
        }
        ItemStack bowl = inventory.getStackInSlot(INPUT);
        if (!bowl.isEmpty() && !ItemStack.isSameItemSameTags(bowl, held)) {
            notice(player, Component.translatable("workshop.immersive_brewing.mortar.bowl_other"), true);
            return InteractionResult.CONSUME;
        }
        int room = Math.max(0, BOWL_UNITS - bowl.getCount());
        if (room == 0) {
            notice(player, Component.translatable("workshop.immersive_brewing.mortar.bowl_full", BOWL_UNITS), true);
            return InteractionResult.CONSUME;
        }
        // One unit per use: the amount is chosen by placing it (handoff 6).
        putDirect(INPUT, held.copyWithCount(bowl.getCount() + 1));
        if (!player.getAbilities().instabuild) held.shrink(1);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.LOAD, StationVisual.NONE, 0, 0, 0);
        markWorkshopDirty(true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected WorkshopResult act(ServerPlayer player, WorkshopIntent intent, long now) {
        int part = intent.part();
        switch (intent.action()) {
            case INSERT -> {
                ItemStack held = player.getItemInHand(intent.hand());
                if (held.isEmpty()) return WorkshopResult.IGNORED;
                int before = held.getCount();
                InteractionResult r = directInsert(player, intent.hand(), held, WorkshopParts.get(WorkshopKind.MORTAR, part));
                if (r == InteractionResult.PASS) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.not_here");
                if (intent.batch()) for (int i = 1; i < BOWL_UNITS && held.getCount() > 0 && held.getCount() < before; i++) {
                    int n = held.getCount();
                    directInsert(player, intent.hand(), held, WorkshopParts.get(WorkshopKind.MORTAR, part));
                    if (held.getCount() == n) break;
                }
                return WorkshopResult.ACCEPTED;
            }
            case TAKE -> {
                return take(player, part);
            }
            case SEAL -> {
                if (ledger == null || !ledger.physical() || ledger.state() != JobState.RUNNING) return WorkshopResult.IGNORED;
                sealPhysical(player, "workshop.immersive_brewing.seal.manual");
                return WorkshopResult.ACCEPTED;
            }
            case UNSEAL -> {
                return unsealPhysical(player);
            }
            case BEGIN -> {
                return beginPreparation(player, intent.argument() <= 0 ? BOWL_UNITS : intent.argument());
            }
            default -> { }
        }
        if (ledger != null && ledger.state() == JobState.SEALED) {
            return ledger.physical() ? WorkshopResult.refuse("workshop.immersive_brewing.sealed.take_pestle") : WorkshopResult.refuse("workshop.immersive_brewing.legacy.finish_first");
        }
        if (ledger != null && ledger.state() == JobState.RECOVERY) {
            return WorkshopResult.refuse("notice.immersive_brewing.held", Component.translatable(ledger.recoveryReason() == null
                    ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
        }
        boolean tool = part == WorkshopParts.M_PESTLE || part == WorkshopParts.M_SCRAPER;
        switch (intent.action()) {
            case GRAB -> {
                if (!tool) return WorkshopResult.IGNORED;
                if (ledger == null && part == WorkshopParts.M_PESTLE && profileFor(inventory.getStackInSlot(INPUT)) == null) {
                    return WorkshopResult.refuse(inventory.getStackInSlot(INPUT).isEmpty() ? "notice.immersive_brewing.mortar.empty"
                            : "notice.immersive_brewing.mortar.unprofiled");
                }
                if (ledger == null && part == WorkshopParts.M_SCRAPER) return WorkshopResult.refuse("workshop.immersive_brewing.mortar.nothing_to_fold");
                var other = leases.acquire(part, player.getUUID(), player.getGameProfile().getName(), now);
                if (other != null) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.held_by", other.name());
                (part == WorkshopParts.M_PESTLE ? pestleTrack : scraperTrack).reset();
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.GRAB, ledger == null ? StationVisual.NONE : ledger.jobId(), 0, part, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case RELEASE -> {
                if (!tool || !leases.release(part, player.getUUID())) return WorkshopResult.IGNORED;
                (part == WorkshopParts.M_PESTLE ? pestleTrack : scraperTrack).reset();
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.DROP, ledger == null ? StationVisual.NONE : ledger.jobId(), 0, part, 0);
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case SAMPLE -> {
                if (!tool || !leases.holds(part, player.getUUID(), now)) return WorkshopResult.IGNORED;
                if (intent.contact() && ledger == null && part == WorkshopParts.M_PESTLE) {
                    // The first contact with the material begins the preparation: from here the work is irreversible.
                    WorkshopResult begun = beginPreparation(player, BOWL_UNITS);
                    if (!begun.accepted()) return begun;
                }
                if (ledger == null || ledger.state() != JobState.RUNNING) return WorkshopResult.IGNORED;
                if (part == WorkshopParts.M_PESTLE) pestleEffort = intent.effort();
                (part == WorkshopParts.M_PESTLE ? pestleTrack : scraperTrack).sample(intent.x(), intent.z(), intent.contact(), now);
                if (intent.contact()) participate(player);
                markWorkshopDirty(false);
                return WorkshopResult.ACCEPTED;
            }
            case PRESS -> {
                if (!leases.holds(WorkshopParts.M_PESTLE, player.getUUID(), now)) return WorkshopResult.refuse("workshop.immersive_brewing.mortar.take_pestle");
                if (ledger == null) {
                    WorkshopResult begun = beginPreparation(player, BOWL_UNITS);
                    if (!begun.accepted()) return begun;
                }
                MortarProcess p = liveProcess();
                if (p == null || ledger.state() != JobState.RUNNING) return WorkshopResult.IGNORED;
                if ((p.setup().operations() & MortarProcess.OP_PRESS) == 0) return WorkshopResult.refuse("workshop.immersive_brewing.mortar.no_press");
                double u = Math.max(-1, Math.min(1, intent.x())), v = Math.max(-1, Math.min(1, intent.z()));
                if (!p.press(u, v, intent.effort().value, now)) return WorkshopResult.IGNORED;
                pestleEffort = intent.effort();
                participate(player);
                effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.PRESS, ledger.jobId(), 0, MortarProcess.zoneAt(u, v), intent.effort().ordinal());
                markWorkshopDirty(true);
                return WorkshopResult.ACCEPTED;
            }
            case FINISH -> {
                if (part != WorkshopParts.M_CUP) return WorkshopResult.IGNORED;
                return packageKits(player);
            }
            default -> {
                return WorkshopResult.IGNORED;
            }
        }
    }

    private WorkshopResult take(ServerPlayer player, int part) {
        int slot;
        if (part >= WorkshopParts.M_SHELF && part < WorkshopParts.M_SHELF + OUTPUT_SIZE) slot = OUTPUT + part - WorkshopParts.M_SHELF;
        else if (part == WorkshopParts.M_BOWL && ledger == null) slot = INPUT;
        else return WorkshopResult.IGNORED;
        ItemStack s = inventory.getStackInSlot(slot);
        if (s.isEmpty()) return WorkshopResult.IGNORED;
        if (!com.otectus.immersivebrewing.interaction.RackWorkshop.fits(player.getInventory(), s)) return WorkshopResult.refuse("workshop.immersive_brewing.refuse.inventory_full");
        ItemStack taken = s.copy();
        putDirect(slot, ItemStack.EMPTY);
        finished[slot] = false;
        com.otectus.immersivebrewing.interaction.RackWorkshop.give(player, taken);
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.COLLECT, StationVisual.NONE, 0, 0, 0);
        markWorkshopDirty(true);
        return WorkshopResult.ACCEPTED;
    }

    private double recentWork;

    @Override
    @Nullable
    protected StationVisual physicalVisual() {
        MortarProcess p = liveProcess();
        if (p == null) return null;
        MortarProcess.Evaluation e = p.evaluate();
        double work = recentWork;
        recentWork = 0;
        boolean pressing = level != null && leases.holder(WorkshopParts.M_PESTLE, level.getGameTime()) != null && pestleTrack.contact();
        return new StationVisual(0, 0, 0, 0, (float) e.clump(), (float) e.processed(), true).details(
                new StationVisual.Mortar(p.setup().mode().ordinal(), (float) e.processed(), (float) pestleEffort.value, (float) e.clump(), 0,
                        (float) pestleTrack.u(), (float) pestleTrack.v(), pressing, (float) (work * 0.002), p.presses()),
                StationVisual.Kettle.EMPTY, StationVisual.Alembic.EMPTY, (int) (level == null ? 0 : level.getGameTime() / 10), Operation.PREPARE);
    }

    @Override
    protected int physicalComparator() {
        MortarProcess p = liveProcess();
        return p != null && p.evaluate().usable() ? 9 : 8;
    }

    @Override
    protected void writeToolPose(int part, CompoundTag tag) {
        ToolTrack track = part == WorkshopParts.M_PESTLE ? pestleTrack : part == WorkshopParts.M_SCRAPER ? scraperTrack : null;
        if (track == null) return;
        tag.putFloat("x", (float) track.u());
        tag.putFloat("z", (float) track.v());
        tag.putBoolean("contact", track.contact());
    }

    @Override
    protected void writeWorkshopSnapshot(CompoundTag t) {
        ItemStack bowl = ledger != null && !ledger.inputs().isEmpty() ? ledger.inputs().get(0) : inventory.getStackInSlot(INPUT);
        if (!bowl.isEmpty()) {
            CompoundTag item = LedgerCodec.saveStack(bowl.copyWithCount(1));
            item.remove("FullCount");
            t.put("reagent", item);
            t.putInt("units", Math.min(64, bowl.getCount()));
            t.putBoolean("preparable", profileFor(bowl) != null);
        }
        MortarProcess p = liveProcess();
        if (p != null) {
            CompoundTag m = new CompoundTag();
            p.writeSnapshot(m);
            t.put("mortar", m);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (level == null || !level.isClientSide) {
            process = null;
            pestleTrack.reset();
            scraperTrack.reset();
        }
    }

    @Override
    protected void afterRecoveryInstalled() {
        process = null;
        leases.clear();
        super.afterRecoveryInstalled();
        markWorkshopDirty(true);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.immersive_brewing.apothecary_mortar");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MortarMenu(id, inventory, worldPosition);
    }
}
