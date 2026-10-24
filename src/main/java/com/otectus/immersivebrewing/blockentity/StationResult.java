package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.potion.Grade;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The last committed result at a station, for its status line, Jade and the result screen: what was made, how many,
 * the scores and the one improvement tip. Committed once; reading it never re-issues anything.
 */
public record StationResult(Operation operation, ItemStack output, int count, int quality, int extraction, int control,
                            String tipKey, List<String> tipArgs, String brewer) {

    public Grade grade() {
        return Grade.of(quality);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("op", operation.name());
        tag.put("output", LedgerCodec.saveStack(output));
        tag.putInt("count", count);
        tag.putByte("Q", (byte) quality);
        tag.putByte("E", (byte) extraction);
        tag.putByte("C", (byte) control);
        tag.putString("tip", tipKey);
        ListTag args = new ListTag();
        for (String a : tipArgs) args.add(StringTag.valueOf(a));
        tag.put("args", args);
        tag.putString("brewer", brewer == null ? "" : brewer);
        return tag;
    }

    public static StationResult load(CompoundTag tag) {
        Operation op;
        try {
            op = Operation.valueOf(tag.getString("op"));
        } catch (IllegalArgumentException e) {
            op = Operation.BREW;
        }
        List<String> args = new ArrayList<>();
        ListTag list = tag.getList("args", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(4, list.size()); i++) args.add(list.getString(i));
        return new StationResult(op, LedgerCodec.loadStack(tag.getCompound("output")), tag.getInt("count"), tag.getByte("Q"),
                tag.getByte("E"), tag.getByte("C"), tag.getString("tip"), List.copyOf(args), tag.getString("brewer"));
    }
}
