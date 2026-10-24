package com.otectus.immersivebrewing.batch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The single owner of everything a station job holds (spec 20.1): the input bottles or reagents moved out of the public
 * slots, the tray stacks reserved for later steps, the fuel charges, the immutable plan snapshot and canonical output,
 * and the full simulation parameters and state. Persisted with the station through {@link LedgerCodec}; packaged whole
 * into a Recovery Batch when the station breaks.
 *
 * <p>Mutated only on the server thread by the owning station.
 */
public final class JobLedger {
    public static final int MAX_ASSISTANTS = 4;

    private final UUID jobId;
    private final Operation operation;
    private JobState state;
    private final List<ItemStack> inputs;
    private final List<SlotStack> reserved;
    private int fuelCharges;
    private final CompoundTag snapshot;
    private final ItemStack outputSnapshot;
    private final long[] params;
    private long[] simState;
    private final long seed;
    private final int extraction;
    @Nullable
    private final UUID brewerId;
    @Nullable
    private final String brewerName;
    private final List<String> assistants;
    @Nullable
    private String recoveryReason;
    private int resumes;
    private final int dataGeneration;
    private final long lineage;
    /** A newer-schema ledger kept byte for byte (always in RECOVERY). */
    @Nullable
    private final CompoundTag unknownSchema;

    public JobLedger(UUID jobId, Operation operation, JobState state, List<ItemStack> inputs, List<SlotStack> reserved, int fuelCharges,
                     CompoundTag snapshot, ItemStack outputSnapshot, long[] params, long[] simState, long seed, int extraction,
                     @Nullable UUID brewerId, @Nullable String brewerName, List<String> assistants, @Nullable String recoveryReason,
                     int resumes, int dataGeneration, long lineage) {
        this(jobId, operation, state, inputs, reserved, fuelCharges, snapshot, outputSnapshot, params, simState, seed, extraction,
                brewerId, brewerName, assistants, recoveryReason, resumes, dataGeneration, lineage, null);
    }

    private JobLedger(UUID jobId, Operation operation, JobState state, List<ItemStack> inputs, List<SlotStack> reserved, int fuelCharges,
                      CompoundTag snapshot, ItemStack outputSnapshot, long[] params, long[] simState, long seed, int extraction,
                      @Nullable UUID brewerId, @Nullable String brewerName, List<String> assistants, @Nullable String recoveryReason,
                      int resumes, int dataGeneration, long lineage, @Nullable CompoundTag unknownSchema) {
        this.jobId = jobId;
        this.operation = operation;
        this.state = state;
        this.inputs = new ArrayList<>(inputs);
        this.reserved = new ArrayList<>(reserved);
        this.fuelCharges = Math.max(0, fuelCharges);
        this.snapshot = snapshot;
        this.outputSnapshot = outputSnapshot;
        this.params = params;
        this.simState = simState;
        this.seed = seed;
        this.extraction = extraction;
        this.brewerId = brewerId;
        this.brewerName = brewerName;
        this.assistants = new ArrayList<>(assistants);
        this.recoveryReason = recoveryReason;
        this.resumes = resumes;
        this.dataGeneration = dataGeneration;
        this.lineage = lineage;
        this.unknownSchema = unknownSchema;
    }

    static JobLedger unknown(CompoundTag raw, String reason) {
        return new JobLedger(UUID.randomUUID(), Operation.BREW, JobState.RECOVERY, List.of(), List.of(), 0, new CompoundTag(),
                ItemStack.EMPTY, new long[0], new long[0], 0, 0, null, null, List.of(), reason, 0, 0, 0, raw);
    }

    public UUID jobId() {
        return jobId;
    }

    public Operation operation() {
        return operation;
    }

    public JobState state() {
        return state;
    }

    public void setState(JobState state) {
        this.state = state;
    }

    /** Exact stacks this job owns as its batch (bottles, potions or source reagents). */
    public List<ItemStack> inputs() {
        return inputs;
    }

    /** Tray stacks reserved for later steps, with the slot each came from. */
    public List<SlotStack> reserved() {
        return reserved;
    }

    public int fuelCharges() {
        return fuelCharges;
    }

    public void setFuelCharges(int charges) {
        this.fuelCharges = Math.max(0, charges);
    }

    public CompoundTag snapshot() {
        return snapshot;
    }

    public ItemStack outputSnapshot() {
        return outputSnapshot;
    }

    public long[] params() {
        return params;
    }

    public long[] simState() {
        return simState;
    }

    public void setSimState(long[] state) {
        this.simState = state;
    }

    public long seed() {
        return seed;
    }

    public int extraction() {
        return extraction;
    }

    @Nullable
    public UUID brewerId() {
        return brewerId;
    }

    @Nullable
    public String brewerName() {
        return brewerName;
    }

    public List<String> assistants() {
        return assistants;
    }

    /** Records a player who resumed someone else's sealed batch (bounded; the original brewer stays the brewer). */
    public void addAssistant(String name) {
        if (name == null || assistants.contains(name) || assistants.size() >= MAX_ASSISTANTS) return;
        assistants.add(name);
    }

    @Nullable
    public String recoveryReason() {
        return recoveryReason;
    }

    public void hold(String reason) {
        this.state = JobState.RECOVERY;
        this.recoveryReason = reason;
    }

    public void clearRecovery() {
        this.recoveryReason = null;
    }

    public int resumes() {
        return resumes;
    }

    public void countResume() {
        resumes++;
    }

    public int dataGeneration() {
        return dataGeneration;
    }

    public long lineage() {
        return lineage;
    }

    @Nullable
    public CompoundTag unknownSchema() {
        return unknownSchema;
    }

    /** Whether the job is between reservation and commit (anything but a plain held recovery). */
    public boolean active() {
        return state == JobState.RESERVED || state == JobState.RUNNING || state == JobState.SEALED;
    }
}
