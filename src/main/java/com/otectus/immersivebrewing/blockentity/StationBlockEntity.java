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
    private boolean syncQueued;
    private int syncCooldown;

    // Client view
    protected StationView view = StationView.EMPTY;

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

    public void setVisual(StationVisual visual) {
        this.visual = visual;
        if (syncCooldown <= 0) queueSync();
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

    public InteractionResult use(Player player, InteractionHand hand, BlockHitResult hit) {
        if (level == null) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        InteractionResult special = useWith(player, hand, held);
        if (special != InteractionResult.PASS) return special;
        if (held.is(ModItems.RECOVERY_BATCH.get())) {
            if (!level.isClientSide) installRecovery(player, hand, held);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            NetworkHooks.openScreen(sp, this, buf -> buf.writeBlockPos(worldPosition));
        }
        return InteractionResult.CONSUME;
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
        if (syncCooldown > 0) syncCooldown--;
        if (syncQueued && syncCooldown <= 0) {
            syncQueued = false;
            syncCooldown = VISUAL_SYNC_TICKS;
            sendUpdate();
        }
        boolean lit = litNow();
        BlockState state = getBlockState();
        if (state.hasProperty(StationBlock.LIT) && state.getValue(StationBlock.LIT) != lit && level != null) {
            level.setBlock(worldPosition, state.setValue(StationBlock.LIT, lit), 3);
        }
    }

    /** Client-side ambient effects, installed by client setup (common code never loads client classes). */
    public static volatile java.util.function.Consumer<StationBlockEntity> clientEffects = station -> {};

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

    private void sendUpdate() {
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
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
        if (tag.contains("View", Tag.TAG_COMPOUND)) view = StationView.load(tag.getCompound("View"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        SessionManager.flush(this);
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
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    protected StationView buildView() {
        JobLedger l = ledger;
        return new StationView(l == null ? null : l.state(), l == null ? null : l.operation(), l == null ? ItemStack.EMPTY : viewOutput(),
                l == null ? 0 : l.inputs().size(), l == null ? null : l.recoveryReason(), l == null ? null : l.brewerName(),
                controllerName, visual, status(), lastResult);
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
