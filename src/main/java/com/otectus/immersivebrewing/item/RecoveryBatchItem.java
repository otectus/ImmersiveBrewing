package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A broken station's open job, packaged whole (spec 20.4): the ledger with its bottles, reagents, fuel and simulation
 * state. It is not an extra bottle and can not be emptied by hand; installing it at an empty station of the same kind
 * consumes it and transfers the ledger atomically. Excluded from carriers, brewing and nested storage.
 */
public class RecoveryBatchItem extends Item {
    public static final String KEY = "immersive_brewing_recovery";

    public RecoveryBatchItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(ItemStack item, ResourceLocation station, JobLedger ledger, Component summary) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 1);
        tag.putString("station", station.toString());
        tag.put("ledger", LedgerCodec.save(ledger));
        tag.putString("summary", Component.Serializer.toJson(summary));
        item.getOrCreateTag().put(KEY, tag);
        return item;
    }

    @Nullable
    public static ResourceLocation station(ItemStack stack) {
        CompoundTag tag = data(stack);
        return tag == null ? null : ResourceLocation.tryParse(tag.getString("station"));
    }

    public static Optional<JobLedger> ledger(ItemStack stack) {
        CompoundTag tag = data(stack);
        if (tag == null || !tag.contains("ledger", Tag.TAG_COMPOUND)) return Optional.empty();
        return Optional.of(LedgerCodec.load(tag.getCompound("ledger")));
    }

    @Nullable
    private static CompoundTag data(ItemStack stack) {
        CompoundTag root = stack.getTag();
        return root != null && root.contains(KEY, Tag.TAG_COMPOUND) ? root.getCompound(KEY) : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = data(stack);
        if (tag == null) {
            tooltip.add(Component.translatable("tooltip.immersive_brewing.recovery.empty").withStyle(ChatFormatting.RED));
            return;
        }
        Component summary = null;
        try {
            summary = Component.Serializer.fromJson(tag.getString("summary"));
        } catch (RuntimeException ignored) {
            // A damaged summary only loses the description; the ledger itself is checked when installed.
        }
        if (summary != null) tooltip.add(summary.copy().withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.immersive_brewing.recovery.install").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return data(stack) != null;
    }
}
