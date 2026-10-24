package com.otectus.immersivebrewing.advancement;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

/** Registers the milestone trigger and awards milestones from committed outcomes. */
public final class ModAdvancements {
    public static final MilestoneTrigger MILESTONE = new MilestoneTrigger();

    /** Called during common setup (the trigger registry is not thread-safe). */
    public static void register() {
        CriteriaTriggers.register(MILESTONE);
    }

    public static void milestone(ServerPlayer player, Milestone milestone) {
        if (player instanceof FakePlayer) return;
        MILESTONE.trigger(player, milestone);
    }

    private ModAdvancements() {}
}
