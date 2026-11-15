package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.item.RecoveryBatchItem;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared station behaviour (spec 5, 20, 22): a bounded public inventory, an optional job ledger that owns everything
 * reserved, a fuel-charge counter, the last result, and a compact client view. The full ledger never leaves the
 * server; clients receive {@link StationView} (spec 19: a small synced view, never the nested ledger).
 *
 * <p>Ownership rules enforced here: while a job is open, slots the job reserved refuse insertion and automation can
 * only take completed, unreserved output. Breaking the station seals any session, packages an open ledger into one
 * Recovery Batch and drops the unrelated inventory once.
 */
public abstract class StationBlockEntity extends BlockEntity implements MenuProvider {
    /** Visual sync while a session runs: every half second. */
    private static final int VISUAL_SYNC_TICKS = 10;

    protected final StationInventory inventory;
    @Nullable
    protected JobLedger ledger;
    protected int fuelCharges;
    @Nullable
    protected StationResult lastResult;
    protected boolean statusComparator;
    /** Rack or output slots holding completed output (the only rack stacks automation may take). */
    protected final boolean[] finished;

    // Server transient
    @Nullable
    private String controllerName;
    private StationVisual visual = StationVisual.IDLE;
    private UUID incarnation = UUID.randomUUID();
    private long visualRevision;
    private long effectSequence;
    private UUID colorJob = StationVisual.NONE;
    private int[] stageColors = new int[] {0x385DC6};
    private boolean syncQueued;
    /** A full update owed to tracking clients, flushed by the next server tick (see {@link #sendUpdate()}). */
    private boolean updatePending;
    private int syncCooldown;

    // ---- hands-on workshop (server transient) ----
    /** Short tool and control leases (transient: they clear on load). */
    protected final com.otectus.immersivebrewing.workshop.Leases leases = new com.otectus.immersivebrewing.workshop.Leases();
    private int workshopGrace;
    private boolean snapshotDirty;
    private boolean snapshotUrgent;
    private int snapshotCooldown;
    private long snapshotRevision;
    /** Ticks with no authorised participant in range before an attended batch seals (handoff 16: about two seconds). */
    public static final int GRACE_TICKS = 40;
    private static final int SNAPSHOT_TICKS = 3;

    // Client view
    protected StationView view = StationView.EMPTY;
    protected net.minecraft.nbt.CompoundTag workshopView = new net.minecraft.nbt.CompoundTag();
    private long workshopViewRevision = -1;

    private final Map<Direction, LazyOptional<IItemHandler>> sided = new EnumMap<>(Direction.class);
    private LazyOptional<IItemHandler> unsided;

