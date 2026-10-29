package com.otectus.immersivebrewing.integration.smithing;

import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.InterModComms;

import java.util.function.Predicate;

/**
 * Immersive Smithing (spec 17), reached only after {@code ModList.isLoaded("immersive_smithing")}. The iron paddle's
 * smithing recipe is plain data ({@code recipes/smithing/iron_brewing_paddle.json}, conditioned on the mod and
 * {@code smithingIntegration}); Smithing's own {@code ItemStack.hurt} hook then lets its quality change paddle wear, so
 * no second durability rule exists here. This class only keeps item-holding containers out of Smithing's material
 * handling: a Batch Carrier or a Recovery Batch is never meltable, recyclable or graded metal equipment, whatever its
 * recipe contains. The IMC key is a compile-time constant, so nothing of Smithing loads from this call.
 */
public final class SmithingIntegration {
    public static final String MOD_ID = "immersive_smithing";

    public static void enqueueIMC() {
        // Public IMC v1 key; no classes from the optional jar are needed by the core build.
        InterModComms.sendTo(MOD_ID, "exclusion", () -> (Predicate<ItemStack>) SmithingIntegration::holdsItems);
    }

    static boolean holdsItems(ItemStack stack) {
        return stack.is(ModItems.BATCH_CARRIER.get()) || stack.is(ModItems.RECOVERY_BATCH.get());
    }

    private SmithingIntegration() {}
}
