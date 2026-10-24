package com.otectus.immersivebrewing.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A supported plan and its exact costs for a serving count (spec 16.7), as the server would charge them. */
public record PlanQuery(String key, ItemStack input, ItemStack output, List<Item> reagents, List<Integer> reagentCounts,
                        int fuelCharges, List<ItemStack> remainders, boolean contextual) {}
