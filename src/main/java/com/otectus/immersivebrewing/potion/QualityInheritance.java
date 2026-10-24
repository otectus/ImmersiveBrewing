package com.otectus.immersivebrewing.potion;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Quality through transformations (spec 12.3).
 * <ul>
 * <li>Creating the first effect from water or a base potion earns the new score normally, with a fresh lineage.</li>
 * <li>Any operation on a potion that already has effects (graded, or ordinary at the baseline 50) can not raise it:
 * {@code Q_out = min(Q_in, candidate)}. A modifier or container step keeps the input's extraction; a new effect (a
 * corruption) uses the new preparation.</li>
 * <li>Lineage, purification count and refiner always carry through; the original brewer and label carry through
 * modifiers, a new effect records its own brewer.</li>
 * </ul>
 * Purification is the only operation that may raise a finished potion's score (see the Alembic).
 */
public final class QualityInheritance {

    /** The metadata for the output and whether the inherited cap applied. */
    public record Outcome(BrewingMetadata metadata, boolean capped, int candidate) {}

    public static Outcome brew(ItemStack canonicalInput, @Nullable BrewingMetadata input, boolean createsEffect, int extraction, int control,
                               long freshLineage, @Nullable UUID brewer, @Nullable String brewerName) {
        boolean inputHasEffects = !PotionUtils.getMobEffects(canonicalInput).isEmpty();
        if (!inputHasEffects) {
            int q = QualityMath.quality(extraction, control);
            return new Outcome(BrewingMetadata.brewed(extraction, control, q, freshLineage, brewer, brewerName), false, q);
        }
        int qualityIn = input != null ? input.quality() : QualityMath.BASELINE;
        int extractionIn = input != null ? input.extraction() : QualityMath.RAW_EXTRACTION;
        int e = createsEffect ? extraction : extractionIn;
        int candidate = QualityMath.quality(e, control);
        int q = Math.min(qualityIn, candidate);
        long lineage = input != null && input.lineage() != 0 ? input.lineage() : freshLineage;
        int purifications = input != null ? input.purifications() : 0;
        boolean keepBrewer = !createsEffect && input != null;
        BrewingMetadata out = new BrewingMetadata(e, control, q, lineage, purifications,
                keepBrewer ? input.brewerId() : brewer, keepBrewer ? input.brewerName() : brewerName,
                input != null ? input.refinerName() : null, keepBrewer ? input.label() : null, keepBrewer ? input.note() : null);
        return new Outcome(out, q < candidate, candidate);
    }

    /**
     * A transformation made outside this mod (the ordinary brewing stand): no new score. The potion keeps its lineage,
     * purification count and attribution; its quality can only stay or fall to the baseline (spec 12.3).
     */
    public static BrewingMetadata passThrough(BrewingMetadata input, boolean sameContents) {
        int q = sameContents ? input.quality() : Math.min(input.quality(), QualityMath.BASELINE);
        return new BrewingMetadata(input.extraction(), input.control(), q, input.lineage(), input.purifications(), input.brewerId(),
                input.brewerName(), input.refinerName(), sameContents ? input.label() : null, sameContents ? input.note() : null);
    }

    private QualityInheritance() {}
}
