package com.otectus.immersivebrewing.smoketest;

import com.otectus.immersivebrewing.client.screen.KettlePlanScreen;
import com.otectus.immersivebrewing.network.packet.PlanListS2C;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;

/** Picks plans from the real plan screen, as a player would click them. */
final class PlanAccess {
    static PlanListS2C.Entry swiftness(KettlePlanScreen screen) {
        return screen.plans().entries().stream()
                .filter(e -> e.output().is(Items.POTION) && PotionUtils.getPotion(e.output()) == Potions.SWIFTNESS && e.available())
                .min(java.util.Comparator.comparingInt(e -> e.steps().size()))
                .orElse(null);
    }

    private PlanAccess() {}
}
