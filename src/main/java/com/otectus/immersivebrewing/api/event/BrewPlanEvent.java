package com.otectus.immersivebrewing.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * A plan is offered ({@link #preview()} true, once per listed plan) or about to be reserved (false). Cancelling refuses
 * it: a preview cancel hides the plan, a start cancel refuses to reserve anything. Server thread; no inventory has
 * changed when it fires.
 */
@Cancelable
public class BrewPlanEvent extends Event {
    private final ServerPlayer player;
    private final BlockPos station;
    private final String planKey;
    private final ItemStack input;
    private final ItemStack output;
    private final int servings;
    private final boolean preview;

    public BrewPlanEvent(ServerPlayer player, BlockPos station, String planKey, ItemStack input, ItemStack output, int servings, boolean preview) {
        this.player = player;
        this.station = station;
        this.planKey = planKey;
        this.input = input.copy();
        this.output = output.copy();
        this.servings = servings;
        this.preview = preview;
    }

    public ServerPlayer player() {
        return player;
    }

    public BlockPos station() {
        return station;
    }

    public String planKey() {
        return planKey;
    }

    public ItemStack input() {
        return input.copy();
    }

    public ItemStack output() {
        return output.copy();
    }

    public int servings() {
        return servings;
    }

    public boolean preview() {
        return preview;
    }
}
