package com.otectus.immersivebrewing.batch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Versioned NBT form of a {@link JobLedger} (spec 20.3). Bounded: list lengths and the total encoded size are checked
 * on save and load. A ledger written by a newer schema loads as a held recovery job with its raw compound preserved,
 * never discarded and never reinterpreted.
 */
public final class LedgerCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_STACKS = 32;
    public static final int MAX_BYTES = 256 * 1024;

    public static CompoundTag save(JobLedger ledger) {
        if (ledger.unknownSchema() != null) return ledger.unknownSchema().copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putUUID("job", ledger.jobId());
        tag.putString("state", ledger.state().name());
        tag.putString("operation", ledger.operation().name());
        tag.put("inputs", saveStacks(ledger.inputs()));
        ListTag reserved = new ListTag();
        for (SlotStack s : ledger.reserved()) reserved.add(s.save());
        tag.put("reserved", reserved);
        tag.putInt("fuel", ledger.fuelCharges());
        tag.put("snapshot", ledger.snapshot().copy());
        tag.put("output", saveStack(ledger.outputSnapshot()));
        tag.put("params", new LongArrayTag(ledger.params()));
        tag.put("sim", new LongArrayTag(ledger.simState()));
        tag.putLong("seed", ledger.seed());
        tag.putInt("extraction", ledger.extraction());
        if (ledger.brewerId() != null) tag.putUUID("brewer", ledger.brewerId());
        if (ledger.brewerName() != null) tag.putString("brewerName", ledger.brewerName());
        ListTag assistants = new ListTag();
        for (String a : ledger.assistants()) assistants.add(net.minecraft.nbt.StringTag.valueOf(a));
        tag.put("assistants", assistants);
        if (ledger.recoveryReason() != null) tag.putString("recovery", ledger.recoveryReason());
        tag.putInt("resumes", ledger.resumes());
        tag.putInt("generation", ledger.dataGeneration());
        tag.putLong("lineage", ledger.lineage());
        int size = encodedSize(tag);
        if (size > MAX_BYTES) throw new IllegalStateException("job ledger too large: " + size + " bytes");
        return tag;
    }

    public static JobLedger load(CompoundTag tag) {
        int schema = tag.getInt("schema");
        if (schema > SCHEMA || schema < 1) return JobLedger.unknown(tag.copy(), "recovery.immersive_brewing.newer_schema");
        // An item whose mod is missing would load as air and be lost on the next save: keep the whole ledger raw instead,
        // held for recovery until the item exists again (spec 20.1: never silently delete unknown data).
        if (hasUnknownItem(tag.getList("inputs", Tag.TAG_COMPOUND), false) || hasUnknownItem(tag.getList("reserved", Tag.TAG_COMPOUND), true)
                || hasUnknownItem(tag.getCompound("output"))) {
            return JobLedger.unknown(tag.copy(), "recovery.immersive_brewing.missing_item");
        }
        JobState state = enumOr(JobState.class, tag.getString("state"), JobState.RECOVERY);
        Operation operation = enumOr(Operation.class, tag.getString("operation"), Operation.BREW);
        List<SlotStack> reserved = new ArrayList<>();
        ListTag list = tag.getList("reserved", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(list.size(), MAX_STACKS); i++) reserved.add(SlotStack.load(list.getCompound(i)));
        List<String> assistants = new ArrayList<>();
        ListTag names = tag.getList("assistants", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(names.size(), JobLedger.MAX_ASSISTANTS); i++) assistants.add(names.getString(i));
        JobLedger ledger = new JobLedger(tag.hasUUID("job") ? tag.getUUID("job") : UUID.randomUUID(), operation, state,
                loadStacks(tag.getList("inputs", Tag.TAG_COMPOUND)), reserved, tag.getInt("fuel"), tag.getCompound("snapshot"),
                loadStack(tag.getCompound("output")), tag.getLongArray("params"), tag.getLongArray("sim"), tag.getLong("seed"),
                tag.getInt("extraction"), tag.hasUUID("brewer") ? tag.getUUID("brewer") : null,
                tag.contains("brewerName", Tag.TAG_STRING) ? tag.getString("brewerName") : null, assistants,
                tag.contains("recovery", Tag.TAG_STRING) ? tag.getString("recovery") : null, tag.getInt("resumes"),
                tag.getInt("generation"), tag.getLong("lineage"));
        // A ledger that was mid-session when the world saved loads sealed: never resumed without a player (spec 20.4).
        // A reservation that never started is released by the station when it loads (ordinary inventory restored).
        if (ledger.state() == JobState.RUNNING && operation.interactive()) ledger.setState(JobState.SEALED);
        return ledger;
    }

    private static boolean hasUnknownItem(ListTag list, boolean wrapped) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (hasUnknownItem(wrapped ? entry.getCompound("stack") : entry)) return true;
        }
        return false;
    }

    private static boolean hasUnknownItem(CompoundTag stack) {
        if (stack.isEmpty() || !stack.contains("id", Tag.TAG_STRING)) return false;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(stack.getString("id"));
        return id == null || !net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id);
    }

    public static CompoundTag saveStack(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        if (stack.isEmpty()) return tag;
        stack.save(tag);
        tag.putInt("FullCount", stack.getCount()); // ItemStack writes its count as a byte
        return tag;
    }

    public static ItemStack loadStack(CompoundTag tag) {
        if (tag.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = ItemStack.of(tag);
        if (tag.contains("FullCount", Tag.TAG_INT)) stack.setCount(Math.max(0, tag.getInt("FullCount")));
        return stack;
    }

    public static ListTag saveStacks(List<ItemStack> stacks) {
        if (stacks.size() > MAX_STACKS) throw new IllegalStateException("too many stacks in a ledger: " + stacks.size());
        ListTag list = new ListTag();
        for (ItemStack s : stacks) list.add(saveStack(s));
        return list;
    }

    public static List<ItemStack> loadStacks(ListTag list) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < Math.min(list.size(), MAX_STACKS); i++) {
            ItemStack s = loadStack(list.getCompound(i));
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static int encodedSize(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.size();
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private LedgerCodec() {}
}
