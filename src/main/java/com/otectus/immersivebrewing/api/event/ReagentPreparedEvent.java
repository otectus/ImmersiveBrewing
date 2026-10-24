package com.otectus.immersivebrewing.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.Nullable;

/** Kits were prepared at a Mortar and placed on its shelf. Fires once, after commit. */
public class ReagentPreparedEvent extends Event {
    @Nullable
    private final ServerPlayer player;
    private final BlockPos station;
    private final ItemStack kits;
    private final int extraction;

    public ReagentPreparedEvent(@Nullable ServerPlayer player, BlockPos station, ItemStack kits, int extraction) {
        this.player = player;
        this.station = station;
        this.kits = kits.copy();
        this.extraction = extraction;
    }

    @Nullable
    public ServerPlayer player() {
        return player;
    }

    public BlockPos station() {
        return station;
    }

    public ItemStack kits() {
        return kits.copy();
    }

    public int extraction() {
        return extraction;
    }
}
