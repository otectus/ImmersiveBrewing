package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;

/** Read-only adaptation of immutable accepted plans and saved simulations. Never resolves recipes. */
public final class StationPresentations {
    public static int[] stageColors(JobLedger ledger) {
        ListTag steps = ledger.snapshot().getList("steps", Tag.TAG_COMPOUND);
        int[] colors = new int[Math.min(64, steps.size()) + 1];
        ItemStack input = ledger.inputs().isEmpty() ? ItemStack.EMPTY : ledger.inputs().get(0);
        colors[0] = PotionUtils.getColor(input);
        for (int i = 1; i < colors.length; i++) {
            ItemStack after = LedgerCodec.loadStack(steps.getCompound(i - 1).getCompound("after"));
            colors[i] = after.isEmpty() ? colors[i - 1] : PotionUtils.getColor(after);
        }
        return colors;
    }

    public static StationVisual frozen(JobLedger ledger) {
        if (ledger == null || ledger.unknownSchema() != null) return StationVisual.IDLE;
        try {
            if (ledger.operation().interactive()) {
                SessionKind kind = switch (ledger.operation()) {
                    case PREPARE -> SessionKind.PREPARATION;
                    case BREW -> SessionKind.BREWING;
                    default -> SessionKind.DISTILLATION;
                };
                return SimHandles.create(kind, ledger.params(), ledger.simState()).visual();
            }
            long[] state = ledger.simState();
            float progress = state.length == 0 ? 0 : Math.min(1, (float) state[0] / Math.max(1, ledger.snapshot().getInt("duration")));
            return new StationVisual(0, 0, .4F, 0, 0, progress, false);
        } catch (RuntimeException unreadable) {
            // Old/new or absent providers must retain the opaque ledger and use a generic sealed appearance.
            return StationVisual.IDLE;
        }
    }
    private StationPresentations() {}
}
