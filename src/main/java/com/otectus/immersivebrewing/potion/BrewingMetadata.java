package com.otectus.immersivebrewing.potion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * This mod's bookkeeping on a finished potion (spec 12.1, item 2): craftsmanship scores, provenance, purification count,
 * label and lineage. It lives under one compound, {@link #ROOT}, beside the untouched canonical contents, and is the only
 * thing this mod ever writes onto a potion stack. Immutable; {@code with*} returns a copy.
 *
 * <p>Version {@link #SCHEMA}. A compound written by a newer schema is preserved byte for byte and treated as baseline
 * quality until a migrator understands it (spec 20.3).
 */
public record BrewingMetadata(int extraction, int control, int quality, long lineage, int purifications,
                              @Nullable UUID brewerId, @Nullable String brewerName, @Nullable String refinerName,
                              @Nullable String label, @Nullable String note) {
    public static final String ROOT = "immersive_brewing";
    public static final int SCHEMA = 1;
    public static final int MAX_LABEL = 48;
    public static final int MAX_NOTE = 120;
    public static final int MAX_NAME = 32;

    public BrewingMetadata {
        extraction = QualityMath.clampScore(extraction);
        control = QualityMath.clampScore(control);
        quality = QualityMath.clampScore(quality);
        purifications = Math.max(0, Math.min(16, purifications));
        brewerName = Text.limit(brewerName, MAX_NAME);
        refinerName = Text.limit(refinerName, MAX_NAME);
        label = Text.limit(label, MAX_LABEL);
        note = Text.limit(note, MAX_NOTE);
    }

    /** A freshly brewed batch. */
    public static BrewingMetadata brewed(int extraction, int control, int quality, long lineage, @Nullable UUID brewer, @Nullable String brewerName) {
        return new BrewingMetadata(extraction, control, quality, lineage, 0, brewer, brewerName, null, null, null);
    }

    public Grade grade() {
        return Grade.of(quality);
    }

    public boolean purified() {
        return purifications > 0;
    }

    public BrewingMetadata withScores(int extraction, int control, int quality) {
        return new BrewingMetadata(extraction, control, quality, lineage, purifications, brewerId, brewerName, refinerName, label, note);
    }

    public BrewingMetadata withPurification(int quality, @Nullable String refiner) {
        return new BrewingMetadata(extraction, control, quality, lineage, purifications + 1, brewerId, brewerName, refiner, label, note);
    }

    public BrewingMetadata withLabel(@Nullable String label, @Nullable String note) {
        return new BrewingMetadata(extraction, control, quality, lineage, purifications, brewerId, brewerName, refinerName, label, note);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", SCHEMA);
        tag.putByte("E", (byte) extraction);
        tag.putByte("C", (byte) control);
        tag.putByte("Q", (byte) quality);
        if (lineage != 0) tag.putLong("lineage", lineage);
        if (purifications > 0) tag.putByte("purified", (byte) purifications);
        if (brewerId != null) tag.putUUID("brewer", brewerId);
        if (brewerName != null) tag.putString("brewerName", brewerName);
        if (refinerName != null) tag.putString("refiner", refinerName);
        if (label != null) tag.putString("label", label);
        if (note != null) tag.putString("note", note);
        return tag;
    }

    /** Parses a compound written by this schema; anything else (missing, newer, malformed) is empty. */
    public static Optional<BrewingMetadata> fromTag(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("schema", Tag.TAG_ANY_NUMERIC)) return Optional.empty();
        int schema = tag.getInt("schema");
        if (schema < 1 || schema > SCHEMA) return Optional.empty();
        if (!tag.contains("Q", Tag.TAG_ANY_NUMERIC)) return Optional.empty();
        UUID brewer = tag.hasUUID("brewer") ? tag.getUUID("brewer") : null;
        return Optional.of(new BrewingMetadata(tag.getByte("E"), tag.getByte("C"), tag.getByte("Q"), tag.getLong("lineage"),
                tag.getByte("purified"), brewer, optString(tag, "brewerName"), optString(tag, "refiner"),
                optString(tag, "label"), optString(tag, "note")));
    }

    public static Optional<BrewingMetadata> read(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND)) return Optional.empty();
        return fromTag(tag.getCompound(ROOT));
    }

    /** True when the stack carries this mod's compound in a schema this version cannot read (preserved, never rewritten). */
    public static boolean hasUnreadableCompound(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(ROOT, Tag.TAG_COMPOUND) && fromTag(tag.getCompound(ROOT)).isEmpty();
    }

    /** Writes the compound onto the stack (replacing an older one of this schema). */
    public static void write(ItemStack stack, BrewingMetadata metadata) {
        stack.getOrCreateTag().put(ROOT, metadata.toTag());
    }

    /** Removes this mod's compound, dropping an emptied tag so the stack stacks with untouched vanilla potions again. */
    public static void strip(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT)) return;
        tag.remove(ROOT);
        if (tag.isEmpty()) stack.setTag(null);
    }

    @Nullable
    private static String optString(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : null;
    }

    /** Literal-text helpers shared by labels, notes and names. */
    public static final class Text {
        /** Trims, drops control and format characters and caps the length in code points; blank becomes null. */
        @Nullable
        public static String limit(@Nullable String value, int maxCodePoints) {
            if (value == null) return null;
            StringBuilder out = new StringBuilder();
            int count = 0;
            for (int i = 0; i < value.length() && count < maxCodePoints; ) {
                int cp = value.codePointAt(i);
                i += Character.charCount(cp);
                if (!allowed(cp)) continue;
                out.appendCodePoint(cp);
                count++;
            }
            String s = out.toString().strip();
            return s.isEmpty() ? null : s;
        }

        /** Whether {@code value} is acceptable as typed (no stripping needed): the server rejects rather than silently edits. */
        public static boolean valid(@Nullable String value, int maxCodePoints) {
            if (value == null || value.isEmpty()) return true;
            if (value.codePointCount(0, value.length()) > maxCodePoints) return false;
            return value.codePoints().allMatch(Text::allowed);
        }

        private static boolean allowed(int cp) {
            if (Character.isISOControl(cp)) return false;
            if (cp == '§') return false; // legacy formatting code
            int type = Character.getType(cp);
            return type != Character.FORMAT && type != Character.PRIVATE_USE && type != Character.SURROGATE
                    && type != Character.UNASSIGNED;
        }

        private Text() {}
    }
}
