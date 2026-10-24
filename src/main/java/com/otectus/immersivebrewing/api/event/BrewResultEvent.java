package com.otectus.immersivebrewing.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * A kettle brew resolves (spec 16.7). {@link Pre} fires before commit and may veto it (the job is held for recovery with
 * every material intact; nothing already charged is lost) or narrow the quality; inventory mutation still belongs to the
 * station. {@link Post} fires exactly once, after the outputs exist.
 */
public abstract class BrewResultEvent extends Event {
    @Nullable
    private final ServerPlayer player;
    private final BlockPos station;
    private final ItemStack output;
    private final int servings;
    private final int extraction;
    private final int control;

    protected BrewResultEvent(@Nullable ServerPlayer player, BlockPos station, ItemStack output, int servings, int extraction, int control) {
        this.player = player;
        this.station = station;
        this.output = output.copy();
        this.servings = servings;
        this.extraction = extraction;
        this.control = control;
    }

    @Nullable
    public ServerPlayer player() {
        return player;
    }

    public BlockPos station() {
        return station;
    }

    /** One serving of the canonical output (no metadata yet on Pre; with metadata on Post). */
    public ItemStack output() {
        return output.copy();
    }

    public int servings() {
        return servings;
    }

    public int extraction() {
        return extraction;
    }

    public int control() {
        return control;
    }

    @Cancelable
    public static class Pre extends BrewResultEvent {
        private int quality;

        public Pre(@Nullable ServerPlayer player, BlockPos station, ItemStack output, int servings, int extraction, int control, int quality) {
            super(player, station, output, servings, extraction, control);
            this.quality = quality;
        }

        public int quality() {
            return quality;
        }

        /** Narrows the quality; a listener can lower it, never raise it. */
        public void narrowQuality(int quality) {
            this.quality = Math.max(0, Math.min(this.quality, quality));
        }
    }

    public static class Post extends BrewResultEvent {
        private final int quality;

        public Post(@Nullable ServerPlayer player, BlockPos station, ItemStack output, int servings, int extraction, int control, int quality) {
            super(player, station, output, servings, extraction, control);
            this.quality = quality;
        }

        public int quality() {
            return quality;
        }
    }
}
