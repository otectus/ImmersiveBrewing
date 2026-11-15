package com.otectus.immersivebrewing.interaction;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The neighbouring-station offset carried in a {@code SWITCH_STATION} intent's argument: each axis -3..3 packed into
 * 0..342. Anything outside that cube names no station.
 */
public final class StationOffsets {
    public static final int RANGE = 3;
    private static final int SPAN = RANGE * 2 + 1;

    public static int pack(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > RANGE || Math.abs(dy) > RANGE || Math.abs(dz) > RANGE) return -1;
        return ((dx + RANGE) * SPAN + (dy + RANGE)) * SPAN + (dz + RANGE);
    }

    @Nullable
    public static BlockPos unpack(BlockPos from, int packed) {
        if (packed < 0 || packed >= SPAN * SPAN * SPAN) return null;
        int dz = packed % SPAN - RANGE, dy = packed / SPAN % SPAN - RANGE, dx = packed / (SPAN * SPAN) - RANGE;
        return from.offset(dx, dy, dz);
    }

    private StationOffsets() {}
}