    protected StationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int slots) {
        super(type, pos, state);
        this.inventory = new StationInventory(this, slots);
        this.finished = new boolean[slots];
        createHandlers();
    }

    // ---- per-station rules ----

    public abstract ResourceLocation stationId();

    protected abstract int slotLimit(int slot);

    /** Whether a stack may enter a slot through a menu or a handler right now. */
    protected abstract boolean canPlace(int slot, ItemStack stack);

    /** Whether a public slot may be extracted through a menu or handler right now. */
    public boolean canTake(int slot) {
        return true;
    }

    /** Automation insertion: face and slot rules on top of {@link #canPlace}. */
    protected abstract boolean automationInsert(@Nullable Direction side, int slot, ItemStack stack);

    /** Automation extraction: only completed, unreserved output. */
    protected abstract boolean automationExtract(@Nullable Direction side, int slot);

    /** The station's one-line status, built from the server state (also sent to the client view). */
    protected abstract Component status();

    /** Whether any job-relevant contents are present (for the status comparator). */
    protected abstract boolean loaded();

    /** Station-specific interaction (special held items); PASS opens the menu. */
    protected InteractionResult useWith(Player player, InteractionHand hand, ItemStack held) {
        return InteractionResult.PASS;
    }

    // ---- accessors ----

    public StationInventory inventory() {
        return inventory;
    }

    @Nullable
    public JobLedger ledger() {
        return ledger;
    }

    public int fuelCharges() {
        return fuelCharges;
    }

    /** Whether a slot holds completed output (synced: the client reads it to tell finished bottles from staged ones). */
    public boolean finishedAt(int slot) {
        return slot >= 0 && slot < finished.length && finished[slot];
    }

    @Nullable
    public StationResult lastResult() {
        return lastResult;
    }

    public StationView view() {
        return view;
    }

    public boolean hasOpenJob() {
        return ledger != null;
    }

    public boolean jobActive() {
        return ledger != null && ledger.active();
    }

    @Nullable
    public String controllerName() {
        return controllerName;
    }

    public void setController(@Nullable String name) {
        if (java.util.Objects.equals(controllerName, name)) return;
        controllerName = name;
        queueSync();
    }

    public void setVisual(StationVisual value) {
        publishVisual(value, value.activity() == StationVisual.Activity.IDLE && value.active()
                ? StationVisual.Activity.WORKING : value.activity());
    }

    public void publishVisual(StationVisual value, StationVisual.Activity activity) {
        if (level == null || level.isClientSide) return;
        Operation operation = ledger == null ? (anyFinished() && lastResult != null ? lastResult.operation() : value.operation()) : ledger.operation();
        if (ledger == null) activity = anyFinished() ? StationVisual.Activity.OUTPUT_READY : StationVisual.Activity.IDLE;
        else if (ledger.state() == JobState.RECOVERY) activity = StationVisual.Activity.RECOVERY;
        else if (ledger.state() == JobState.SEALED && activity != StationVisual.Activity.RESUME_COUNT_IN
                && activity != StationVisual.Activity.HELP_PAUSED) activity = StationVisual.Activity.SEALED;
        if (ledger != null && (operation == Operation.BREW || operation == Operation.FINISH)) {
            if (!ledger.jobId().equals(colorJob)) {
                stageColors = StationPresentations.stageColors(ledger);
                colorJob = ledger.jobId();
            }
            var id = ResourceLocation.tryParse(ledger.snapshot().getString("profile"));
            var profile = com.otectus.immersivebrewing.data.BrewingDefinitions.server().brewProfile(id == null
                    ? com.otectus.immersivebrewing.data.BrewingDefinitions.GENERIC_PROFILE : id);
            int index = Math.min(value.kettle().additionIndex(), stageColors.length - 1);
            value = value.appearance(profile.accentColor(), profile.particle(), stageColors[index], stageColors[Math.min(index + 1, stageColors.length - 1)]);
        }
        var session = SessionManager.byStation(net.minecraft.core.GlobalPos.of(level.dimension(), worldPosition)).orElse(null);
        UUID job = ledger == null ? (anyFinished() ? visual.jobId() : StationVisual.NONE) : ledger.jobId();
        StationVisual next = value.withContext(new StationVisual.Context(incarnation, job, ++visualRevision,
                level.getGameTime(), value.simulationStep(), operation, activity,
                session == null ? StationVisual.NONE : session.playerId(), ledger == null ? 0 : ownedServings()));
        boolean transition = visual.activity() != next.activity() || !visual.jobId().equals(next.jobId());
        visual = next;
        com.otectus.immersivebrewing.network.ModNetwork.tracking(this,
                new com.otectus.immersivebrewing.network.packet.StationVisualS2C(worldPosition, visual));
        if (transition) { queueSync(); sendUpdate(); }
    }

    /** Lifecycle mutations publish immediately; continuous work keeps the existing ten-step cadence. */
    private void refreshPresentation() {
        if (level == null || level.isClientSide) return;
        var session = SessionManager.byStation(net.minecraft.core.GlobalPos.of(level.dimension(), worldPosition)).orElse(null);
        if (session != null) return;
        StationVisual.Activity activity = ledger == null ? (anyFinished() ? StationVisual.Activity.OUTPUT_READY : StationVisual.Activity.IDLE)
                : switch (ledger.state()) {
                    case RESERVED -> StationVisual.Activity.COUNT_IN;
                    case RUNNING -> ledger.operation().interactive() && !ledger.physical() ? StationVisual.Activity.SEALED : StationVisual.Activity.WORKING;
                    case SEALED -> StationVisual.Activity.SEALED;
                    case RECOVERY -> StationVisual.Activity.RECOVERY;
                };
        UUID job = ledger == null ? (anyFinished() ? visual.jobId() : StationVisual.NONE) : ledger.jobId();
        if (activity == visual.activity() && job.equals(visual.jobId()) && incarnation.equals(visual.incarnation())) return;
        publishVisual(ledger == null ? StationVisual.IDLE : StationPresentations.frozen(ledger), activity);
    }

    public void acceptVisual(StationVisual incoming) {
        if (level == null || !level.isClientSide) return;
        StationVisual old = view.visual();
        if (!old.incarnation().equals(incoming.incarnation()) || incoming.revision() <= old.revision()) return;
        view = view.withVisual(incoming);
    }

    public void effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind kind, UUID job, int step, int index, int outcome) {
        if (level == null || level.isClientSide) return;
        refreshPresentation();
        com.otectus.immersivebrewing.network.ModNetwork.tracking(this,
                new com.otectus.immersivebrewing.network.packet.StationEffectEvent(worldPosition, incarnation, job,
                        ++effectSequence, level.getGameTime(), step, kind, index, outcome));
    }

    private int ownedServings() {
        if (ledger == null) return 0;
        return ledger.operation() == Operation.PREPARE ? ledger.inputs().stream().mapToInt(ItemStack::getCount).sum() : ledger.inputs().size();
    }

    public StationVisual visual() {
        return level != null && level.isClientSide ? view.visual() : visual;
    }

    public boolean statusComparator() {
        return statusComparator;
    }

    public void toggleComparatorMode() {
        statusComparator = !statusComparator;
        changed();
    }

    // ---- interaction ----

    /**
     * Ordinary use (handoff 4): one accepted action per click, in this order. (1) Valid held-item actions: recovery
     * installation, permitted water filling, carrier exchange. (2) A direct action with the held item on the part it
     * was used on (or the one part that takes it): loading a tray, a rack, a burner. (3) The station's working view.
     * The maintenance menu stays reachable from the working view's inventory drawer; sneaking is never required.
     */
    public InteractionResult use(Player player, InteractionHand hand, BlockHitResult hit) {
        if (level == null) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        InteractionResult special = useWith(player, hand, held);
        if (special != InteractionResult.PASS) return special;
        if (held.is(ModItems.RECOVERY_BATCH.get())) {
            if (!level.isClientSide) installRecovery(player, hand, held);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!held.isEmpty()) {
            // One unit per use, as with other in-world containers; the working view's batch action moves more (handoff 6.3).
            InteractionResult direct = directInsert(player, hand, held, partUnder(player, hit), 1);
            if (direct != InteractionResult.PASS) return direct;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) openWorkingView(sp);
        return InteractionResult.CONSUME;
    }

    /** Opens the working view (server): registers the presence and tells the client to open its focus surface. */
    public void openWorkingView(ServerPlayer player) {
        com.otectus.immersivebrewing.interaction.WorkshopPresence.enter(player, worldPosition, workshopKind());
        markWorkshopDirty(true);
    }

    /** Opens the station's maintenance menu (the working view's inventory drawer, and the migration fallback). */
    public void openDrawer(ServerPlayer player) {
        NetworkHooks.openScreen(player, this, buf -> buf.writeBlockPos(worldPosition));
    }

    /** The part the player's look ray meets first (in model pixels), or null. */
    @Nullable
    protected com.otectus.immersivebrewing.workshop.WorkshopPart partUnder(Player player, BlockHitResult hit) {
        Direction facing = getBlockState().getValue(StationBlock.FACING);
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 dir = hit.getLocation().subtract(eye);
        if (dir.lengthSqr() < 1e-6) dir = player.getLookAngle();
        return com.otectus.immersivebrewing.workshop.WorkshopGeometry.pick(com.otectus.immersivebrewing.workshop.WorkshopParts.of(workshopKind()),
                worldPosition, facing, eye, dir, 8.0, p -> true).map(com.otectus.immersivebrewing.workshop.WorkshopGeometry.Hit::part).orElse(null);
    }

    /**
     * Puts up to {@code limit} units of the held stack into the part it was used on, or the one part that takes it. PASS
     * when no part of this station takes the item at all (the use then opens the working view); CONSUME with a reason
     * when a part would take it but not now (nothing is eaten). An ordinary use moves one unit; only an explicit batch
     * action in the working view passes a larger limit (handoff 6.3: one pour never means the whole stack).
     */
    protected InteractionResult directInsert(Player player, InteractionHand hand, ItemStack held,
                                             @Nullable com.otectus.immersivebrewing.workshop.WorkshopPart part, int limit) {
        return InteractionResult.PASS;
    }

    /** Installs a Recovery Batch at this empty station: consumes the item and adopts its ledger atomically (spec 20.4). */
    protected void installRecovery(Player player, InteractionHand hand, ItemStack held) {
        ResourceLocation station = RecoveryBatchItem.station(held);
        if (!stationId().equals(station)) {
            notice(player, Component.translatable("notice.immersive_brewing.recovery.wrong_station"), true);
            return;
        }
        if (ledger != null || !emptyForRecovery()) {
            notice(player, Component.translatable("notice.immersive_brewing.recovery.not_empty"), true);
            return;
        }
        JobLedger restored = RecoveryBatchItem.ledger(held).orElse(null);
        if (restored == null) {
            notice(player, Component.translatable("notice.immersive_brewing.recovery.damaged"), true);
            return;
        }
        // Consume first, then adopt: the ledger exists in exactly one place at every point.
        player.setItemInHand(hand, ItemStack.EMPTY);
        ledger = restored;
        if (restored.operation().interactive() && restored.state() == JobState.RESERVED) {
            // Packaged before its reaction began: cancel before start (spec 20.2) returns everything to this station.
            cancelReservation();
            notice(player, Component.translatable("notice.immersive_brewing.recovery.released"), false);
            return;
        }
        // An interrupted session resumes from its stored state; a passive job simply carries on.
        if (restored.operation().interactive() && restored.state() == JobState.RUNNING) restored.setState(JobState.SEALED);
        afterRecoveryInstalled();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.RECOVERY_INSTALL, restored.jobId(), 0, 0, 0);
        changed();
        notice(player, Component.translatable("notice.immersive_brewing.recovery.installed"), false);
    }

    /** The slots a recovered ledger needs empty (rack or inputs). */
    protected abstract boolean emptyForRecovery();

    protected void afterRecoveryInstalled() {
        revalidateRecovery();
    }

    /** Re-checks a held ledger's required data; may move it between SEALED and RECOVERY. */
    public void revalidateRecovery() {}

    public static void notice(Player player, Component text, boolean failure) {
        if (player instanceof ServerPlayer sp) com.otectus.immersivebrewing.network.ModNetwork.notice(sp, text, failure);
    }

    // ---- breaking ----

    /** Called once when the block is removed. */
    public void onBroken() {
        if (level == null || level.isClientSide) return;
        SessionManager.stationRemoved(this);
        flushProcess();
        leases.clear();
        if (level.getServer() != null) com.otectus.immersivebrewing.interaction.WorkshopPresence.stationRemoved(level.dimension(), worldPosition, level.getServer());
        if (ledger != null) {
            ItemStack recovery = RecoveryBatchItem.create(new ItemStack(ModItems.RECOVERY_BATCH.get()), stationId(), ledger, recoverySummary());
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, recovery);
            ledger = null;
        }
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack.copy());
            inventory.force(i, ItemStack.EMPTY);
        }
    }

    protected Component recoverySummary() {
        return ledger == null ? Component.empty() : Component.translatable("tooltip.immersive_brewing.recovery.summary",
                Component.translatable(ledger.operation().key()), ledger.inputs().size());
    }

    // ---- ticking ----

    public void serverTick() {
        workshopTick();
        refreshPresentation();
        if (syncCooldown > 0) syncCooldown--;
        if (syncQueued && syncCooldown <= 0) {
            syncQueued = false;
            syncCooldown = VISUAL_SYNC_TICKS;
            sendUpdate();
        }
        if (updatePending) {
            updatePending = false;
            if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        boolean lit = litNow();
        BlockState state = getBlockState();
        if (state.hasProperty(StationBlock.LIT) && state.getValue(StationBlock.LIT) != lit && level != null) {
            level.setBlock(worldPosition, state.setValue(StationBlock.LIT, lit), 3);
        }
    }

    // ---- hands-on workshop: the block entity owns the process tick (handoff 12) ----

    public abstract com.otectus.immersivebrewing.workshop.WorkshopKind workshopKind();

    /** Advances the physical process exactly once per server tick, seals an unattended batch, expires leases, syncs. */
    private void workshopTick() {
        if (level == null || level.isClientSide) return;
        boolean measured = ledger != null && ledger.physical() || snapshotDirty || !leases.all().isEmpty();
        long started = measured ? System.nanoTime() : 0;
        workshopStep();
        if (measured) com.otectus.immersivebrewing.workshop.WorkshopMetrics.station(System.nanoTime() - started);
    }

    private void workshopStep() {
        long now = level.getGameTime();
        if (!leases.expire(now).isEmpty()) {
            onLeasesLapsed();
            markWorkshopDirty(true);
        }
        if (ledger != null && ledger.physical() && ledger.state() == JobState.RUNNING) {
            if (participantInRange()) workshopGrace = 0;
            else if (++workshopGrace >= GRACE_TICKS) {
                sealPhysical(null, "workshop.immersive_brewing.seal.unattended");
            }
            if (ledger != null && ledger.state() == JobState.RUNNING) {
                physicalStep(now);
                // The established ambient pipeline (bounded particles, capped sound voices) follows the physical state.
                if (ledger != null && ledger.state() == JobState.RUNNING && now % 10 == 0) {
                    StationVisual v = physicalVisual();
                    if (v != null) publishVisual(v, StationVisual.Activity.WORKING);
                }
            }
        } else {
            workshopGrace = 0;
        }
        if (snapshotCooldown > 0) snapshotCooldown--;
        if (snapshotDirty && (snapshotUrgent || snapshotCooldown <= 0)) {
            snapshotDirty = false;
            snapshotUrgent = false;
            snapshotCooldown = SNAPSHOT_TICKS;
            com.otectus.immersivebrewing.network.ModNetwork.tracking(this,
                    new com.otectus.immersivebrewing.network.packet.WorkshopSnapshotS2C(worldPosition, ++snapshotRevision, buildWorkshopSnapshot()));
            com.otectus.immersivebrewing.workshop.WorkshopMetrics.snapshot();
        }
    }

    /** Whether an authorised participant (the brewer or a helper who did productive work) is online and near. */
    protected boolean participantInRange() {
        if (level == null || level.getServer() == null) return false;
        double range = ServerConfig.get(ServerConfig.PARTICIPANT_RANGE);
        for (UUID id : workshopParticipants()) {
            ServerPlayer p = com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), id);
            if (p == null || !p.isAlive() || p.level() != level) continue;
            if (p.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= range * range) return true;
        }
        return false;
    }

    /** The productive participants of the current physical job (empty when there is none). */
    protected java.util.List<UUID> workshopParticipants() {
        return java.util.List.of();
    }

    /** The compact ambient visual of the running physical process (sounds and particles), or null. */
    @Nullable
    protected StationVisual physicalVisual() {
        return null;
    }

    /** One server step of the physical process (only while the job is RUNNING). */
    protected void physicalStep(long now) {}

    /** A lease lapsed on its own (its holder went quiet): reset the tool it covered. */
    protected void onLeasesLapsed() {}

    /** Releases every lease a player holds here (leaving, logout, death, damage). */
    public void releaseLeases(UUID player) {
        if (leases.releaseAll(player)) {
            onLeasesLapsed();
            markWorkshopDirty(true);
        }
    }

    /**
     * Seals a running physical job now (handoff 16): leases released, controls neutral, exact state kept, no further
     * cost or penalty. Safe to call at any time; a sealed job resumes only through an explicit unseal.
     */
    public void sealPhysical(@Nullable ServerPlayer by, String reason) {
        if (ledger == null || !ledger.physical() || ledger.state() != JobState.RUNNING) return;
        leases.clear();
        onPhysicalSealed();
        flushProcess();
        ledger.setState(JobState.SEALED);
        workshopGrace = 0;
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.SEAL, ledger.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        if (level != null && level.getServer() != null) {
            for (UUID id : workshopParticipants()) {
                ServerPlayer p = com.otectus.immersivebrewing.interaction.WorkshopPresence.player(level.getServer(), id);
                if (p != null) notice(p, Component.translatable("notice.immersive_brewing.workshop.sealed", Component.translatable(reason)), false);
            }
        }
    }

    /** Station hook: neutralise the live process's controls before the seal is stored. */
    protected void onPhysicalSealed() {}

    /** Reopens a sealed physical job for {@code player}: their old input is neutral and the process carries on. */
    public com.otectus.immersivebrewing.interaction.WorkshopResult unsealPhysical(ServerPlayer player) {
        revalidateRecovery();
        if (ledger == null || !ledger.physical()) return com.otectus.immersivebrewing.interaction.WorkshopResult.IGNORED;
        if (ledger.state() == JobState.RECOVERY) {
            return com.otectus.immersivebrewing.interaction.WorkshopResult.refuse("notice.immersive_brewing.held",
                    Component.translatable(ledger.recoveryReason() == null ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
        }
        if (ledger.state() != JobState.SEALED) return com.otectus.immersivebrewing.interaction.WorkshopResult.IGNORED;
        String problem = physicalResumeProblem();
        if (problem != null) return com.otectus.immersivebrewing.interaction.WorkshopResult.refuse(problem);
        ledger.setState(JobState.RUNNING);
        ledger.countResume();
        participate(player);
        if (!player.getUUID().equals(ledger.brewerId())) ledger.addAssistant(player.getGameProfile().getName());
        com.otectus.immersivebrewing.advancement.ModAdvancements.milestone(player, com.otectus.immersivebrewing.advancement.Milestone.RESUME);
        workshopGrace = 0;
        changed();
        effect(com.otectus.immersivebrewing.network.packet.StationEffectEvent.Kind.RESUME, ledger.jobId(), 0, 0, 0);
        markWorkshopDirty(true);
        return com.otectus.immersivebrewing.interaction.WorkshopResult.ACCEPTED;
    }

    /** Why a sealed physical job can not reopen now (a translation key), or null. */
    @Nullable
    protected String physicalResumeProblem() {
        return null;
    }

    /** Records a productive participant of the current job. */
    protected void participate(ServerPlayer player) {}

    /** Encodes the live physical process into the ledger (before saving, packaging or sealing). */
    protected void flushProcess() {}

    /**
     * Applies one validated intent (handoff 15). The common parts are here: the drawer, the book, leaving, and the
     * legacy resume; everything that touches the process is the station's {@link #act}.
     */
    public com.otectus.immersivebrewing.interaction.WorkshopResult workshopAct(ServerPlayer player,
                                                                                com.otectus.immersivebrewing.interaction.WorkshopIntent intent) {
        if (level == null) return com.otectus.immersivebrewing.interaction.WorkshopResult.IGNORED;
        if (ledger != null && intent.job() != null && !intent.job().equals(com.otectus.immersivebrewing.interaction.WorkshopIntent.ANY_JOB)
                && !intent.job().equals(ledger.jobId())) {
            return com.otectus.immersivebrewing.interaction.WorkshopResult.IGNORED; // aimed at a batch that is gone
        }
        switch (intent.action()) {
            case DRAWER -> {
                openDrawer(player);
                return com.otectus.immersivebrewing.interaction.WorkshopResult.ACCEPTED;
            }
            case LEGACY_RESUME -> {
                if (ledger == null || !ledger.legacy() || ledger.state() != JobState.SEALED) {
                    return com.otectus.immersivebrewing.interaction.WorkshopResult.refuse("notice.immersive_brewing.nothing_to_resume");
                }
                resumeLegacy(player);
                return com.otectus.immersivebrewing.interaction.WorkshopResult.ACCEPTED;
            }
            default -> {
                return act(player, intent, level.getGameTime());
            }
        }
    }

    /** Station-specific workshop actions. */
    protected com.otectus.immersivebrewing.interaction.WorkshopResult act(ServerPlayer player,
                                                                           com.otectus.immersivebrewing.interaction.WorkshopIntent intent, long now) {
        return com.otectus.immersivebrewing.interaction.WorkshopResult.IGNORED;
    }

    /** Finishes a batch started before the hands-on overhaul through its original interface (migration only). */
    protected void resumeLegacy(ServerPlayer player) {}

    /** Asks for a snapshot to the tracking players: now when {@code urgent} (a discrete change), else within a few ticks. */
    public void markWorkshopDirty(boolean urgent) {
        snapshotDirty = true;
        snapshotUrgent |= urgent;
    }

    /** The compact presentation snapshot every tracking client receives. */
    public net.minecraft.nbt.CompoundTag buildWorkshopSnapshot() {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putByte("kind", (byte) workshopKind().ordinal());
        if (ledger != null) {
            t.putByte("state", (byte) ledger.state().ordinal());
            t.putByte("op", (byte) ledger.operation().ordinal());
            t.putBoolean("physical", ledger.physical());
            t.putBoolean("legacy", ledger.legacy());
            t.putUUID("job", ledger.jobId());
            if (ledger.brewerName() != null) t.putString("brewer", ledger.brewerName());
        }
        net.minecraft.nbt.ListTag held = new net.minecraft.nbt.ListTag();
        long now = level == null ? 0 : level.getGameTime();
        for (var e : leases.all().entrySet()) {
            if (e.getValue().expires() < now) continue;
            net.minecraft.nbt.CompoundTag h = new net.minecraft.nbt.CompoundTag();
            h.putByte("part", (byte) (int) e.getKey());
            h.putString("name", e.getValue().name());
            h.putUUID("id", e.getValue().holder());
            writeToolPose(e.getKey(), h);
            held.add(h);
        }
        t.put("held", held);
        writeWorkshopSnapshot(t);
        return t;
    }

    /** Station hook: the live pose of a leased tool (normalised x/z, contact). */
    protected void writeToolPose(int part, net.minecraft.nbt.CompoundTag tag) {}

    /** Station hook: process state and the visible subset of owned items. */
    protected void writeWorkshopSnapshot(net.minecraft.nbt.CompoundTag tag) {}

    public net.minecraft.nbt.CompoundTag workshopView() {
        return workshopView;
    }

    /** Client: accepts a newer snapshot (stale revisions from a reordered or late packet are ignored). */
    public void acceptWorkshopSnapshot(long revision, net.minecraft.nbt.CompoundTag snapshot) {
        if (level == null || !level.isClientSide) return;
        if (revision <= workshopViewRevision) return;
        workshopViewRevision = revision;
        workshopView = snapshot;
    }

    /** Client-side ambient effects, installed by client setup (common code never loads client classes). */
    public static volatile java.util.function.Consumer<StationBlockEntity> clientEffects = station -> {};
    public static volatile java.util.function.Consumer<StationBlockEntity> clientRemoved = station -> {};

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide) clientRemoved.accept(this);
        super.setRemoved();
    }

    public void clientTick() {
        clientEffects.accept(this);
    }

    /** Whether the burner glows (block state LIT). */
    protected boolean litNow() {
        return ledger != null && ledger.state() == JobState.RUNNING;
    }

    // ---- sessions (server) ----

    /** A station-menu button (validated: the player has this station's menu open). */
    public void handleAction(ServerPlayer player, com.otectus.immersivebrewing.network.packet.StationActionC2S.Action action, int argument) {
        if (action == com.otectus.immersivebrewing.network.packet.StationActionC2S.Action.TOGGLE_COMPARATOR) toggleComparatorMode();
    }

    /** The approved assistance for a request packed into a station action: capped by the server, or none when disabled. */
    protected static double[] approvedAssist(int argument) {
        if (!ServerConfig.get(ServerConfig.ALLOW_ASSISTED_CONTROLS)) return new double[] {1.0, 1.0};
        double time = Math.min(com.otectus.immersivebrewing.network.packet.StationActionC2S.assistTime(argument), ServerConfig.get(ServerConfig.MAX_ASSIST_TIME_MULTIPLIER));
        double band = Math.min(com.otectus.immersivebrewing.network.packet.StationActionC2S.assistBand(argument), ServerConfig.get(ServerConfig.MAX_ASSIST_BAND_MULTIPLIER));
        return new double[] {Math.max(1.0, time), Math.max(1.0, band)};
    }

    /** The count-in ended: the reaction starts (RESERVED or SEALED to RUNNING). */
    public void onRunStarted(@Nullable ServerPlayer player, boolean resumed) {
        if (ledger == null) return;
        ledger.setState(JobState.RUNNING);
        if (resumed) {
            ledger.countResume();
            if (player != null && !player.getUUID().equals(ledger.brewerId())) ledger.addAssistant(player.getGameProfile().getName());
            if (player != null) com.otectus.immersivebrewing.advancement.ModAdvancements.milestone(player, com.otectus.immersivebrewing.advancement.Milestone.RESUME);
        }
        changed();
    }

    /** Interruption after the reaction started: keep everything, store the full simulation state (spec 20.4). */
    public void sealJob(long[] simState) {
        if (ledger == null) return;
        ledger.setSimState(simState);
        ledger.setState(JobState.SEALED);
        changed();
    }

    /** Interruption during the count-in: a fresh job releases its reservation; a resumed one stays sealed. */
    public void cancelCountIn(boolean resumed) {
        if (ledger == null) return;
        if (resumed) {
            ledger.setState(JobState.SEALED);
            changed();
        } else {
            cancelReservation();
        }
    }

    /**
     * Commits a finished session exactly once: the ledger's materials become the result, the ledger closes, then events
     * fire. Returns the result payload for the client, or null when the job had to be held for recovery instead.
     */
    @Nullable
    public abstract CompoundTag completeJob(@Nullable ServerPlayer player, com.otectus.immersivebrewing.session.SimHandle sim);

    /** Called by menus after a slot's live stack changed in place. */
    public void slotChanged(int slot) {
        onInventoryChanged(slot);
    }

    // ---- inventory callbacks ----

    protected void onInventoryChanged(int slot) {
        if (slot >= 0 && slot < finished.length && inventory.getStackInSlot(slot).isEmpty()) finished[slot] = false;
        changed();
    }

    /** Marks the station dirty, refreshes comparators and queues a client sync. */
    public void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            queueSync();
        }
    }

    protected void queueSync() {
        syncQueued = true;
    }

    /**
     * Owes tracking clients a full update, sent from this station's next tick. Never marks the chunk changed directly:
     * presentation is refreshed while vanilla builds an update packet (inside its chunk broadcast) and while a chunk
     * loads, and a block change recorded re-entrantly there leaves the section's change set stranded, so no later
     * change in that section would ever reach a client.
     */
    private void sendUpdate() {
        if (level != null && !level.isClientSide) updatePending = true;
    }

    /** Puts a stack into a slot bypassing the public rules (commits and returns only). */
    protected void putDirect(int slot, ItemStack stack) {
        inventory.force(slot, stack);
    }

    // ---- batch carrier ----

    /**
     * Moves finished, carrier-storable stacks from slots {@code [from, from + size)} into a carrier's contents, merging
     * where stacks allow; what does not fit stays in its slot (spec 10: exact stacks, nothing deleted). Returns the
     * number of items moved.
     */
    protected int collectFinished(java.util.List<ItemStack> contents, int from, int size) {
        int moved = 0;
        for (int i = from; i < from + size; i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!finished[i] || s.isEmpty() || !com.otectus.immersivebrewing.item.CarrierContents.accepts(s)) continue;
            ItemStack left = s.copy();
            for (int c = 0; c < contents.size() && !left.isEmpty(); c++) {
                ItemStack slot = contents.get(c);
                if (slot.isEmpty()) {
                    contents.set(c, left);
                    left = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameTags(slot, left) && slot.getCount() < slot.getMaxStackSize()) {
                    int move = Math.min(left.getCount(), slot.getMaxStackSize() - slot.getCount());
                    contents.set(c, slot.copyWithCount(slot.getCount() + move));
                    left = left.copyWithCount(left.getCount() - move);
                }
            }
            if (left.getCount() == s.getCount()) continue;
            moved += s.getCount() - left.getCount();
            putDirect(i, left);
        }
        return moved;
    }

    /** Loads single items the predicate accepts from a carrier's contents into the empty slots of {@code [from, from + size)}. */
    protected int loadFromCarrier(java.util.List<ItemStack> contents, int from, int size, java.util.function.Predicate<ItemStack> accepts) {
        int moved = 0;
        for (int c = 0; c < contents.size(); c++) {
            ItemStack s = contents.get(c);
            while (!s.isEmpty() && accepts.test(s)) {
                int free = -1;
                for (int i = from; i < from + size; i++) {
                    if (inventory.getStackInSlot(i).isEmpty()) {
                        free = i;
                        break;
                    }
                }
                if (free < 0) break;
                putDirect(free, s.copyWithCount(1));
                s = s.copyWithCount(s.getCount() - 1);
                moved++;
            }
            contents.set(c, s);
        }
        return moved;
    }

    // ---- comparator ----

    public int comparatorSignal() {
        if (statusComparator) {
            if (ledger != null) {
                if (ledger.physical() && ledger.state() == JobState.RUNNING) return physicalComparator();
                return switch (ledger.state()) {
                    case RESERVED, RUNNING -> 8;
                    case SEALED -> 11;
                    case RECOVERY -> 13;
                };
            }
            if (anyFinished()) return 15;
            return loaded() ? 4 : 0;
        }
        int total = 0;
        int full = 0;
        for (int i = 0; i < finished.length; i++) {
            if (!isOutputSlot(i)) continue;
            total++;
            if (finished[i] && !inventory.getStackInSlot(i).isEmpty()) full++;
        }
        return total == 0 || full == 0 ? 0 : Math.max(1, Math.round(15F * full / total));
    }

    /**
     * Status level of a running hands-on job: 8 working, 9 waiting for attention (a dose, a rest, a charge), 14 ready to
     * bottle or collect. Sealed is 11 and held 13, as before; an empty public rack is never read as an empty batch.
     */
    protected int physicalComparator() {
        return 8;
    }

    /** Slots that count toward the output-fill comparator. */
    protected abstract boolean isOutputSlot(int slot);

    protected boolean anyFinished() {
        for (int i = 0; i < finished.length; i++) if (finished[i] && !inventory.getStackInSlot(i).isEmpty()) return true;
        return false;
    }

    // ---- persistence ----

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("Incarnation")) incarnation = tag.getUUID("Incarnation");
        visualRevision = Math.max(visualRevision, tag.getLong("VisualRevision"));
        effectSequence = Math.max(effectSequence, tag.getLong("EffectSequence"));
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) inventory.deserializeNBT(tag.getCompound("Inventory"));
        if (tag.contains("FuelCharges", Tag.TAG_ANY_NUMERIC)) fuelCharges = Math.max(0, tag.getInt("FuelCharges"));
        if (tag.contains("Ledger", Tag.TAG_COMPOUND)) {
            ledger = LedgerCodec.load(tag.getCompound("Ledger"));
        } else if (tag.contains("Inventory", Tag.TAG_COMPOUND)) {
            ledger = null;
        }
        if (tag.contains("LastResult", Tag.TAG_COMPOUND)) lastResult = StationResult.load(tag.getCompound("LastResult"));
        statusComparator = tag.getBoolean("StatusComparator");
        if (tag.contains("Finished", Tag.TAG_BYTE_ARRAY)) {
            byte[] bytes = tag.getByteArray("Finished");
            for (int i = 0; i < finished.length; i++) finished[i] = i < bytes.length && bytes[i] != 0;
        }
        if (tag.contains("Workshop", Tag.TAG_COMPOUND) && level != null && level.isClientSide) {
            acceptWorkshopSnapshot(tag.getLong("WorkshopRevision"), tag.getCompound("Workshop"));
        }
        if (tag.contains("View", Tag.TAG_COMPOUND)) {
            StationView incoming = StationView.load(tag.getCompound("View"));
            // Full inventory updates may race a compact visual packet; retain the newest pose.
            if (incoming.visual().incarnation().equals(view.visual().incarnation())
                    && incoming.visual().revision() < view.visual().revision()) incoming = incoming.withVisual(view.visual());
            view = incoming;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID("Incarnation", incarnation);
        tag.putLong("VisualRevision", visualRevision);
        tag.putLong("EffectSequence", effectSequence);
        SessionManager.flush(this);
        flushProcess();
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("FuelCharges", fuelCharges);
        if (ledger != null) tag.put("Ledger", LedgerCodec.save(ledger));
        if (lastResult != null) tag.put("LastResult", lastResult.save());
        tag.putBoolean("StatusComparator", statusComparator);
        byte[] bytes = new byte[finished.length];
        for (int i = 0; i < finished.length; i++) bytes[i] = (byte) (finished[i] ? 1 : 0);
        tag.putByteArray("Finished", bytes);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && ledger != null && ledger.state() == JobState.RESERVED && ledger.operation().interactive()) {
            // A reservation that never started: release it, restoring ordinary inventory (spec 20.4 server restart).
            cancelReservation();
        }
        if (level != null && !level.isClientSide && ledger != null) revalidateRecovery();
        refreshPresentation();
    }

    /** Returns every reserved stack to its slot and closes the ledger. */
    protected abstract void cancelReservation();

    // ---- client sync ----

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("FuelCharges", fuelCharges);
        tag.put("View", buildView().save());
        byte[] bytes = new byte[finished.length];
        for (int i = 0; i < finished.length; i++) bytes[i] = (byte) (finished[i] ? 1 : 0);
        tag.putByteArray("Finished", bytes);
        tag.putBoolean("StatusComparator", statusComparator);
        if (level != null && !level.isClientSide) {
            tag.put("Workshop", buildWorkshopSnapshot());
            tag.putLong("WorkshopRevision", ++snapshotRevision);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    protected StationView buildView() {
        refreshPresentation();
        JobLedger l = ledger;
        return new StationView(l == null ? null : l.state(), l == null ? null : l.operation(), l == null ? ItemStack.EMPTY : viewOutput(),
                l == null ? 0 : ownedServings(), l == null ? null : l.recoveryReason(), l == null ? null : l.brewerName(),
                controllerName, visual, status(), lastResult, l == null || l.inputs().isEmpty() ? ItemStack.EMPTY : l.inputs().get(0).copyWithCount(1));
    }

    /** The output a job will produce, for the view (canonical snapshot, never the ledger itself). */
    protected ItemStack viewOutput() {
        return ledger == null ? ItemStack.EMPTY : ledger.outputSnapshot().copyWithCount(1);
    }

    // ---- capabilities ----

    private void createHandlers() {
        for (Direction d : Direction.values()) {
            final Direction side = d;
            sided.put(d, LazyOptional.of(() -> new AutomationHandler(this, side)));
        }
        unsided = LazyOptional.of(() -> new AutomationHandler(this, null));
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && !remove) {
            return side == null ? unsided.cast() : sided.get(side).cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        sided.values().forEach(LazyOptional::invalidate);
        unsided.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        createHandlers();
    }

    boolean automationAllowedIn() {
        return ServerConfig.get(ServerConfig.ALLOW_AUTOMATION_INPUTS);
    }

    boolean automationAllowedOut() {
        return ServerConfig.get(ServerConfig.ALLOW_AUTOMATION_OUTPUTS);
    }

    // ---- ledger helpers ----

    /** Generates a lineage token for a new batch: never 0. */
    protected static long newLineage() {
        long l = UUID.randomUUID().getLeastSignificantBits();
        return l == 0 ? 1 : l;
    }

    public boolean operationRunning(Operation operation) {
        return ledger != null && ledger.operation() == operation && ledger.state() == JobState.RUNNING;
    }
}
