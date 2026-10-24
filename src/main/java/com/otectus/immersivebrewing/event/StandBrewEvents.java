package com.otectus.immersivebrewing.event;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.QualityInheritance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.event.brewing.PotionBrewEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Graded potions brewed further in an ordinary brewing stand (spec 12.3). Vanilla mixing builds fresh stacks and would
 * drop the metadata, which would also reset the purification count. Pre and Post fire synchronously inside one
 * {@code doBrew}, so the bottles seen in Pre are matched to the results in Post: a container-only conversion (same
 * potion) keeps the quality; any other change keeps lineage and purification but can not exceed the baseline.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID)
public final class StandBrewEvents {
    private static final ThreadLocal<ItemStack[]> BEFORE = new ThreadLocal<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPre(PotionBrewEvent.Pre event) {
        if (event.isCanceled()) {
            BEFORE.remove();
            return;
        }
        ItemStack[] before = new ItemStack[3];
        boolean any = false;
        for (int i = 0; i < 3 && i < event.getLength(); i++) {
            before[i] = event.getItem(i).copy();
            any |= BrewingMetadata.read(before[i]).isPresent();
        }
        if (any) BEFORE.set(before);
        else BEFORE.remove();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPost(PotionBrewEvent.Post event) {
        ItemStack[] before = BEFORE.get();
        BEFORE.remove();
        if (before == null) return;
        for (int i = 0; i < 3 && i < event.getLength(); i++) {
            ItemStack in = before[i];
            ItemStack out = event.getItem(i);
            if (in == null || in.isEmpty() || out.isEmpty() || BrewingMetadata.read(out).isPresent()) continue;
            Optional<BrewingMetadata> meta = BrewingMetadata.read(in);
            if (meta.isEmpty()) continue;
            boolean sameContents = PotionUtils.getPotion(in) == PotionUtils.getPotion(out);
            BrewingMetadata.write(out, QualityInheritance.passThrough(meta.get(), sameContents));
        }
    }

    private StandBrewEvents() {}
}
