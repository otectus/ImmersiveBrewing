package com.otectus.immersivebrewing.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * An Alembic job committed: a purification ({@link #purified()} true, with the quality before and after) or a
 * reclamation. Fires once, after the outputs exist.
 */
public class BatchRefinedEvent extends Event {
    @Nullable
    private final ServerPlayer player;
    private final BlockPos station;
    private final ItemStack output;
    private final int count;
    private final boolean purified;
    private final int qualityBefore;
    private final int qualityAfter;

    public BatchRefinedEvent(@Nullable ServerPlayer player, BlockPos station, ItemStack output, int count, boolean purified, int qualityBefore, int qualityAfter) {
        this.player = player;
        this.station = station;
        this.output = output.copy();
        this.count = count;
        this.purified = purified;
        this.qualityBefore = qualityBefore;
        this.qualityAfter = qualityAfter;
    }

    @Nullable
    public ServerPlayer player() {
        return player;
    }

    public BlockPos station() {
        return station;
    }

    public ItemStack output() {
        return output.copy();
    }

    public int count() {
        return count;
    }

    public boolean purified() {
        return purified;
    }

    public int qualityBefore() {
        return qualityBefore;
    }

    public int qualityAfter() {
        return qualityAfter;
    }
}
