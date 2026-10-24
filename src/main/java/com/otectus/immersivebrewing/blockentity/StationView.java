package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The compact, client-visible summary of a station: job state and kind, the output it will make, serving count,
 * recovery reason, brewer and controller names, the live visual and the status line. Built on the server from the
 * ledger; the ledger itself is never sent.
 */
public record StationView(@Nullable JobState state, @Nullable Operation operation, ItemStack output, int servings,
                          @Nullable String recovery, @Nullable String brewer, @Nullable String controller, StationVisual visual,
                          Component status, @Nullable StationResult lastResult) {
    public static final StationView EMPTY = new StationView(null, null, ItemStack.EMPTY, 0, null, null, null, StationVisual.IDLE,
            Component.empty(), null);

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (state != null) tag.putString("state", state.name());
        if (operation != null) tag.putString("op", operation.name());
        if (!output.isEmpty()) tag.put("output", LedgerCodec.saveStack(output));
        tag.putByte("servings", (byte) servings);
        if (recovery != null) tag.putString("recovery", recovery);
        if (brewer != null) tag.putString("brewer", brewer);
        if (controller != null) tag.putString("controller", controller);
        tag.put("visual", visual.save());
        tag.putString("status", Component.Serializer.toJson(status));
        if (lastResult != null) tag.put("last", lastResult.save());
        return tag;
    }

    public static StationView load(CompoundTag tag) {
        JobState state = null;
        Operation op = null;
        try {
            if (tag.contains("state", Tag.TAG_STRING)) state = JobState.valueOf(tag.getString("state"));
            if (tag.contains("op", Tag.TAG_STRING)) op = Operation.valueOf(tag.getString("op"));
        } catch (IllegalArgumentException ignored) {
            // An unknown state from a newer server shows as idle.
        }
        Component status;
        try {
            status = Component.Serializer.fromJson(tag.getString("status"));
        } catch (RuntimeException e) {
            status = null;
        }
        return new StationView(state, op, tag.contains("output", Tag.TAG_COMPOUND) ? LedgerCodec.loadStack(tag.getCompound("output")) : ItemStack.EMPTY,
                tag.getByte("servings"), tag.contains("recovery", Tag.TAG_STRING) ? tag.getString("recovery") : null,
                tag.contains("brewer", Tag.TAG_STRING) ? tag.getString("brewer") : null,
                tag.contains("controller", Tag.TAG_STRING) ? tag.getString("controller") : null,
                StationVisual.load(tag.getCompound("visual")), status == null ? Component.empty() : status,
                tag.contains("last", Tag.TAG_COMPOUND) ? StationResult.load(tag.getCompound("last")) : null);
    }
}
