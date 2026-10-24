package com.otectus.immersivebrewing.api;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable view of a stack's brewing metadata (spec 16.7). Absent for ordinary potions, which behave as quality 50.
 */
public record BrewingMetadataView(int extraction, int control, int quality, String grade, long lineage, int purifications,
                                  @Nullable UUID brewer, @Nullable String brewerName, @Nullable String refiner,
                                  @Nullable String label, @Nullable String note) {

    public static Optional<BrewingMetadataView> of(ItemStack stack) {
        return com.otectus.immersivebrewing.potion.BrewingMetadata.read(stack).map(m -> new BrewingMetadataView(m.extraction(), m.control(),
                m.quality(), m.grade().name().toLowerCase(java.util.Locale.ROOT), m.lineage(), m.purifications(), m.brewerId(), m.brewerName(),
                m.refinerName(), m.label(), m.note()));
    }
}
