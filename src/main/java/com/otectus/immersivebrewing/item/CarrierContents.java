package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact stacks and expedition preferences inside a Batch Carrier. The original contents list remains at {@link #KEY}
 * so 1.0 carriers keep working; versioned 1.1 preferences live beside it. Unknown newer preference payloads are never
 * rewritten by this version.
 */
public final class CarrierContents {
    public static final String KEY = "immersive_brewing_carrier";
    public static final String META_KEY = "immersive_brewing_carrier_meta";
    public static final int SCHEMA = 1;
    public static final int SLOTS = 6;
    public static final int MAX_LOADOUT_NAME = 24;
    public static final int DEFAULT_LABEL_COLOR = 0x8B5A2B;
    public static final int COLOR_COUNT = 8;
    public static final int EMBLEM_COUNT = 6;

    /** Versioned preferences. Templates are exact, slot-specific loadout ghosts and never own real items. */
    public record Meta(int selected, @Nullable String loadoutName, int labelColor, int emblem, boolean advanceOnEmpty,
                       List<ItemStack> templates) {
        public Meta {
            selected = Math.max(0, Math.min(SLOTS - 1, selected));
            loadoutName = BrewingMetadata.Text.limit(loadoutName, MAX_LOADOUT_NAME);
            labelColor &= 0xFFFFFF;
            emblem = Math.max(0, Math.min(EMBLEM_COUNT - 1, emblem));
            templates = normalized(templates);
        }
    }

    public static boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || stack.is(ModTags.CARRIER_EXCLUDED)) return false;
        if (stack.getItem() instanceof BatchCarrierItem || stack.getItem() instanceof RecoveryBatchItem) return false;
        if (!stack.getItem().canFitInsideContainerItems()) return false;
        if (stack.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) return false;
        return stack.getItem() instanceof PotionItem || stack.getItem() instanceof TippedArrowItem || stack.is(ModTags.CARRIER_STORABLE)
                || stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE);
    }

    public static List<ItemStack> read(ItemStack carrier) {
        List<ItemStack> out = emptySlots();
        CompoundTag root = carrier.getTag();
        if (root == null || !root.contains(KEY, Tag.TAG_LIST)) return out;
        ListTag list = root.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot");
            if (slot < 0 || slot >= SLOTS) continue;
            // Legacy or future-invalid contents are still data we own: preserve them and let every insertion path
            // enforce accepts(). Silently hiding them here would make the next ordinary edit destructive.
            out.set(slot, ItemStack.of(entry));
        }
        return out;
    }

    public static void write(ItemStack carrier, List<ItemStack> contents) {
        ListTag list = new ListTag();
        for (int i = 0; i < Math.min(SLOTS, contents.size()); i++) {
            ItemStack s = contents.get(i);
            if (s.isEmpty()) continue;
            CompoundTag entry = s.save(new CompoundTag());
            entry.putByte("Slot", (byte) i);
            list.add(entry);
        }
        if (list.isEmpty()) {
            CompoundTag root = carrier.getTag();
            if (root != null) {
                root.remove(KEY);
                if (root.isEmpty()) carrier.setTag(null);
            }
        } else {
            carrier.getOrCreateTag().put(KEY, list);
        }
    }

    public static Meta meta(ItemStack carrier) {
        CompoundTag root = carrier.getTag();
        if (root == null || !root.contains(META_KEY, Tag.TAG_COMPOUND)) return defaults();
        CompoundTag tag = root.getCompound(META_KEY);
        if (!tag.contains("schema", Tag.TAG_ANY_NUMERIC) || tag.getInt("schema") < 1 || tag.getInt("schema") > SCHEMA) return defaults();
        List<ItemStack> templates = emptySlots();
        if (tag.contains("Templates", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Templates", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int slot = entry.getByte("Slot");
                if (slot < 0 || slot >= SLOTS) continue;
                ItemStack stack = ItemStack.of(entry);
                if (accepts(stack)) templates.set(slot, stack.copy());
            }
        }
        String name = tag.contains("Loadout", Tag.TAG_STRING) ? tag.getString("Loadout") : null;
        int color = tag.contains("Color", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Color") : DEFAULT_LABEL_COLOR;
        return new Meta(tag.getByte("Selected"), name, color, tag.getByte("Emblem"), tag.getBoolean("AdvanceOnEmpty"), templates);
    }

    /** False means a future schema owns the payload and this version must leave it byte-for-byte alone. */
    public static boolean metadataWritable(ItemStack carrier) {
        CompoundTag root = carrier.getTag();
        if (root == null || !root.contains(META_KEY, Tag.TAG_COMPOUND)) return true;
        CompoundTag tag = root.getCompound(META_KEY);
        return !tag.contains("schema", Tag.TAG_ANY_NUMERIC) || tag.getInt("schema") <= SCHEMA;
    }

    public static boolean writeMeta(ItemStack carrier, Meta meta) {
        if (!metadataWritable(carrier)) return false;
        CompoundTag root = carrier.getOrCreateTag();
        CompoundTag tag = root.contains(META_KEY, Tag.TAG_COMPOUND) ? root.getCompound(META_KEY).copy() : new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putByte("Selected", (byte) meta.selected());
        if (meta.loadoutName() == null) tag.remove("Loadout");
        else tag.putString("Loadout", meta.loadoutName());
        tag.putInt("Color", meta.labelColor());
        tag.putByte("Emblem", (byte) meta.emblem());
        tag.putBoolean("AdvanceOnEmpty", meta.advanceOnEmpty());
        ListTag templates = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = meta.templates().get(i);
            if (stack.isEmpty()) continue;
            CompoundTag entry = stack.save(new CompoundTag());
            entry.putByte("Slot", (byte) i);
            templates.add(entry);
        }
        if (templates.isEmpty()) tag.remove("Templates");
        else tag.put("Templates", templates);
        root.put(META_KEY, tag);
        return true;
    }

    public static boolean setSelected(ItemStack carrier, int selected) {
        Meta m = meta(carrier);
        return writeMeta(carrier, new Meta(selected, m.loadoutName(), m.labelColor(), m.emblem(), m.advanceOnEmpty(), m.templates()));
    }

    public static boolean saveLoadout(ItemStack carrier, @Nullable String name) {
        Meta m = meta(carrier);
        return writeMeta(carrier, new Meta(m.selected(), name, m.labelColor(), m.emblem(), m.advanceOnEmpty(), read(carrier)));
    }

    public static boolean isMissing(ItemStack carrier, int slot) {
        if (slot < 0 || slot >= SLOTS) return false;
        ItemStack expected = meta(carrier).templates().get(slot);
        if (expected.isEmpty()) return false;
        ItemStack actual = read(carrier).get(slot);
        return actual.isEmpty() || !ItemStack.isSameItemSameTags(expected, actual) || actual.getCount() < expected.getCount();
    }

    public static boolean isEmpty(ItemStack carrier) {
        return read(carrier).stream().allMatch(ItemStack::isEmpty);
    }

    public static int count(ItemStack carrier) {
        int n = 0;
        for (ItemStack s : read(carrier)) n += s.getCount();
        return n;
    }

    private static Meta defaults() {
        return new Meta(0, null, DEFAULT_LABEL_COLOR, 0, false, emptySlots());
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) out.add(ItemStack.EMPTY);
        return out;
    }

    private static List<ItemStack> normalized(List<ItemStack> source) {
        List<ItemStack> out = emptySlots();
        if (source == null) return List.copyOf(out);
        for (int i = 0; i < Math.min(SLOTS, source.size()); i++) {
            ItemStack stack = source.get(i);
            if (stack != null && accepts(stack)) out.set(i, stack.copy());
        }
        return List.copyOf(out);
    }

    private CarrierContents() {}
}
